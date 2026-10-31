package pl.skidam.automodpack_core.oss;

import java.io.IOException;
import java.net.URI;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** A locally provisioned trust profile. Server hints cannot extend this profile. */
public final class PackTrust {
    private final String packId, identity;
    private final PublicKey publicKey, serverKey;
    private final URI manifestUrl, authorizationEndpoint;
    private final Set<String> allowedHosts, allowedRoots;
    private final long minimumSequence;
    public PackTrust(String packId, String identity, String publicKey, String serverIdentityPublicKey,
                     URI manifestUrl, URI authorizationEndpoint, Set<String> allowedHosts,
                     Set<String> allowedRoots, long minimumSequence) throws IOException {
        this.packId = ManifestVerifier.token(packId); this.identity = identity;
        if (identity == null || identity.isBlank() || identity.length() > 160 || identity.chars().anyMatch(c -> c < 32)) throw new IOException("Invalid identity");
        this.publicKey = parseKey(publicKey); this.serverKey = parseKey(serverIdentityPublicKey);
        if(MessageDigest.isEqual(this.publicKey.getEncoded(),this.serverKey.getEncoded())) throw new IOException("Publisher and server identity keys must be independent");
        this.allowedHosts = new HashSet<>();
        for (String host : allowedHosts) {
            if (host == null || !host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?") || host.contains("..")) throw new IOException("Invalid OSS host allowlist");
            this.allowedHosts.add(host);
        }
        if (this.allowedHosts.isEmpty() || minimumSequence < 1) throw new IOException("Trust requires hosts and minimumSequence");
        this.allowedRoots = Set.copyOf(allowedRoots);
        if (this.allowedRoots.isEmpty() || !SafePaths.MANAGED_ROOTS.containsAll(this.allowedRoots)) throw new IOException("Invalid managed roots");
        this.minimumSequence = minimumSequence;
        this.manifestUrl = manifestUrl; validateUrl(manifestUrl);
        if (authorizationEndpoint != null) validateHttps(authorizationEndpoint, false); this.authorizationEndpoint = authorizationEndpoint;
    }
    public PackTrust(String packId, String identity, String publicKey, String serverIdentityPublicKey,
                     URI manifestUrl, Set<String> allowedHosts, Set<String> allowedRoots, long minimumSequence) throws IOException {
        this(packId,identity,publicKey,serverIdentityPublicKey,manifestUrl,null,allowedHosts,allowedRoots,minimumSequence);
    }
    private static PublicKey parseKey(String encoded) throws IOException {
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded))); }
        catch (GeneralSecurityException | IllegalArgumentException ex) { throw new IOException("Invalid provisioned Ed25519 key", ex); }
    }
    static void validateHttps(URI uri, boolean allowQuery) throws IOException {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null ||
            uri.getFragment() != null || (!allowQuery && uri.getRawQuery() != null) || (uri.getPort() != -1 && uri.getPort() != 443) ||
            uri.getRawPath() == null || !uri.getRawPath().startsWith("/") || !uri.normalize().getRawPath().equals(uri.getRawPath()) ||
            uri.toString().indexOf('\\') >= 0 || uri.toString().chars().anyMatch(c -> c < 33)) throw new IOException("Untrusted HTTPS URL");
    }
    public void validateUrl(URI uri) throws IOException {
        validateHttps(uri, false);
        if (!allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw new IOException("OSS host is not trusted");
    }
    public void validateTransportUrl(URI canonical, URI transport) throws IOException {
        validateUrl(canonical); validateHttps(transport, true);
        if (!sameEndpoint(canonical, transport)) throw new IOException("Transport URL does not match signed OSS object");
    }
    public void validateManifestUrl(URI uri, String releaseId) throws IOException {
        validateUrl(uri); ManifestVerifier.token(releaseId);
        URI expected=manifestUrl.resolve("releases/"+releaseId+"/manifest.json");
        if(!sameEndpoint(expected,uri)) throw new IOException("Manifest does not match the pinned immutable release location");
    }
    private static boolean sameEndpoint(URI first, URI second) {
        return first.getHost().equalsIgnoreCase(second.getHost()) && first.getRawPath().equals(second.getRawPath());
    }
    public void validateAuthorizationEndpoint(URI hint) throws IOException {
        if (authorizationEndpoint == null) throw new IOException("No HTTP authorization endpoint is provisioned");
        validateHttps(hint, false);
        if (!sameEndpoint(authorizationEndpoint, hint)) throw new IOException("Authorization endpoint is not provisioned");
    }
    public byte[] verifyServerEnvelope(byte[] envelope) throws IOException {
        var object = StrictJson.object(envelope);
        if (StrictJson.number(object, "schema") != 1 || !packId.equals(StrictJson.string(object, "packId"))) throw new IOException("Invalid server identity envelope");
        return ManifestVerifier.verifyPayload(object, serverKey);
    }
    public String packId() { return packId; }
    public String identity() { return identity; }
    public PublicKey publicKey() { return publicKey; }
    public PublicKey serverIdentityPublicKey() { return serverKey; }
    public String authorityId() { return ManifestVerifier.sha256(publicKey.getEncoded()); }
    public String serverIdentityId() { return ManifestVerifier.sha256(serverKey.getEncoded()); }
    public URI manifestUrl() { return manifestUrl; }
    public URI authorizationEndpoint() { return authorizationEndpoint; }
    public Set<String> allowedHosts() { return Set.copyOf(allowedHosts); }
    public Set<String> allowedRoots() { return allowedRoots; }
    public long minimumSequence() { return minimumSequence; }
}
