package pl.skidam.automodpack_core.oss.server;

import java.util.UUID;

/** The proof arguments originate exclusively from the successful Mojang session-service call. */
public final class OnlineSessionGate {
    private OnlineSessionGate() {}
    public static boolean eligible(boolean onlineMode, boolean integrated, boolean memoryConnection,
                                   UUID provedUuid, String provedName, UUID currentUuid, String currentName) {
        return onlineMode && !integrated && !memoryConnection && provedUuid != null && provedName != null
                && provedUuid.equals(currentUuid) && provedName.equals(currentName);
    }
}
