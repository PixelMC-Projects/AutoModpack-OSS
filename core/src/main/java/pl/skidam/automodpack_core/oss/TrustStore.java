package pl.skidam.automodpack_core.oss;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Missing configuration intentionally trusts nobody. */
public final class TrustStore {
    private final Map<String, PackTrust> packs;
    public TrustStore(Collection<PackTrust> packs) throws IOException {
        var map = new LinkedHashMap<String, PackTrust>();
        for (var pack : packs) if (map.put(pack.packId(), pack) != null) throw new IOException("Duplicate pack identity");
        this.packs = Map.copyOf(map);
    }
    public static TrustStore load(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new TrustStore(List.of());
        SafePaths.requireRegularFile(path);
        var root = StrictJson.object(SafePaths.readBounded(path, StrictJson.MAX_BYTES));
        if (StrictJson.number(root, "schema") != 1) throw new IOException("Unsupported trust schema");
        var packs = new ArrayList<PackTrust>();
        try {
            for (var entry : StrictJson.array(root, "packs")) {
                var item = entry.getAsJsonObject(); var hosts = new HashSet<String>(); var roots = new HashSet<String>();
                for (var host : StrictJson.array(item, "allowedHosts")) hosts.add(host.getAsString());
                for (var dir : StrictJson.array(item, "allowedRoots")) roots.add(dir.getAsString());
                packs.add(new PackTrust(StrictJson.string(item,"packId"), StrictJson.string(item,"identity"),
                    StrictJson.string(item,"publicKey"), StrictJson.string(item,"serverIdentityPublicKey"),
                    URI.create(StrictJson.string(item,"manifestUrl")), item.has("authorizationEndpoint") ? URI.create(StrictJson.string(item,"authorizationEndpoint")) : null,
                    hosts, roots, StrictJson.number(item,"minimumSequence")));
            }
        } catch (RuntimeException ex) { throw new IOException("Invalid trust configuration", ex); }
        return new TrustStore(packs);
    }
    public PackTrust requirePack(String packId) throws IOException {
        PackTrust result = packs.get(packId); if (result == null) throw new IOException("Pack is not locally trusted"); return result;
    }
    public Collection<PackTrust> packs() { return packs.values(); }
}
