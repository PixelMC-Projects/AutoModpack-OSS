package pl.skidam.automodpack_core.oss.server;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetAddress;
import java.util.List;

/** All real configuration and secrets live outside the checkout and game instance. */
public final class OssServerConfig {
    public boolean enabled;
    public String packId, trustStore, activeReleaseFile, bucket, region, killSwitchFile;
    public String accessKeyIdEnvironment = "AUTOMODPACK_OSS_READ_KEY_ID";
    public String accessKeySecretEnvironment = "AUTOMODPACK_OSS_READ_KEY_SECRET";
    public String securityTokenEnvironment = "AUTOMODPACK_OSS_READ_SECURITY_TOKEN";
    public String serverIdentityKeyEnvironment = "AUTOMODPACK_OSS_SERVER_IDENTITY_PKCS8";
    public int urlLifetimeSeconds = 180;
    public int sessionLifetimeSeconds = 900;
    public int activeRefreshSeconds = 0;
    public String externalRevocationEndpoint;
    public String externalRevocationTokenEnvironment;

    public static OssServerConfig load(Path path) throws Exception {
        if (!path.isAbsolute() || Files.size(path) > 65536) throw new IllegalArgumentException("Invalid external config path");
        OssServerConfig c = new Gson().fromJson(Files.readString(path), OssServerConfig.class);
        if (c == null || !c.enabled) throw new IllegalArgumentException("OSS synchronization disabled");
        if (c.packId == null || c.packId.isBlank() || c.bucket == null || c.region == null || c.trustStore == null || !Path.of(c.trustStore).isAbsolute()
                || c.activeReleaseFile == null || !Path.of(c.activeReleaseFile).isAbsolute()
                || c.killSwitchFile == null || !Path.of(c.killSwitchFile).isAbsolute()
                || c.urlLifetimeSeconds < 120 || c.urlLifetimeSeconds > 300 || c.sessionLifetimeSeconds < 180 || c.sessionLifetimeSeconds > 3600
                || c.activeRefreshSeconds != 0 && (c.activeRefreshSeconds < 300 || c.activeRefreshSeconds > 3600))
            throw new IllegalArgumentException("Incomplete OSS synchronization configuration");
        if (c.externalRevocationEndpoint != null) {
            java.net.URI hook = java.net.URI.create(c.externalRevocationEndpoint);
            if (!"https".equals(hook.getScheme()) || hook.getHost() == null || hook.getUserInfo() != null || hook.getFragment() != null
                    || c.externalRevocationTokenEnvironment == null || System.getenv(c.externalRevocationTokenEnvironment) == null)
                throw new IllegalArgumentException("Incomplete optional external revocation hook");
        }
        return c;
    }
}
