package pl.skidam.automodpack.client.oss;

/**
 * Client lifecycle hook for AutoModpack OSS.
 * In-mod settings screens are intentionally omitted; sync settings will live inside PixelMC-Hub.
 */
public final class OssClientNetworking {
    private OssClientNetworking() {}

    public static void register() {
        // First-time consent always targets the official release track; register optional settings
        // into PixelMC-Hub when PixelMC-Hub is present.
        OssHubBridge.registerIfPresent();
    }

    public static void openSettings() {
        // Intentionally disabled per product design; settings belong in PixelMC-Hub.
    }
}
