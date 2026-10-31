package pl.skidam.automodpack_core.oss;

import java.net.URI;

public record ManifestFile(String path, long size, String sha256, URI url) {}
