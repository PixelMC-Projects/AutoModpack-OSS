package pl.skidam.automodpack_core.oss;

import java.util.List;

/** Only ManifestVerifier can construct a verified release. */
public final class VerifiedManifest {
    private final PackTrust trust;
    private final String releaseId, scope, digest;
    private final long sequence;
    private final int protocol;
    private final List<ManifestFile> files;
    private final byte[] envelope;
    VerifiedManifest(PackTrust trust, String releaseId, long sequence, int protocol, String scope,
                     List<ManifestFile> files, byte[] envelope, String digest) {
        this.trust = trust; this.releaseId = releaseId; this.sequence = sequence; this.protocol = protocol;
        this.scope = scope; this.files = List.copyOf(files); this.envelope = envelope.clone(); this.digest = digest;
    }
    public String packId() { return trust.packId(); }
    public String releaseId() { return releaseId; }
    public long sequence() { return sequence; }
    public int consentProtocolVersion() { return protocol; }
    public String managementScopeVersion() { return scope; }
    public List<ManifestFile> files() { return files; }
    public String authorityId() { return trust.authorityId(); }
    public String serverIdentityId() { return trust.serverIdentityId(); }
    public String payloadDigest() { return digest; }
    public byte[] envelopeBytes() { return envelope.clone(); }
    public PackTrust trust() { return trust; }
}
