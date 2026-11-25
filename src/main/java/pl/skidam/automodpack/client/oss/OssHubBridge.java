package pl.skidam.automodpack.client.oss;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import pl.skidam.automodpack_core.oss.ReleaseChannel;
import pl.skidam.automodpack_core.oss.client.ConsentStore;

/**
 * Optional soft-plugin bridge that registers AutoModpack-OSS into PixelMC-Hub's modpack sync
 * settings slot when PixelMC-Hub is present, without introducing any compile-time dependency.
 */
final class OssHubBridge {
    private static final String HUB_REGISTRY_CLASS = "com.pixelmc.hub.client.HubModpackSync";
    private static final long CACHE_TTL_MS = 1_500L;

    private static volatile boolean cachedEnabled = true;
    private static volatile String cachedChannel = ReleaseChannel.STABLE.id();
    private static volatile boolean cachedGranted = false;
    private static volatile long lastLoadedMs = 0L;

    private OssHubBridge() {}

    static void registerIfPresent() {
        try {
            Class<?> registry = Class.forName(HUB_REGISTRY_CLASS);
            Method register =
                    registry.getMethod(
                            "register",
                            BooleanSupplier.class,
                            Supplier.class,
                            BooleanSupplier.class,
                            Consumer.class,
                            Consumer.class,
                            Runnable.class);
            BooleanSupplier enabled = OssHubBridge::readEnabled;
            Supplier<String> channel = OssHubBridge::readChannel;
            BooleanSupplier granted = OssHubBridge::readGranted;
            Consumer<Boolean> setEnabled = OssHubBridge::writeEnabled;
            Consumer<String> setChannel = OssHubBridge::writeChannel;
            Runnable revokeAll = OssHubBridge::writeRevokeAll;
            register.invoke(null, enabled, channel, granted, setEnabled, setChannel, revokeAll);
            LOGGER.info("Registered AutoModpack-OSS sync settings plugin with PixelMC-Hub");
        } catch (ClassNotFoundException ignored) {
            // PixelMC-Hub is optional; run standalone when absent.
        } catch (Throwable failure) {
            LOGGER.warn(
                    "Could not register AutoModpack-OSS plugin with PixelMC-Hub ({})",
                    failure.getClass().getSimpleName());
        }
    }

    static void invalidateCache() {
        lastLoadedMs = 0L;
    }

    private static Path gameRoot() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? Path.of("").toAbsolutePath().normalize() : mc.gameDirectory.toPath();
    }

    private static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastLoadedMs < CACHE_TTL_MS) return;
        synchronized (OssHubBridge.class) {
            if (System.currentTimeMillis() - lastLoadedMs < CACHE_TTL_MS) return;
            try {
                ConsentStore store = new ConsentStore(gameRoot());
                cachedEnabled = store.enabled();
                cachedChannel = store.channel().id();
                cachedGranted = store.grantCount() > 0;
            } catch (Exception ignored) {
                cachedEnabled = false;
                cachedChannel = ReleaseChannel.STABLE.id();
                cachedGranted = false;
            }
            lastLoadedMs = System.currentTimeMillis();
        }
    }

    private static boolean readEnabled() {
        refreshIfStale();
        return cachedEnabled;
    }

    private static String readChannel() {
        refreshIfStale();
        return cachedChannel;
    }

    private static boolean readGranted() {
        refreshIfStale();
        return cachedGranted;
    }

    private static void writeEnabled(boolean value) {
        synchronized (OssHubBridge.class) {
            try {
                ConsentStore store = new ConsentStore(gameRoot());
                store.setEnabled(value);
                cachedEnabled = store.enabled();
                cachedChannel = store.channel().id();
                cachedGranted = store.grantCount() > 0;
                lastLoadedMs = System.currentTimeMillis();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to update AutoModpack-OSS enabled state", e);
            }
        }
    }

    private static void writeChannel(String value) {
        synchronized (OssHubBridge.class) {
            try {
                ConsentStore store = new ConsentStore(gameRoot());
                store.setChannel(ReleaseChannel.parse(value));
                cachedEnabled = store.enabled();
                cachedChannel = store.channel().id();
                cachedGranted = store.grantCount() > 0;
                lastLoadedMs = System.currentTimeMillis();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to update AutoModpack-OSS channel", e);
            }
        }
    }

    private static void writeRevokeAll() {
        synchronized (OssHubBridge.class) {
            try {
                ConsentStore store = new ConsentStore(gameRoot());
                store.revokeAll();
                cachedEnabled = store.enabled();
                cachedChannel = store.channel().id();
                cachedGranted = store.grantCount() > 0;
                lastLoadedMs = System.currentTimeMillis();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to revoke AutoModpack-OSS consent", e);
            }
        }
    }
}
