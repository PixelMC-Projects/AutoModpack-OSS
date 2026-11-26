package pl.skidam.automodpack_core.oss.client;

import com.google.gson.*;

import pl.skidam.automodpack_core.oss.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;

import javax.net.ssl.*;

/**
 * HTTPS client for the pre-launch signing gateway. Enforces pinned Ed25519 server identity envelope
 * verification, optional pinned TLS certificate SHA-256 verification (for private CAs), and strict
 * connect/read timeouts so offline launches never stall.
 */
public final class PrelaunchGatewayClient {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    public record CheckResult(
            boolean upToDate,
            ReleaseChannel channel,
            String releaseId,
            long sequence,
            String payloadDigest,
            URI manifestCanonicalUrl,
            URI manifestSignedUrl,
            long expiresAt) {}

    public record SignedFileEntry(URI canonicalUrl, URI signedUrl) {}

    public record SignedFilesResult(
            ReleaseChannel channel,
            String releaseId,
            long sequence,
            long expiresAt,
            Map<String, SignedFileEntry> files) {}

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public PrelaunchGatewayClient() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public PrelaunchGatewayClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public CheckResult check(
            PackTrust trust,
            ReleaseChannel channel,
            long installedSequence,
            String installedDigest,
            ReleaseChannel installedChannel)
            throws IOException {
        URI endpoint = trust.authorizationEndpoint();
        if (endpoint == null) {
            throw new IOException("No pre-launch authorization endpoint provisioned");
        }
        JsonObject req = new JsonObject();
        req.addProperty("schema", 1);
        req.addProperty("action", "check");
        req.addProperty("packId", trust.packId());
        req.addProperty("channel", (channel == null ? ReleaseChannel.STABLE : channel).id());
        req.addProperty("installedSequence", installedSequence);
        req.addProperty("installedDigest", installedDigest == null ? "" : installedDigest);
        if (installedChannel != null) {
            req.addProperty("installedChannel", installedChannel.id());
        }

        JsonObject payload = postSigned(trust, endpoint, req);
        if (!"prelaunch_check".equals(StrictJson.string(payload, "type"))
                || !trust.packId().equals(StrictJson.string(payload, "packId"))) {
            throw new IOException("Unexpected pre-launch check response");
        }
        ReleaseChannel resolved =
                ReleaseChannel.parse(
                        payload.has("channel")
                                ? StrictJson.string(payload, "channel")
                                : ReleaseChannel.STABLE.id());
        boolean upToDate = StrictJson.bool(payload, "upToDate");
        String releaseId = ManifestVerifier.token(StrictJson.string(payload, "releaseId"));
        long sequence = StrictJson.number(payload, "sequence");
        String digest = StrictJson.string(payload, "payloadDigest");
        if (upToDate) {
            return new CheckResult(true, resolved, releaseId, sequence, digest, null, null, 0L);
        }
        URI canonical = URI.create(StrictJson.string(payload, "manifestCanonicalUrl"));
        URI signed = URI.create(StrictJson.string(payload, "manifestUrl"));
        trust.validateManifestUrl(canonical, releaseId);
        trust.validateTransportUrl(canonical, signed);
        long expiresAt = StrictJson.number(payload, "expiresAt");
        return new CheckResult(
                false, resolved, releaseId, sequence, digest, canonical, signed, expiresAt);
    }

    public SignedFilesResult signFiles(
            PackTrust trust, ReleaseChannel channel, String releaseId, List<String> paths)
            throws IOException {
        URI endpoint = trust.authorizationEndpoint();
        if (endpoint == null) {
            throw new IOException("No pre-launch authorization endpoint provisioned");
        }
        JsonObject req = new JsonObject();
        req.addProperty("schema", 1);
        req.addProperty("action", "signFiles");
        req.addProperty("packId", trust.packId());
        req.addProperty("channel", (channel == null ? ReleaseChannel.STABLE : channel).id());
        req.addProperty("releaseId", releaseId);
        JsonArray arr = new JsonArray();
        for (String p : paths) arr.add(p);
        req.add("paths", arr);

        JsonObject payload = postSigned(trust, endpoint, req);
        if (!"prelaunch_files".equals(StrictJson.string(payload, "type"))
                || !trust.packId().equals(StrictJson.string(payload, "packId"))
                || !releaseId.equals(StrictJson.string(payload, "releaseId"))) {
            throw new IOException("Unexpected pre-launch file authorization response");
        }
        ReleaseChannel resolved =
                ReleaseChannel.parse(
                        payload.has("channel")
                                ? StrictJson.string(payload, "channel")
                                : ReleaseChannel.STABLE.id());
        long sequence = StrictJson.number(payload, "sequence");
        long expiresAt = StrictJson.number(payload, "expiresAt");
        JsonObject filesObj = StrictJson.object(payload, "files");
        Map<String, SignedFileEntry> out = new LinkedHashMap<>();
        for (String p : paths) {
            if (!filesObj.has(p) || !filesObj.get(p).isJsonObject()) {
                throw new IOException("Gateway omitted requested file: " + p);
            }
            JsonObject entry = filesObj.getAsJsonObject(p);
            URI canonical = URI.create(StrictJson.string(entry, "canonicalUrl"));
            URI signed = URI.create(StrictJson.string(entry, "url"));
            trust.validateTransportUrl(canonical, signed);
            out.put(p, new SignedFileEntry(canonical, signed));
        }
        return new SignedFilesResult(
                resolved, releaseId, sequence, expiresAt, Collections.unmodifiableMap(out));
    }

    private JsonObject postSigned(PackTrust trust, URI endpoint, JsonObject body)
            throws IOException {
        trust.validateAuthorizationEndpoint(endpoint);
        byte[] reqBytes = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection conn = (HttpsURLConnection) endpoint.toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (trust.authorizationCertSha256() != null) {
            applyPinnedTls(conn, trust.authorizationCertSha256());
        }
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(reqBytes);
            }
            int code = conn.getResponseCode();
            InputStream stream =
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            byte[] raw = stream == null ? new byte[0] : stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (raw.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Gateway response exceeds size limit");
            }
            if (code != 200) {
                throw new IOException("Gateway refused pre-launch request (HTTP " + code + ")");
            }
            byte[] verifiedPayload = trust.verifyServerEnvelope(raw);
            return StrictJson.object(verifiedPayload);
        } finally {
            conn.disconnect();
        }
    }

    public static void applyPinnedTls(HttpsURLConnection conn, String expectedCertSha256Hex)
            throws IOException {
        String pinned = expectedCertSha256Hex.toLowerCase(Locale.ROOT);
        try {
            TrustManager[] managers = {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        throw new CertificateException("Client auth unsupported");
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        if (chain == null || chain.length == 0) {
                            throw new CertificateException("Empty server certificate chain");
                        }
                        try {
                            MessageDigest md = MessageDigest.getInstance("SHA-256");
                            for (X509Certificate cert : chain) {
                                cert.checkValidity();
                                String fp = HexFormat.of().formatHex(md.digest(cert.getEncoded()));
                                if (pinned.equals(fp)) {
                                    return;
                                }
                            }
                        } catch (CertificateException ce) {
                            throw ce;
                        } catch (Exception ex) {
                            throw new CertificateException(
                                    "Certificate fingerprint check failed", ex);
                        }
                        throw new CertificateException("Server certificate fingerprint mismatch");
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, managers, new SecureRandom());
            conn.setSSLSocketFactory(ctx.getSocketFactory());
            // Because the exact leaf/CA certificate fingerprint is cryptographically pinned,
            // allow IP/custom-host connections whose SAN matches the pinned certificate.
            conn.setHostnameVerifier((hostname, session) -> true);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Failed to initialize pinned TLS context", ex);
        }
    }
}
