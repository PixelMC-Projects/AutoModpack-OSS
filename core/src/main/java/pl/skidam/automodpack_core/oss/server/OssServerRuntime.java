package pl.skidam.automodpack_core.oss.server;

import com.google.gson.*;
import pl.skidam.automodpack_core.oss.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

/** Metadata and short-lived grants only. File bytes are never proxied by this process. */
public final class OssServerRuntime implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static volatile OssServerRuntime instance;
    private final OssServerConfig config;
    private final PackTrust trust;
    private final ActiveReleasePin pin;
    private final ActiveReleaseBinding binding;
    private final PrivateKey identity;
    private final OssUrlSigner signer;
    private final DownloadAuthorizer authorizer;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r,"AutoModpack-OSS-active"); t.setDaemon(true); return t; });
    private final AtomicBoolean disabled = new AtomicBoolean();
    private volatile VerifiedManifest active;
    private volatile long lastVerified;
    private long highestSequence;
    private String highestRelease;

    public static OssServerRuntime get() { return instance; }
    public static synchronized void startFromEnvironment() {
        if (instance != null) return;
        String location = System.getenv("AUTOMODPACK_OSS_SERVER_CONFIG");
        if (location == null || location.isBlank()) { LOGGER.info("OSS synchronization disabled: external configuration is absent"); return; }
        try { instance = new OssServerRuntime(OssServerConfig.load(Path.of(location))); }
        catch (Exception e) { LOGGER.error("OSS synchronization disabled: configuration, trust or active release validation failed ({})", e.getClass().getSimpleName()); }
    }
    public static synchronized void stop() { if (instance != null) { instance.close(); instance = null; } }

    private OssServerRuntime(OssServerConfig config) throws Exception {
        this.config = config;
        trust = TrustStore.load(Path.of(config.trustStore)).requirePack(config.packId);
        binding = new ActiveReleaseBinding(Path.of(config.activeReleaseFile),trust);
        pin = binding.pin();
        signer = new OssUrlSigner(config.bucket, config.region, requiredEnvironment(config.accessKeyIdEnvironment), requiredEnvironment(config.accessKeySecretEnvironment), System.getenv(config.securityTokenEnvironment));
        byte[] encodedKey = Base64.getDecoder().decode(requiredEnvironment(config.serverIdentityKeyEnvironment));
        identity = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
        // Reject a mismatched identity key before any listener is exposed.
        byte[] probe = "AutoModpack-OSS-identity-key-check".getBytes(StandardCharsets.UTF_8);
        Signature check = Signature.getInstance("Ed25519"); check.initSign(identity); check.update(probe); byte[] signed = check.sign();
        check.initVerify(trust.serverIdentityPublicKey()); check.update(probe);
        if (!check.verify(signed)) throw new SecurityException("Server identity differs from local trust");
        authorizer = new DownloadAuthorizer(signer, config.sessionLifetimeSeconds, config.urlLifetimeSeconds, Clock.systemUTC());
        if (!Files.notExists(Path.of(config.killSwitchFile))) throw new SecurityException("Kill switch is active");
        refresh();
        if (config.activeRefreshSeconds > 0) scheduler.scheduleWithFixedDelay(this::refreshSafely, config.activeRefreshSeconds, config.activeRefreshSeconds, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::checkKillSwitch, 1, 1, TimeUnit.SECONDS);
        LOGGER.info("OSS synchronization enabled with verified active release; legacy file hosting is disabled");
    }

    public PackTrust trust() { return trust; }
    public boolean available() { checkKillSwitch(); return !disabled.get() && active != null && (config.activeRefreshSeconds == 0 || Instant.now().getEpochSecond() - lastVerified <= config.activeRefreshSeconds * 2L); }
    public VerifiedManifest active() { if (!available()) throw new SecurityException("No fresh active release"); return active; }
    public DownloadAuthorizer.Grant issue(UUID authenticatedAccount, String ip) { return authorizer.issue(authenticatedAccount, ip, active()); }
    public void revoke(String token) { authorizer.revoke(token); }
    public boolean accept(String token) { return available() && authorizer.accept(token); }
    public boolean allowMetadata(String token, String ip) { return available() && authorizer.allowMetadata(token,ip); }
    public int sessionLifetimeSeconds() { return config.sessionLifetimeSeconds; }
    public byte[] downloadMessage(String token, String ip, String nonce, String releaseId, String path, int queryId) throws Exception {
        VerifiedManifest manifest = active();
        DownloadAuthorizer.SignedFile file = authorizer.authorize(token, ip, releaseId, path, manifest);
        trust.validateTransportUrl(file.canonicalUrl(), file.url());
        JsonObject payload = baseDownloadMessage(nonce, "file", queryId);
        payload.addProperty("path", path); payload.addProperty("canonicalUrl", file.canonicalUrl().toASCIIString());
        payload.addProperty("url", file.url().toASCIIString()); payload.addProperty("expiresAt", file.expiresAt());
        return signEnvelope(payload);
    }
    public byte[] downloadReady(String nonce, int queryId, String type, long expiresAt) throws Exception {
        JsonObject payload = baseDownloadMessage(nonce, type, queryId);
        payload.addProperty("sessionExpiresAt", expiresAt);
        return signEnvelope(payload);
    }
    private JsonObject baseDownloadMessage(String nonce, String type, int queryId) {
        VerifiedManifest manifest = active();
        JsonObject payload = new JsonObject(); payload.addProperty("schema",1); payload.addProperty("type",type);
        payload.addProperty("clientNonce",nonce); payload.addProperty("packId",trust.packId()); payload.addProperty("queryId",queryId);
        payload.addProperty("releaseId",manifest.releaseId()); payload.addProperty("sequence",manifest.sequence());
        return payload;
    }

    public byte[] offer(UUID account, String name, String nonce, DownloadAuthorizer.Grant grant, int queryId) throws Exception {
        if (!nonce.matches("[A-Za-z0-9_-]{43}")) throw new SecurityException("Invalid client nonce");
        VerifiedManifest manifest = active();
        long now = Instant.now().getEpochSecond(), urlExpires = now + config.urlLifetimeSeconds;
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "offer");
        payload.addProperty("schema", 1); payload.addProperty("packId", config.packId); payload.addProperty("queryId", queryId);
        payload.addProperty("manifestUrl", pin.manifestUrl().toASCIIString());
        payload.addProperty("manifestSha256", pin.manifestSha256());
        payload.addProperty("manifestTransportUrl", signer.sign(pin.manifestUrl(), Instant.ofEpochSecond(urlExpires)).toASCIIString());
        payload.addProperty("playerUuid", account.toString()); payload.addProperty("playerName", name);
        payload.addProperty("onlineAuthenticated", true); payload.addProperty("releaseId", manifest.releaseId()); payload.addProperty("sequence", manifest.sequence());
        payload.addProperty("authorizationTransport", "minecraft-login");
        payload.addProperty("sessionExpiresAt", grant.expiresAt()); payload.addProperty("urlExpiresAt", urlExpires);
        payload.addProperty("issuedAt", now); payload.addProperty("queryExpiresAt", now + 180); payload.addProperty("clientNonce", nonce);
        return signEnvelope(payload);
    }
    private byte[] signEnvelope(JsonObject payload) throws Exception {
        byte[] bytes = JSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 48 * 1024) throw new SecurityException("Login metadata exceeds bound");
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(identity); signature.update(bytes);
        JsonObject envelope = new JsonObject(); envelope.addProperty("schema", 1); envelope.addProperty("packId", config.packId);
        envelope.addProperty("payload", Base64.getEncoder().encodeToString(bytes)); envelope.addProperty("signature", Base64.getEncoder().encodeToString(signature.sign()));
        byte[] encoded = JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 68 * 1024) throw new SecurityException("Login envelope exceeds bound");
        return encoded;
    }

    private synchronized void refresh() throws Exception {
        URI url = signer.sign(pin.manifestUrl(), Instant.now().plusSeconds(config.urlLifetimeSeconds));
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(8)).GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, ignored -> new BoundedManifestBody());
        HttpResponse<byte[]> response;
        try { response = pending.get(8,TimeUnit.SECONDS); }
        catch (Exception e) { pending.cancel(true); throw e; }
        if (response.statusCode() != 200) throw new SecurityException("Active release unavailable");
        byte[] bytes = response.body();
        VerifiedManifest verified = binding.verifyManifest(bytes);
        if (verified.sequence() < highestSequence || verified.sequence() == highestSequence && highestRelease != null && !verified.releaseId().equals(highestRelease))
            throw new SecurityException("Active release rollback or sequence reuse");
        for (ManifestFile file : verified.files()) signer.sign(file.url(), Instant.now().plusSeconds(120)); // validates OSS bucket/path contract
        highestSequence = verified.sequence(); highestRelease = verified.releaseId();
        authorizer.activate(verified); active = verified; lastVerified = Instant.now().getEpochSecond();
    }
    private void refreshSafely() {
        if (disabled.get()) return;
        try { refresh(); }
        catch (Exception e) { active = null; LOGGER.warn("OSS active release refresh failed; new grants paused ({})", e.getClass().getSimpleName()); }
    }

    /** Bounds memory before allocation and applies the outer timeout to the complete body, not just headers. */
    private static final class BoundedManifestBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value;value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer:buffers) {
                if (buffer.remaining() > 8 * 1024 * 1024 - bytes.size()) {
                    subscription.cancel();result.completeExceptionally(new SecurityException("Active manifest exceeds limit"));return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private void checkKillSwitch() {
        if (!Files.notExists(Path.of(config.killSwitchFile)) && disabled.compareAndSet(false, true)) {
            authorizer.disable(); active = null;
            LOGGER.warn("OSS synchronization kill switch enabled; sessions and future URL issuance revoked");
            notifyExternalRevoker();
        }
    }
    private void notifyExternalRevoker() {
        if (config.externalRevocationEndpoint == null) return;
        JsonObject event = new JsonObject(); event.addProperty("event", "automodpack-oss-kill-switch"); event.addProperty("packId", config.packId); event.addProperty("time", Instant.now().toString());
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.externalRevocationEndpoint)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + requiredEnvironment(config.externalRevocationTokenEnvironment)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(event))).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null || response.statusCode() / 100 != 2) LOGGER.error("Optional external revocation hook failed; OSS administrator action is required");
            else LOGGER.info("External revocation hook acknowledged; actual OSS revocation is the external administrator service's responsibility");
        });
    }
    private static String requiredEnvironment(String name) { String value = name == null ? null : System.getenv(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing external secret"); return value; }
    @Override public void close() { disabled.set(true); authorizer.disable(); scheduler.shutdownNow(); }
}
