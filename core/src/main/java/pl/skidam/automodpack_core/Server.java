package pl.skidam.automodpack_core;

/** The OSS fork requires a real authenticated Forge login; standalone legacy file hosting is disabled. */
public final class Server {
    public static void main(String[] args) {
        GlobalVariables.LOGGER.error("Legacy standalone file hosting is disabled. Use the authenticated Forge OSS integration.");
    }
}
