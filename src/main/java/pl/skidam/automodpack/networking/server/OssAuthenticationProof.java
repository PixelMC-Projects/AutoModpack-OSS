package pl.skidam.automodpack.networking.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Created only after Mojang hasJoinedServer succeeds, never from the client's claimed UUID. */
public final class OssAuthenticationProof {
    public record Identity(UUID uuid, String name) {}
    private static final Map<ServerLoginPacketListenerImpl,Identity> VERIFIED = Collections.synchronizedMap(new WeakHashMap<>());
    private OssAuthenticationProof() {}
    public static void verified(ServerLoginPacketListenerImpl listener, GameProfile profile) {
        if (profile != null && profile.getId() != null && profile.getName() != null)
            VERIFIED.put(listener, new Identity(profile.getId(), profile.getName()));
    }
    public static Identity take(ServerLoginPacketListenerImpl listener) { return VERIFIED.remove(listener); }
    public static void clear(ServerLoginPacketListenerImpl listener) { VERIFIED.remove(listener); }
}
