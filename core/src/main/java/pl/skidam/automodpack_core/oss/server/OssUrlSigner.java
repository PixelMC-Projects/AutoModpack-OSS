package pl.skidam.automodpack_core.oss.server;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** OSS V4 GET-only signing. No list, upload, ACL or bucket-policy operations exist here. */
public final class OssUrlSigner {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final String bucket, region, keyId, secret, securityToken, host;

    public OssUrlSigner(String bucket, String region, String keyId, String secret, String securityToken) {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") || region == null || !region.matches("[a-z0-9]+(?:-[a-z0-9]+)+")
                || keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) throw new IllegalArgumentException("Missing OSS read credentials/region");
        this.bucket = bucket; this.region = region; this.keyId = keyId; this.secret = secret;
        this.securityToken = securityToken == null ? "" : securityToken;
        this.host = bucket + ".oss-" + region + ".aliyuncs.com";
    }
    public URI sign(URI canonical, Instant expiry) { return sign(canonical, Instant.now(), expiry); }

    public URI sign(URI canonical, Instant signingTime, Instant expiry) {
        if (!"https".equals(canonical.getScheme()) || canonical.getRawQuery() != null || canonical.getFragment() != null
                || canonical.getUserInfo() != null || !host.equals(canonical.getHost()) || canonical.getPort() != -1 && canonical.getPort() != 443
                || canonical.getRawPath() == null || !canonical.getRawPath().startsWith("/") || !canonical.normalize().equals(canonical))
            throw new IllegalArgumentException("Invalid OSS object URL");
        int keyBytes = canonical.getPath().substring(1).getBytes(StandardCharsets.UTF_8).length;
        if (keyBytes == 0 || keyBytes > 1023) throw new IllegalArgumentException("OSS object key exceeds bound");
        long expires = expiry.getEpochSecond() - signingTime.getEpochSecond();
        if (expires < 1 || expires > 300) throw new IllegalArgumentException("OSS URL lifetime exceeds bound");
        String timestamp = TIMESTAMP.format(signingTime), date = timestamp.substring(0,8);
        String scope = date + "/" + region + "/oss/aliyun_v4_request";
        TreeMap<String,String> query = new TreeMap<>();
        query.put("x-oss-signature-version","OSS4-HMAC-SHA256"); query.put("x-oss-date",timestamp);
        query.put("x-oss-expires",Long.toString(expires)); query.put("x-oss-credential",keyId + "/" + scope);
        if (!securityToken.isEmpty()) query.put("x-oss-security-token",securityToken);
        String canonicalQuery = query.entrySet().stream().map(e -> encode(e.getKey(),false) + "=" + encode(e.getValue(),false)).collect(Collectors.joining("&"));
        String canonicalRequest = "GET\n" + encode("/" + bucket + canonical.getPath(),true) + "\n" + canonicalQuery + "\n\n\nUNSIGNED-PAYLOAD";
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
            String toSign = "OSS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + hash;
            byte[] signingKey = hmac(("aliyun_v4" + secret).getBytes(StandardCharsets.UTF_8),date);
            signingKey = hmac(signingKey,region); signingKey = hmac(signingKey,"oss"); signingKey = hmac(signingKey,"aliyun_v4_request");
            String signature = HexFormat.of().formatHex(hmac(signingKey,toSign));
            return URI.create(canonical.toASCIIString() + "?" + canonicalQuery + "&x-oss-signature=" + signature);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("OSS signing unavailable",e); }
    }
    private static byte[] hmac(byte[] key, String value) throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String encode(String value, boolean slashSafe) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~' || slashSafe && c == '/') out.append((char)c);
            else out.append('%').append("0123456789ABCDEF".charAt(c >>> 4)).append("0123456789ABCDEF".charAt(c & 15));
        }
        return out.toString();
    }
}
