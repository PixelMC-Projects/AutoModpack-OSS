package pl.skidam.automodpack_loader_core;

import java.nio.file.Path;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.oss.TrustStore;
import pl.skidam.automodpack_core.oss.StagedInstaller;
import pl.skidam.automodpack_core.oss.client.ConsentStore;
import pl.skidam.automodpack_core.oss.client.StartupNotice;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.mods.ModpackLoader;
import static pl.skidam.automodpack_core.GlobalVariables.*;

/** Offline only. Invoked before Forge scans any mod candidates. */
public final class Preload {
    public Preload() { this(Path.of("").toAbsolutePath().normalize()); }
    public Preload(Path gameRoot) {
        preload = true;
        LOADER_MANAGER = new LoaderManager();
        MODPACK_LOADER = new ModpackLoader();
        THIS_MOD_JAR = JarUtils.getJarPath(getClass());
        MODS_DIR = gameRoot.resolve("mods");
        MC_VERSION = "1.20.1";
        LOADER = "forge";
        AM_VERSION = FileInspection.getModVersion(THIS_MOD_JAR);
        clientConfig = new Jsons.ClientConfigFieldsV2();
        serverConfig = new Jsons.ServerConfigFieldsV2();
        knownHosts = new Jsons.KnownHostsFields();
        if (LOADER_MANAGER.getEnvironmentType() != LoaderManagerService.EnvironmentType.CLIENT) return;
        try {
            TrustStore trust = TrustStore.load(gameRoot.resolve("config/automodpack-oss/trust.json"));
            ConsentStore consents = new ConsentStore(gameRoot);
            var result = StagedInstaller.applyPending(gameRoot, trust, consents::hasAnyGrant);
            LOGGER.info("OSS offline pre-load installation: {}", result.status());
            try { StartupNotice.record(gameRoot, result); }
            catch (java.io.IOException noticeFailure) { LOGGER.warn("OSS startup notice could not be persisted; current outcome remains visible in settings"); }
        } catch (Exception failure) {
            throw new IllegalStateException("OSS installation recovery failed; startup stopped safely", failure);
        }
    }
}
