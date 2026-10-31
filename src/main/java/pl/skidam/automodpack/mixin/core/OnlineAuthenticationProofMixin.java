package pl.skidam.automodpack.mixin.core;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import pl.skidam.automodpack.networking.server.OssAuthenticationProof;
import java.net.InetAddress;
import java.lang.reflect.Field;

/** Forge 1.20.1 successful online authentication, before its NEGOTIATING state starts. */
@Mixin(targets = "net.minecraft.server.network.ServerLoginPacketListenerImpl$1")
public abstract class OnlineAuthenticationProofMixin {
    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;hasJoinedServer(Lcom/mojang/authlib/GameProfile;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/GameProfile;", remap = false))
    private GameProfile automodpack$verifiedSession(MinecraftSessionService service, GameProfile claimed, String hash, InetAddress address) throws AuthenticationUnavailableException {
        GameProfile verified = service.hasJoinedServer(claimed, hash, address);
        if (verified != null) {
            ServerLoginPacketListenerImpl listener = automodpack$outerListener();
            if (listener != null) OssAuthenticationProof.verified(listener, verified);
        }
        return verified;
    }
    /** Anonymous synthetic field names vary between official/SRG namespaces; select only its exact type. */
    @Unique private ServerLoginPacketListenerImpl automodpack$outerListener() {
        try {
            Field outer = null;
            for (Field candidate : getClass().getDeclaredFields()) {
                if (candidate.getType() != ServerLoginPacketListenerImpl.class) continue;
                if (outer != null) return null; // Ambiguous shape: refuse proof rather than guessing.
                outer = candidate;
            }
            if (outer == null || !outer.trySetAccessible()) return null;
            return (ServerLoginPacketListenerImpl)outer.get(this);
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }
}
