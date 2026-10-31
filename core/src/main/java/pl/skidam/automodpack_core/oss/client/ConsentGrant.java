package pl.skidam.automodpack_core.oss.client;

import pl.skidam.automodpack_core.oss.VerifiedManifest;
import java.util.UUID;
import java.util.TreeSet;
import pl.skidam.automodpack_core.oss.ManifestVerifier;

public record ConsentGrant(String publisherIdentity, String serverIdentity, String packId,
                           int protocolVersion, String scopeVersion, String rootScope, String accountUuid) {
    public static ConsentGrant forManifest(VerifiedManifest manifest, UUID authenticatedAccount) {
        return new ConsentGrant(manifest.authorityId(), manifest.serverIdentityId(), manifest.packId(),
                manifest.consentProtocolVersion(), manifest.managementScopeVersion(), roots(manifest), authenticatedAccount.toString());
    }
    private static String roots(VerifiedManifest manifest) {
        return ManifestVerifier.sha256(String.join("\n", new TreeSet<>(manifest.trust().allowedRoots())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public boolean covers(VerifiedManifest manifest) {
        return publisherIdentity.equals(manifest.authorityId()) && serverIdentity.equals(manifest.serverIdentityId())
                && packId.equals(manifest.packId()) && protocolVersion == manifest.consentProtocolVersion()
                && scopeVersion.equals(manifest.managementScopeVersion()) && rootScope.equals(roots(manifest));
    }
}
