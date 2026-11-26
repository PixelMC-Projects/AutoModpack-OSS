package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

import pl.skidam.automodpack_core.oss.PackTrust;
import pl.skidam.automodpack_core.oss.TrustStore;
import pl.skidam.automodpack_core.oss.client.ConsentStore;
import pl.skidam.automodpack_core.oss.client.PrelaunchSyncCoordinator;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Entry point invoked from {@link Preload} before Forge scans mods. Safely guards headless / macOS
 * environments (where AWT must not initialize ahead of GLFW on thread 0) and delegates to {@link
 * PrelaunchSyncCoordinator} with a lazily constructed liquid-glass Swing window.
 */
final class PrelaunchBootstrap {
    private PrelaunchBootstrap() {}

    static PrelaunchSyncCoordinator.Outcome runIfConfigured(
            Path gameRoot, TrustStore trust, ConsentStore consents) {
        if (!consents.enabled()) {
            return new PrelaunchSyncCoordinator.Outcome(
                    false, true, false, null, "Sync disabled by user");
        }
        boolean hasEndpoint = false;
        for (PackTrust p : trust.packs()) {
            if (p.authorizationEndpoint() != null) {
                hasEndpoint = true;
                break;
            }
        }
        if (!hasEndpoint) {
            return new PrelaunchSyncCoordinator.Outcome(
                    false, false, true, null, "No pre-launch gateway configured");
        }
        if (isMacOs() || GraphicsEnvironment.isHeadless()) {
            LOGGER.info(
                    "Pre-launch UI skipped on headless/macOS environment; in-game sync remains"
                        + " active");
            return new PrelaunchSyncCoordinator.Outcome(
                    false, false, true, null, "Pre-launch UI skipped on this platform");
        }
        try (PrelaunchGlassWindow ui = new PrelaunchGlassWindow(gameRoot)) {
            return new PrelaunchSyncCoordinator().run(gameRoot, trust, consents, ui);
        }
    }

    private static boolean isMacOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }
}
