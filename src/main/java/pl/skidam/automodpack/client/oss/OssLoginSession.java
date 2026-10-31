package pl.skidam.automodpack.client.oss;

import com.google.gson.*;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import pl.skidam.automodpack_core.oss.*;
import pl.skidam.automodpack_core.oss.client.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

/** One authenticated login negotiation. Game traffic carries metadata only; bytes come from OSS. */
public final class OssLoginSession {
    private static final int BASE = 0x6F535331;
    private static final String CHANNEL = "automodpack:oss_authorization";
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OSS-login-keepalive"); t.setDaemon(true); return t;
    });
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OSS-client-update"); t.setDaemon(true); return t;
    });
    private final Minecraft client;
    private final Connection connection;
    private final Path root;
    private final Screen parent;
    private final ConsentCountdown countdown = new ConsentCountdown();
    private PackTrust trust;
    private VerifiedManifest manifest;
    private StagedInstaller.Stage stage;
    private List<ManifestFile> files;
    private ManifestFile requested;
    private int fileIndex, lastId = BASE - 1;
    private Integer pendingId;
    private JsonObject queuedResponse;
    private String nonce;
    private long sessionDeadline, offerDeadline;
    private UUID account;
    private volatile boolean accepted, staged, cancelled, dismissed, downloading, connectionEnded;
    private volatile String status = "正在验证本地预配的可信服务器和签名清单……";
    private ScheduledFuture<?> heartbeat;

    public OssLoginSession(Minecraft client, Connection connection) {
        this.client = client; this.connection = connection; root = client.gameDirectory.toPath(); parent = client.screen;
    }
    public boolean handle(ClientboundCustomQueryPacket packet) {
        if (!CHANNEL.equals(packet.getIdentifier().toString())) return false;
        int id = packet.getTransactionId();
        FriendlyByteBuf buffer = packet.getData();
        if (buffer == null || buffer.readableBytes() > 1_048_576) { fail(id); return true; }
        byte[] bytes = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), bytes);
        try {
            // Verify keepalives without queueing behind a long object download.
            JsonObject signed = id == BASE ? null : OssProtocol.signedMessage(trust, bytes, nonce, id);
            synchronized (this) {
                if (cancelled || id != lastId + 1 || (nonce == null && id != BASE)) { fail(id); return true; }
                lastId = id; pendingId = id;
            }
            if (signed != null && "keepalive".equals(OssProtocol.string(signed, "type"))) {
                if (!accepted || !manifest.releaseId().equals(OssProtocol.string(signed,"releaseId")) ||
                        manifest.sequence() != OssProtocol.number(signed,"sequence")) throw new IOException("Invalid keepalive");
                flush();
            } else worker.execute(() -> {
                try {
                    if (id == BASE) challenge(bytes);
                    else {
                        String type = OssProtocol.string(signed, "type");
                        if (id == BASE + 1 && type.equals("offer")) offer(signed);
                        else updateMessage(signed, type);
                    }
                } catch (Exception failure) { fail(null); }
            });
        } catch (Exception failure) { fail(id); }
        return true;
    }
    private void challenge(byte[] bytes) throws IOException {
        JsonObject object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (OssProtocol.number(object,"schema") != 1 || !object.has("challenge") || !object.get("challenge").getAsBoolean()) throw new IOException("Invalid challenge");
        if (!new ConsentStore(root).enabled()) { respond(response("declined")); worker.shutdown(); return; }
        trust = TrustStore.load(root.resolve("config/automodpack-oss/trust.json")).requirePack(OssProtocol.string(object,"packId"));
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        JsonObject answer = response("challenge"); answer.addProperty("nonce", nonce); respond(answer);
    }
    private void offer(JsonObject object) throws IOException {
        var offer = OssProtocol.offer(trust, object, client.getUser().getProfileId(), client.getUser().getName(), Instant.now().getEpochSecond());
        account = offer.account();
        offerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(offer.expiresAt() - Instant.now().getEpochSecond());
        showScreen(new OssStatusScreen(this));
        byte[] envelope = OssDownloads.manifest(trust, offer.manifestUrl(), offer.manifestTransportUrl());
        if (!ManifestVerifier.sha256(envelope).equals(offer.manifestSha256())) throw new IOException("Manifest does not match announced active pin");
        manifest = ManifestVerifier.verify(envelope, trust);
        if (!manifest.releaseId().equals(offer.releaseId()) || manifest.sequence() != offer.sequence()) throw new IOException("Server did not announce this active release");
        StagedInstaller.observeManifest(root, manifest);
        if (new ConsentStore(root).permits(manifest, account)) beginUpdate();
        else showScreen(new OssConsentScreen(this, trust, countdown, approved -> worker.execute(() -> {
            try {
                if (!approved) { respond(response("declined")); restoreParent(); worker.shutdown(); return; }
                // The click handler and this persisted grant both require the monotonic gate.
                if (!countdown.canAccept()) throw new IOException("Consent page has not been visible for ten seconds");
                checkOfferActive();
                new ConsentStore(root).grant(manifest, account);
                beginUpdate();
            } catch (Exception failure) { fail(null); }
        })));
    }
    private void beginUpdate() throws IOException {
        checkOfferActive();
        if (cancelled || !new ConsentStore(root).permits(manifest, account)) throw new IOException("Consent was revoked");
        if (StagedInstaller.matchesInstalled(root, manifest)) { respond(response("ready")); restoreParent(); worker.shutdown(); return; }
        stage = StagedInstaller.prepare(root, manifest, manifest.authorityId(), manifest.consentProtocolVersion(), manifest.managementScopeVersion());
        files = stage.requiredFiles(); accepted = true;
        status = "授权已保存。正在通过可信 OSS 下载并校验更新；整合包文件将在下次手动启动时应用。";
        showScreen(new OssStatusScreen(this));
        respond(response("accepted"));
        heartbeat = HEARTBEAT.scheduleAtFixedRate(this::keepalive, 20, 20, TimeUnit.SECONDS);
    }
    private void checkOfferActive() throws IOException {
        if (cancelled || connectionEnded || !connection.isConnected() || System.nanoTime() - offerDeadline >= 0)
            throw new IOException("Authenticated authorization offer expired");
    }
    private void updateMessage(JsonObject object, String type) throws IOException {
        if (!accepted || manifest == null || !manifest.releaseId().equals(OssProtocol.string(object,"releaseId")) ||
                manifest.sequence() != OssProtocol.number(object,"sequence")) throw new IOException("Unexpected update metadata");
        if (type.equals("download")) {
            long expires = OssProtocol.number(object,"sessionExpiresAt"), now = Instant.now().getEpochSecond();
            if (expires <= now || expires > now + 3600) throw new IOException("Invalid update session deadline");
            sessionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(expires - now);
            nextFile();
        } else if (type.equals("keepalive")) {
            flush(); // A download worker may already have queued the next file or completion.
        } else if (type.equals("file")) {
            if (requested == null || !requested.path().equals(OssProtocol.string(object,"path")) ||
                    !requested.url().equals(URI.create(OssProtocol.string(object,"canonicalUrl")))) throw new IOException("Unexpected OSS object");
            long expiry = OssProtocol.number(object,"expiresAt"), now = Instant.now().getEpochSecond();
            if (expiry <= now || expiry > now + 300) throw new IOException("Invalid download URL lifetime");
            URI url = URI.create(OssProtocol.string(object,"url"));
            trust.validateTransportUrl(requested.url(), url);
            checkActive(); downloading = true;
            status = "正在下载并校验 " + (fileIndex + 1) + " / " + files.size() + "：" + requested.path() + "\n下载完成后请手动重启游戏，下次启动时应用。";
            try { OssDownloads.file(trust, stage, requested, url); }
            finally { downloading = false; }
            checkActive(); fileIndex++; requested = null; nextFile();
        } else throw new IOException("Unsupported update metadata");
    }
    private void checkActive() throws IOException {
        if (cancelled || connectionEnded || !connection.isConnected() || System.nanoTime() - sessionDeadline >= 0 ||
                !new ConsentStore(root).permits(manifest, account)) throw new IOException("Update cancelled, expired or consent revoked");
    }
    private void nextFile() throws IOException {
        checkActive();
        if (fileIndex < files.size()) {
            requested = files.get(fileIndex);
            JsonObject answer = response("file"); answer.addProperty("releaseId", manifest.releaseId()); answer.addProperty("path", requested.path()); respond(answer);
        } else {
            stage.finish(); staged = true; stopHeartbeat();
            status = "下载和签名清单校验已完成，更新已暂存。请自行退出并重新启动游戏；下次模组加载前将安全应用更新。随后请手动加入服务器。";
            respond(response("complete"));
        }
    }
    private JsonObject response(String value) {
        JsonObject answer = new JsonObject(); answer.addProperty("schema", 1); answer.addProperty("status", value);
        if (nonce != null) answer.addProperty("clientNonce", nonce);
        return answer;
    }
    private synchronized void respond(JsonObject answer) { queuedResponse = answer; flush(); }
    private synchronized void flush() {
        if (pendingId == null || queuedResponse == null || !connection.isConnected()) return;
        send(pendingId, queuedResponse); pendingId = null; queuedResponse = null;
    }
    private void send(int id, JsonObject object) {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        connection.send(new ServerboundCustomQueryPacket(id, new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))));
    }
    private synchronized void keepalive() {
        if (!accepted || cancelled || staged || connectionEnded) return;
        if (!connection.isConnected() || (sessionDeadline != 0 && System.nanoTime() - sessionDeadline >= 0)) { fail(null); return; }
        if (sessionDeadline != 0 && pendingId != null && queuedResponse == null) { send(pendingId, response("keepalive")); pendingId = null; }
    }
    private synchronized void fail(Integer id) {
        if (dismissed) { stopHeartbeat(); return; }
        // Never log bearer URLs, nonces, usernames or exception messages supplied by a remote peer.
        LOGGER.warn("OSS synchronization refused or interrupted; no unsigned fallback is available");
        if (id != null) pendingId = id;
        respond(response("error")); stopHeartbeat();
        if (accepted) {
            cancelled = true;
            status = "同步已停止，现有整合包保持原状。已完整校验的暂存文件会保留，可手动再次连接后重试；冲突文件请手动检查。没有自动重启或重新连接。";
            showScreen(new OssStatusScreen(this));
        } else { restoreParent(); worker.shutdown(); }
    }
    private void showScreen(Screen screen) {
        client.execute(() -> { if (!dismissed && client.level == null) client.setScreen(screen); });
    }
    private void restoreParent() {
        client.execute(() -> { if (!dismissed && client.level == null) client.setScreen(parent); });
    }
    private void stopHeartbeat() { if (heartbeat != null) heartbeat.cancel(false); }
    public String status() { return status; }
    public void tickConnection() {
        if (!connectionEnded) {
            if (connection.isConnected()) connection.tick();
            else { connection.handleDisconnection(); onDisconnected(); }
        }
    }
    public void onDisconnected() {
        connectionEnded = true; stopHeartbeat();
        if (accepted && !dismissed) {
            if (!staged) { cancelled = true; status = "连接已断开，更新尚未完成。已校验暂存文件会保留，请手动再次连接后重试。现有整合包未应用更新。"; }
            showScreen(new OssStatusScreen(this));
        }
        worker.shutdown();
    }
    public void cancel() {
        dismissed = true; cancelled = true; stopHeartbeat();
        if (connection.isConnected()) {
            respond(response(accepted ? "error" : "declined")); connection.disconnect(net.minecraft.network.chat.Component.literal("已取消自动同步"));
        }
        worker.shutdownNow();
    }
}
