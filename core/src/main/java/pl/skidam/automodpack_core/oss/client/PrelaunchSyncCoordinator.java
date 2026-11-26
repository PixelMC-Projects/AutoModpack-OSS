package pl.skidam.automodpack_core.oss.client;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

import pl.skidam.automodpack_core.oss.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates pre-launch modpack synchronization before Forge scans mods: fast check -> optional
 * first-time consent -> incremental OSS download -> immediate atomic apply.
 */
public final class PrelaunchSyncCoordinator {
    private static final int BATCH_SIZE = 48;

    public enum Phase {
        CHECKING,
        DOWNLOADING,
        APPLYING,
        APPLIED,
        SELF_UPDATE_REQUIRED,
        CONFLICT,
        ERROR
    }

    public enum FileState {
        QUEUED,
        DOWNLOADING,
        VERIFIED,
        CONFLICT
    }

    public record FileRow(
            String path, long size, FileState state, long verifiedAtMs, String note) {}

    public record UiSnapshot(
            Phase phase,
            String packId,
            String identity,
            ReleaseChannel channel,
            int totalFiles,
            int doneFiles,
            int reusedFiles,
            long totalBytes,
            long downloadedBytes,
            double speedBytesPerSec,
            List<FileRow> rows,
            String message) {}

    public interface PrelaunchUi extends AutoCloseable {
        boolean promptConsent(
                PackTrust trust, VerifiedManifest manifest, ConsentCountdown countdown);

        void onSnapshot(UiSnapshot snapshot);

        boolean isSkipped();

        void waitUntilClosed();

        @Override
        void close();
    }

    public interface Downloader {
        byte[] manifest(PackTrust trust, URI canonical, URI transport) throws IOException;

        void file(
                PackTrust trust,
                StagedInstaller.Stage stage,
                ManifestFile file,
                URI transport,
                OssDownloads.ProgressListener listener)
                throws IOException;
    }

    public record Outcome(
            boolean appliedNow,
            boolean skipped,
            boolean offlineOrUnavailable,
            StagedInstaller.ApplyResult applyResult,
            String message) {}

    public static final Downloader DEFAULT_DOWNLOADER =
            new Downloader() {
                @Override
                public byte[] manifest(PackTrust trust, URI canonical, URI transport)
                        throws IOException {
                    return OssDownloads.manifest(trust, canonical, transport);
                }

                @Override
                public void file(
                        PackTrust trust,
                        StagedInstaller.Stage stage,
                        ManifestFile file,
                        URI transport,
                        OssDownloads.ProgressListener listener)
                        throws IOException {
                    OssDownloads.file(trust, stage, file, transport, listener);
                }
            };

    private final PrelaunchGatewayClient gateway;
    private final Downloader downloader;

    public PrelaunchSyncCoordinator() {
        this(new PrelaunchGatewayClient(), DEFAULT_DOWNLOADER);
    }

    public PrelaunchSyncCoordinator(PrelaunchGatewayClient gateway, Downloader downloader) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
    }

    public Outcome run(
            Path gameRoot, TrustStore trustStore, ConsentStore consents, PrelaunchUi ui) {
        if (!consents.enabled()) {
            return new Outcome(false, true, false, null, "Sync disabled by user");
        }
        List<PackTrust> candidates = new ArrayList<>();
        for (PackTrust p : trustStore.packs()) {
            if (p.authorizationEndpoint() != null) candidates.add(p);
        }
        if (candidates.isEmpty()) {
            return new Outcome(false, false, true, null, "No pre-launch endpoint configured");
        }

        Outcome lastOutcome = null;
        for (PackTrust trust : candidates) {
            Outcome out = syncSinglePack(gameRoot, trustStore, trust, consents, ui);
            if (lastOutcome == null || out.appliedNow() || out.applyResult() != null) {
                lastOutcome = out;
            }
        }
        return lastOutcome != null
                ? lastOutcome
                : new Outcome(false, false, false, null, "Up to date");
    }

    private Outcome syncSinglePack(
            Path gameRoot,
            TrustStore trustStore,
            PackTrust trust,
            ConsentStore consents,
            PrelaunchUi ui) {
        ReleaseChannel desiredChannel = consents.channel();
        StagedInstaller.InstalledSummary installed;
        try {
            installed = StagedInstaller.installedSummary(gameRoot, trust.packId());
        } catch (IOException e) {
            LOGGER.warn("Pre-launch failed to read installed summary: {}", e.getMessage());
            return new Outcome(false, false, true, null, e.getMessage());
        }

        PrelaunchGatewayClient.CheckResult check;
        try {
            check =
                    gateway.check(
                            trust,
                            desiredChannel,
                            installed.sequence(),
                            installed.payloadDigest(),
                            installed.channel());
        } catch (IOException offline) {
            // Offline or gateway unreachable: never block singleplayer or normal boot.
            LOGGER.info("Pre-launch sync skipped (gateway unreachable): {}", offline.getMessage());
            return new Outcome(false, false, true, null, offline.getMessage());
        }

        if (check.upToDate()) {
            LOGGER.info(
                    "Pre-launch sync: {} ({}) already up to date (seq={})",
                    trust.packId(),
                    check.channel().id(),
                    check.sequence());
            return new Outcome(false, false, false, null, "Up to date");
        }

        VerifiedManifest manifest;
        try {
            byte[] rawManifest =
                    downloader.manifest(
                            trust, check.manifestCanonicalUrl(), check.manifestSignedUrl());
            manifest = ManifestVerifier.verify(rawManifest, trust);
            if (!manifest.releaseId().equals(check.releaseId())
                    || manifest.sequence() != check.sequence()) {
                throw new IOException("Signed manifest release mismatch with gateway metadata");
            }
            if (StagedInstaller.matchesInstalled(gameRoot, manifest)) {
                LOGGER.info("Pre-launch sync: local files already match {}", manifest.releaseId());
                return new Outcome(false, false, false, null, "Matches installed");
            }
        } catch (IOException ex) {
            LOGGER.warn("Pre-launch manifest check failed: {}", ex.getMessage());
            return new Outcome(false, false, true, null, ex.getMessage());
        }

        // Now we actually have an update to apply -> open the liquid-glass UI.
        boolean uiOpened = false;
        try {
            if (!consents.hasAnyGrant(manifest)) {
                uiOpened = true;
                ConsentCountdown countdown = new ConsentCountdown();
                boolean approved = ui.promptConsent(trust, manifest, countdown);
                if (!approved) {
                    LOGGER.info("Pre-launch sync declined by user for {}", trust.packId());
                    return new Outcome(false, true, false, null, "Declined by user");
                }
                consents.grantInstance(manifest);
            }

            uiOpened = true;
            StagedInstaller.Stage stage;
            try {
                stage =
                        StagedInstaller.prepare(
                                gameRoot,
                                manifest,
                                manifest.authorityId(),
                                manifest.consentProtocolVersion(),
                                manifest.managementScopeVersion(),
                                desiredChannel);
            } catch (IOException conflict) {
                LOGGER.warn("Pre-launch conflict detected: {}", conflict.getMessage());
                ui.onSnapshot(
                        new UiSnapshot(
                                Phase.CONFLICT,
                                trust.packId(),
                                trust.identity(),
                                manifest.channel(),
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                List.of(),
                                conflict.getMessage()));
                ui.waitUntilClosed();
                return new Outcome(
                        false,
                        false,
                        false,
                        new StagedInstaller.ApplyResult(
                                StagedInstaller.Status.CONFLICT, conflict.getMessage()),
                        conflict.getMessage());
            }

            List<ManifestFile> required = stage.requiredFiles();
            int reused = Math.max(0, manifest.files().size() - required.size());
            long totalBytes = 0L;
            List<FileRow> rows = new ArrayList<>(required.size());
            for (ManifestFile f : required) {
                totalBytes += f.size();
                rows.add(new FileRow(f.path(), f.size(), FileState.QUEUED, 0L, ""));
            }

            AtomicLong downloadedBytes = new AtomicLong(0L);
            long speedStartNs = System.nanoTime();
            int doneFiles = 0;

            ui.onSnapshot(
                    new UiSnapshot(
                            Phase.DOWNLOADING,
                            trust.packId(),
                            trust.identity(),
                            manifest.channel(),
                            required.size(),
                            0,
                            reused,
                            totalBytes,
                            0L,
                            0.0,
                            List.copyOf(rows),
                            "正在从私有 OSS 拉取增量文件…"));

            for (int start = 0; start < required.size(); start += BATCH_SIZE) {
                if (ui.isSkipped()) {
                    LOGGER.info("Pre-launch sync skipped by user during download");
                    return new Outcome(false, true, false, null, "Skipped by user");
                }
                int end = Math.min(required.size(), start + BATCH_SIZE);
                List<ManifestFile> batch = required.subList(start, end);
                List<String> batchPaths = new ArrayList<>(batch.size());
                for (ManifestFile mf : batch) batchPaths.add(mf.path());

                PrelaunchGatewayClient.SignedFilesResult signedBatch =
                        gateway.signFiles(
                                trust, manifest.channel(), manifest.releaseId(), batchPaths);

                for (int i = 0; i < batch.size(); i++) {
                    if (ui.isSkipped()) {
                        LOGGER.info("Pre-launch sync skipped by user during file download");
                        return new Outcome(false, true, false, null, "Skipped by user");
                    }
                    int rowIndex = start + i;
                    ManifestFile mf = batch.get(i);
                    PrelaunchGatewayClient.SignedFileEntry entry =
                            signedBatch.files().get(mf.path());
                    if (entry == null) {
                        throw new IOException("Missing signed URL for " + mf.path());
                    }
                    rows.set(
                            rowIndex,
                            new FileRow(mf.path(), mf.size(), FileState.DOWNLOADING, 0L, ""));
                    final int currentDone = doneFiles;
                    final long finalTotalBytes = totalBytes;
                    final AtomicLong lastUiEmitNs = new AtomicLong(0L);

                    ui.onSnapshot(
                            new UiSnapshot(
                                    Phase.DOWNLOADING,
                                    trust.packId(),
                                    trust.identity(),
                                    manifest.channel(),
                                    required.size(),
                                    currentDone,
                                    reused,
                                    finalTotalBytes,
                                    downloadedBytes.get(),
                                    computeSpeed(downloadedBytes.get(), speedStartNs),
                                    List.copyOf(rows),
                                    "正在下载 " + mf.path()));

                    downloader.file(
                            trust,
                            stage,
                            mf,
                            entry.signedUrl(),
                            delta -> {
                                long dl = downloadedBytes.addAndGet(delta);
                                long nowNs = System.nanoTime();
                                if (nowNs - lastUiEmitNs.get() >= 50_000_000L) {
                                    lastUiEmitNs.set(nowNs);
                                    ui.onSnapshot(
                                            new UiSnapshot(
                                                    Phase.DOWNLOADING,
                                                    trust.packId(),
                                                    trust.identity(),
                                                    manifest.channel(),
                                                    required.size(),
                                                    currentDone,
                                                    reused,
                                                    finalTotalBytes,
                                                    dl,
                                                    computeSpeed(dl, speedStartNs),
                                                    List.copyOf(rows),
                                                    "正在下载 " + mf.path()));
                                }
                            });

                    doneFiles++;
                    rows.set(
                            rowIndex,
                            new FileRow(
                                    mf.path(),
                                    mf.size(),
                                    FileState.VERIFIED,
                                    System.currentTimeMillis(),
                                    ""));
                    ui.onSnapshot(
                            new UiSnapshot(
                                    Phase.DOWNLOADING,
                                    trust.packId(),
                                    trust.identity(),
                                    manifest.channel(),
                                    required.size(),
                                    doneFiles,
                                    reused,
                                    finalTotalBytes,
                                    downloadedBytes.get(),
                                    computeSpeed(downloadedBytes.get(), speedStartNs),
                                    List.copyOf(rows),
                                    "已校验 " + mf.path()));
                }
            }

            if (ui.isSkipped()) {
                return new Outcome(false, true, false, null, "Skipped by user");
            }

            ui.onSnapshot(
                    new UiSnapshot(
                            Phase.APPLYING,
                            trust.packId(),
                            trust.identity(),
                            manifest.channel(),
                            required.size(),
                            doneFiles,
                            reused,
                            totalBytes,
                            totalBytes,
                            computeSpeed(totalBytes, speedStartNs),
                            List.copyOf(rows),
                            "正在原子替换受管文件并保留备份…"));

            stage.finish();
            StagedInstaller.ApplyResult applied =
                    StagedInstaller.applyPending(
                            gameRoot, trustStore, consents::hasAnyGrant, desiredChannel);
            LOGGER.info(
                    "Pre-launch immediate apply completed: {} ({})",
                    applied.status(),
                    applied.message());

            if (applied.status() == StagedInstaller.Status.CONFLICT) {
                ui.onSnapshot(
                        new UiSnapshot(
                                Phase.CONFLICT,
                                trust.packId(),
                                trust.identity(),
                                manifest.channel(),
                                required.size(),
                                doneFiles,
                                reused,
                                totalBytes,
                                totalBytes,
                                0.0,
                                List.copyOf(rows),
                                applied.message()));
                ui.waitUntilClosed();
                return new Outcome(false, false, false, applied, applied.message());
            }

            ui.onSnapshot(
                    new UiSnapshot(
                            Phase.APPLIED,
                            trust.packId(),
                            trust.identity(),
                            manifest.channel(),
                            required.size(),
                            doneFiles,
                            reused,
                            totalBytes,
                            totalBytes,
                            0.0,
                            List.copyOf(rows),
                            "更新已全部就位，正在直接启动游戏…"));
            return new Outcome(
                    applied.status() == StagedInstaller.Status.APPLIED,
                    false,
                    false,
                    applied,
                    applied.message());
        } catch (Exception ex) {
            LOGGER.warn("Pre-launch sync encountered error: {}", ex.getMessage());
            if (uiOpened && !ui.isSkipped()) {
                ui.onSnapshot(
                        new UiSnapshot(
                                Phase.ERROR,
                                trust.packId(),
                                trust.identity(),
                                manifest.channel(),
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                List.of(),
                                ex.getMessage() == null ? "同步过程中发生异常" : ex.getMessage()));
                ui.waitUntilClosed();
            }
            return new Outcome(false, false, false, null, ex.getMessage());
        } finally {
            if (uiOpened) {
                try {
                    ui.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static double computeSpeed(long bytes, long startNs) {
        double elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0;
        return elapsedSec > 0.05 ? bytes / elapsedSec : 0.0;
    }
}
