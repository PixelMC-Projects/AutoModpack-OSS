package pl.skidam.automodpack_core.oss.server;

import com.google.gson.JsonParser;
import pl.skidam.automodpack_core.oss.PackTrust;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit local activation, read once at startup; a remote active pointer cannot change this pin. */
public record ActiveReleasePin(String releaseId, long sequence, String manifestSha256, URI manifestUrl) {
    public static ActiveReleasePin load(Path path, PackTrust trust) throws Exception {
        if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.size(path) > 16384) throw new SecurityException("Missing external active release pin");
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        String release = json.get("releaseId").getAsString(), hash = json.get("manifestSha256").getAsString();
        long sequence = json.get("sequence").getAsLong();
        URI url = URI.create(json.get("manifestUrl").getAsString());
        if (json.get("schema").getAsInt() != 1 || !trust.packId().equals(json.get("packId").getAsString()) || !release.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}") || sequence < 1 || !hash.matches("[0-9a-f]{64}"))
            throw new SecurityException("Invalid external active release pin");
        trust.validateManifestUrl(url,release);
        return new ActiveReleasePin(release,sequence,hash,url);
    }
}
