package pl.skidam.automodpack_core.oss;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.security.*;
import java.time.Instant;
import java.util.*;

public final class ManifestVerifier {
    public static final long MAX_FILE_SIZE = 4L * 1024 * 1024 * 1024;
    public static final long MAX_TOTAL_SIZE = 16L * 1024 * 1024 * 1024;
    public static final int MAX_FILES = 10000;
    private ManifestVerifier() {}
    static String token(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) throw new IOException("Invalid identity/version token");
        return value;
    }
    static byte[] verifyPayload(JsonObject envelope, PublicKey publicKey) throws IOException {
        try {
            byte[] payload = Base64.getDecoder().decode(StrictJson.string(envelope, "payload"));
            byte[] signature = Base64.getDecoder().decode(StrictJson.string(envelope, "signature"));
            if (payload.length > StrictJson.MAX_BYTES || signature.length != 64) throw new IOException("Invalid signature envelope size");
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(publicKey); verifier.update(payload);
            if (!verifier.verify(signature)) throw new IOException("Manifest signature is not trusted");
            return payload;
        } catch (GeneralSecurityException | IllegalArgumentException ex) { throw new IOException("Invalid signature envelope", ex); }
    }
    public static VerifiedManifest verify(byte[] envelope, PackTrust trust) throws IOException {
        var outer = StrictJson.object(envelope);
        if (StrictJson.number(outer, "schema") != 1) throw new IOException("Unsupported signature envelope");
        byte[] payload = verifyPayload(outer, trust.publicKey()); var root = StrictJson.object(payload);
        if (StrictJson.number(root,"schema") != 1 || !trust.packId().equals(StrictJson.string(root,"packId"))) throw new IOException("Wrong pack/schema");
        long sequence = StrictJson.number(root,"sequence"), protocol = StrictJson.number(root,"consentProtocolVersion");
        if (sequence < trust.minimumSequence() || protocol < 1 || protocol > Integer.MAX_VALUE) throw new IOException("Release is below trusted minimum or invalid protocol");
        String release = token(StrictJson.string(root,"releaseId")), scope = token(StrictJson.string(root,"managementScopeVersion"));
        try { Instant.parse(StrictJson.string(root,"createdAt")); } catch (RuntimeException ex) { throw new IOException("Invalid creation timestamp", ex); }
        var files = new ArrayList<ManifestFile>(); var seen = new HashSet<String>(); long total = 0;
        var array = StrictJson.array(root,"files"); if (array.isEmpty() || array.size() > MAX_FILES) throw new IOException("Invalid file count");
        try {
            for (var element : array) {
                var item = element.getAsJsonObject(); String path = StrictJson.string(item,"path"); SafePaths.validateRelative(path, trust.allowedRoots());
                if (!seen.add(path.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate/case-colliding managed path");
                long size = StrictJson.number(item,"size"); String hash = StrictJson.string(item,"sha256");
                if (size < 0 || size > MAX_FILE_SIZE || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid file size/hash");
                total = Math.addExact(total, size); if (total > MAX_TOTAL_SIZE) throw new IOException("Release size limit exceeded");
                URI url = URI.create(StrictJson.string(item,"url")); trust.validateUrl(url);
                files.add(new ManifestFile(path, size, hash, url));
            }
        } catch (RuntimeException ex) { throw new IOException("Invalid manifest file", ex); }
        return new VerifiedManifest(trust, release, sequence, (int)protocol, scope, files, envelope, sha256(payload));
    }
    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
}
