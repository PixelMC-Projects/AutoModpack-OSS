package pl.skidam.automodpack_core.oss.server;

import pl.skidam.automodpack_core.oss.*;
import java.nio.file.Path;

/** One server lifecycle's immutable activation. It never re-reads the pin or any remote pointer. */
public final class ActiveReleaseBinding {
    private final ActiveReleasePin pin;
    private final PackTrust trust;
    public ActiveReleaseBinding(Path externalPin, PackTrust trust) throws Exception {
        this.trust=trust;this.pin=ActiveReleasePin.load(externalPin,trust);
    }
    public ActiveReleasePin pin() { return pin; }
    public VerifiedManifest verifyManifest(byte[] envelope) throws Exception {
        if (!ManifestVerifier.sha256(envelope).equals(pin.manifestSha256())) throw new SecurityException("Pinned manifest digest mismatch");
        VerifiedManifest manifest=ManifestVerifier.verify(envelope,trust);
        if (!manifest.releaseId().equals(pin.releaseId()) || manifest.sequence()!=pin.sequence()) throw new SecurityException("Pinned active release mismatch");
        return manifest;
    }
}
