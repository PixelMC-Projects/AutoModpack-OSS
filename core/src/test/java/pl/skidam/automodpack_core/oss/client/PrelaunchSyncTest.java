package pl.skidam.automodpack_core.oss.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.oss.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

class PrelaunchSyncTest {
    @TempDir Path tempDir;

    private Path gameRoot;
    private KeyPair publisherKey;
    private KeyPair serverKey;
    private HttpsServer httpsServer;
    private String certSha256;
    private URI gatewayUri;
    private byte[] manifestEnvelopeBytes;
    private VerifiedManifest verifiedManifest;
    private Map<String, byte[]> fileContents;

    private final AtomicInteger checkCalls = new AtomicInteger();
    private final AtomicInteger signFilesCalls = new AtomicInteger();
    private final AtomicBoolean tamperGatewaySig = new AtomicBoolean(false);

    @BeforeEach
    void setup() throws Exception {
        gameRoot = tempDir.resolve("game");
        Files.createDirectories(gameRoot);

        KeyPairGenerator edGen = KeyPairGenerator.getInstance("Ed25519");
        publisherKey = edGen.generateKeyPair();
        serverKey = edGen.generateKeyPair();

        // Generate temporary self-signed TLS certificate via JDK keytool
        Path keystorePath = tempDir.resolve("gateway.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process proc =
                new ProcessBuilder(
                                keytool,
                                "-genkeypair",
                                "-alias",
                                "gateway",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                keystorePath.toString(),
                                "-storepass",
                                "testpass",
                                "-dname",
                                "CN=127.0.0.1",
                                "-ext",
                                "SAN=ip:127.0.0.1,dns:localhost",
                                "-validity",
                                "1")
                        .redirectErrorStream(true)
                        .start();
        String keytoolOut =
                new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, proc.waitFor(), "keytool failed: " + keytoolOut);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            ks.load(in, "testpass".toCharArray());
        }
        Certificate cert = ks.getCertificate("gateway");
        certSha256 =
                HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));

        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "testpass".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);

        fileContents = new LinkedHashMap<>();
        fileContents.put(
                "mods/sample-mod.jar", "sample-jar-bytes-v1".getBytes(StandardCharsets.UTF_8));
        fileContents.put("config/sample.toml", "enabled = true\n".getBytes(StandardCharsets.UTF_8));

        httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(ssl));
        int port = httpsServer.getAddress().getPort();
        gatewayUri = URI.create("https://127.0.0.1:" + port + "/api/modpack/prelaunch");

        PackTrust trust = buildTrust(certSha256);
        manifestEnvelopeBytes = buildSignedManifest("r1", 1L, fileContents);
        verifiedManifest = ManifestVerifier.verify(manifestEnvelopeBytes, trust);

        httpsServer.createContext("/api/modpack/prelaunch", this::handleGateway);
        httpsServer.start();
    }

    @AfterEach
    void teardown() {
        if (httpsServer != null) httpsServer.stop(0);
    }

    private PackTrust buildTrust(String pinnedCertHex) throws Exception {
        return new PackTrust(
                "pixelmc-test",
                "PixelMC Test",
                Base64.getEncoder().encodeToString(publisherKey.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(serverKey.getPublic().getEncoded()),
                URI.create("https://objects.example.test/pixelmc-test/active.json"),
                gatewayUri,
                pinnedCertHex,
                Set.of("objects.example.test"),
                Set.of("mods", "config"),
                1L);
    }

    private byte[] buildSignedManifest(String releaseId, long sequence, Map<String, byte[]> files)
            throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", 1);
        payload.addProperty("packId", "pixelmc-test");
        payload.addProperty("releaseId", releaseId);
        payload.addProperty("sequence", sequence);
        payload.addProperty("channel", "stable");
        payload.addProperty("consentProtocolVersion", 1);
        payload.addProperty("managementScopeVersion", "scope-1");
        payload.addProperty("createdAt", "2026-11-26T00:00:00Z");
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            JsonObject f = new JsonObject();
            f.addProperty("path", e.getKey());
            f.addProperty("size", e.getValue().length);
            f.addProperty("sha256", ManifestVerifier.sha256(e.getValue()));
            f.addProperty(
                    "url",
                    "https://objects.example.test/pixelmc-test/releases/"
                            + releaseId
                            + "/files/"
                            + e.getKey());
            arr.add(f);
        }
        payload.add("files", arr);
        return signEnvelope(payload, publisherKey, false);
    }

    private byte[] signEnvelope(JsonObject payload, KeyPair key, boolean tamper) throws Exception {
        byte[] raw = StrictJson.bytes(payload);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(key.getPrivate());
        sig.update(raw);
        byte[] sigBytes = sig.sign();
        if (tamper) sigBytes[0] ^= 0x55;
        JsonObject env = new JsonObject();
        env.addProperty("schema", 1);
        env.addProperty("packId", "pixelmc-test");
        env.addProperty("payload", Base64.getEncoder().encodeToString(raw));
        env.addProperty("signature", Base64.getEncoder().encodeToString(sigBytes));
        return StrictJson.bytes(env);
    }

    private void handleGateway(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonObject req = StrictJson.object(body);
            String action = StrictJson.string(req, "action");
            JsonObject resp = new JsonObject();
            resp.addProperty("schema", 1);
            resp.addProperty("packId", "pixelmc-test");
            resp.addProperty("channel", "stable");
            if ("check".equals(action)) {
                checkCalls.incrementAndGet();
                long clientSeq =
                        req.has("installedSequence")
                                ? req.get("installedSequence").getAsLong()
                                : 0L;
                String clientDigest =
                        req.has("installedDigest") ? req.get("installedDigest").getAsString() : "";
                boolean upToDate =
                        clientSeq == verifiedManifest.sequence()
                                && verifiedManifest.payloadDigest().equals(clientDigest);
                resp.addProperty("type", "prelaunch_check");
                resp.addProperty("upToDate", upToDate);
                resp.addProperty("releaseId", verifiedManifest.releaseId());
                resp.addProperty("sequence", verifiedManifest.sequence());
                resp.addProperty("payloadDigest", verifiedManifest.payloadDigest());
                if (!upToDate) {
                    String canonical =
                            "https://objects.example.test/pixelmc-test/releases/r1/manifest.json";
                    resp.addProperty("manifestCanonicalUrl", canonical);
                    resp.addProperty("manifestUrl", canonical + "?x-oss-signature=fixture");
                    resp.addProperty("expiresAt", System.currentTimeMillis() / 1000L + 600L);
                }
            } else if ("signFiles".equals(action)) {
                signFilesCalls.incrementAndGet();
                resp.addProperty("type", "prelaunch_files");
                resp.addProperty("releaseId", verifiedManifest.releaseId());
                resp.addProperty("sequence", verifiedManifest.sequence());
                resp.addProperty("expiresAt", System.currentTimeMillis() / 1000L + 600L);
                JsonArray paths = req.getAsJsonArray("paths");
                JsonObject signed = new JsonObject();
                for (JsonElement el : paths) {
                    String p = el.getAsString();
                    String canonical =
                            "https://objects.example.test/pixelmc-test/releases/r1/files/" + p;
                    JsonObject item = new JsonObject();
                    item.addProperty("canonicalUrl", canonical);
                    item.addProperty("url", canonical + "?x-oss-signature=fixture");
                    signed.add(p, item);
                }
                resp.add("files", signed);
            } else {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            byte[] envelope = signEnvelope(resp, serverKey, tamperGatewaySig.get());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, envelope.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(envelope);
            }
        } catch (Exception e) {
            byte[] err = e.getMessage().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, err.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(err);
            }
        } finally {
            exchange.close();
        }
    }

    private PrelaunchSyncCoordinator.Downloader fakeDownloader(AtomicInteger fileDownloads) {
        return new PrelaunchSyncCoordinator.Downloader() {
            @Override
            public byte[] manifest(PackTrust trust, URI canonical, URI transport)
                    throws IOException {
                trust.validateTransportUrl(canonical, transport);
                return manifestEnvelopeBytes;
            }

            @Override
            public void file(
                    PackTrust trust,
                    StagedInstaller.Stage stage,
                    ManifestFile file,
                    URI transport,
                    OssDownloads.ProgressListener listener)
                    throws IOException {
                trust.validateTransportUrl(file.url(), transport);
                byte[] raw = fileContents.get(file.path());
                assertNotNull(raw, "Missing fixture content for " + file.path());
                stage.accept(file, new ByteArrayInputStream(raw));
                listener.onBytes(raw.length);
                fileDownloads.incrementAndGet();
            }
        };
    }

    private static final class RecordingUi implements PrelaunchSyncCoordinator.PrelaunchUi {
        final boolean approveConsent;
        final boolean skipAfterConsent;
        final AtomicInteger consentPrompts = new AtomicInteger();
        final AtomicInteger snapshots = new AtomicInteger();
        final List<PrelaunchSyncCoordinator.Phase> phases = new ArrayList<>();
        boolean closed;

        RecordingUi(boolean approveConsent, boolean skipAfterConsent) {
            this.approveConsent = approveConsent;
            this.skipAfterConsent = skipAfterConsent;
        }

        @Override
        public boolean promptConsent(
                PackTrust trust, VerifiedManifest manifest, ConsentCountdown countdown) {
            consentPrompts.incrementAndGet();
            return approveConsent;
        }

        @Override
        public void onSnapshot(PrelaunchSyncCoordinator.UiSnapshot snapshot) {
            snapshots.incrementAndGet();
            phases.add(snapshot.phase());
        }

        @Override
        public boolean isSkipped() {
            return skipAfterConsent;
        }

        @Override
        public void waitUntilClosed() {}

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void firstLaunchConsentsDownloadsAndAppliesImmediatelyThenSecondLaunchSkipsUi()
            throws Exception {
        PackTrust trust = buildTrust(certSha256);
        TrustStore store = new TrustStore(List.of(trust));
        ConsentStore consents = new ConsentStore(gameRoot);
        AtomicInteger downloads = new AtomicInteger();

        PrelaunchSyncCoordinator coordinator =
                new PrelaunchSyncCoordinator(
                        new PrelaunchGatewayClient(), fakeDownloader(downloads));

        RecordingUi firstUi = new RecordingUi(true, false);
        PrelaunchSyncCoordinator.Outcome first =
                coordinator.run(gameRoot, store, consents, firstUi);

        assertTrue(first.appliedNow(), "Expected immediate apply on first launch: " + first);
        assertEquals(1, firstUi.consentPrompts.get());
        assertTrue(firstUi.closed);
        assertEquals(2, downloads.get());
        assertEquals(1, signFilesCalls.get());
        assertEquals(
                "sample-jar-bytes-v1", Files.readString(gameRoot.resolve("mods/sample-mod.jar")));
        assertEquals("enabled = true\n", Files.readString(gameRoot.resolve("config/sample.toml")));
        assertTrue(StagedInstaller.matchesInstalled(gameRoot, verifiedManifest));
        assertTrue(
                consents.permits(verifiedManifest, UUID.randomUUID()),
                "Instance grant should also satisfy in-game account consent check");

        // Second launch: already up to date -> zero UI, zero downloads, zero signFiles
        RecordingUi secondUi = new RecordingUi(true, false);
        PrelaunchSyncCoordinator.Outcome second =
                coordinator.run(gameRoot, store, consents, secondUi);
        assertFalse(second.appliedNow());
        assertFalse(second.offlineOrUnavailable());
        assertEquals(0, secondUi.consentPrompts.get());
        assertEquals(0, secondUi.snapshots.get());
        assertEquals(2, downloads.get());
        assertEquals(1, signFilesCalls.get());
    }

    @Test
    void wrongPinnedCertOrTamperedSignatureFailsClosedWithoutTouchingMods() throws Exception {
        String wrongCert = "0".repeat(64);
        TrustStore badCertStore = new TrustStore(List.of(buildTrust(wrongCert)));
        ConsentStore consents = new ConsentStore(gameRoot);
        AtomicInteger downloads = new AtomicInteger();
        PrelaunchSyncCoordinator coordinator =
                new PrelaunchSyncCoordinator(
                        new PrelaunchGatewayClient(), fakeDownloader(downloads));

        RecordingUi ui1 = new RecordingUi(true, false);
        PrelaunchSyncCoordinator.Outcome out1 =
                coordinator.run(gameRoot, badCertStore, consents, ui1);
        assertTrue(out1.offlineOrUnavailable());
        assertEquals(0, ui1.consentPrompts.get());
        assertFalse(Files.exists(gameRoot.resolve("mods/sample-mod.jar")));

        // Valid cert but tampered Ed25519 signature
        tamperGatewaySig.set(true);
        TrustStore validCertStore = new TrustStore(List.of(buildTrust(certSha256)));
        RecordingUi ui2 = new RecordingUi(true, false);
        PrelaunchSyncCoordinator.Outcome out2 =
                coordinator.run(gameRoot, validCertStore, consents, ui2);
        assertTrue(out2.offlineOrUnavailable());
        assertEquals(0, ui2.consentPrompts.get());
        assertFalse(Files.exists(gameRoot.resolve("mods/sample-mod.jar")));
    }

    @Test
    void skipDuringDownloadAbortsCleanlyWithoutModifyingMods() throws Exception {
        TrustStore store = new TrustStore(List.of(buildTrust(certSha256)));
        ConsentStore consents = new ConsentStore(gameRoot);
        AtomicInteger downloads = new AtomicInteger();
        PrelaunchSyncCoordinator coordinator =
                new PrelaunchSyncCoordinator(
                        new PrelaunchGatewayClient(), fakeDownloader(downloads));

        RecordingUi skipUi = new RecordingUi(true, true);
        PrelaunchSyncCoordinator.Outcome out = coordinator.run(gameRoot, store, consents, skipUi);
        assertTrue(out.skipped());
        assertFalse(out.appliedNow());
        assertEquals(0, downloads.get());
        assertFalse(Files.exists(gameRoot.resolve("mods/sample-mod.jar")));
    }
}
