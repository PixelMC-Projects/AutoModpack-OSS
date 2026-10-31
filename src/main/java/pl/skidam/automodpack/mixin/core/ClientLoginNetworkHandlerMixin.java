package pl.skidam.automodpack.mixin.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.client.oss.OssLoginSession;

@Mixin(value = ClientHandshakePacketListenerImpl.class, priority = 300)
public abstract class ClientLoginNetworkHandlerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique private OssLoginSession automodpack$oss;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void ossInit(CallbackInfo ci) {
        automodpack$oss = new OssLoginSession(minecraft,
            ((ClientLoginNetworkHandlerAccessor) this).getConnection());
    }
    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void ossQuery(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        if (automodpack$oss != null && automodpack$oss.handle(packet)) ci.cancel();
    }
    @Inject(method = "onDisconnect", at = @At("RETURN"))
    private void ossDisconnect(Component reason, CallbackInfo ci) {
        if (automodpack$oss != null) automodpack$oss.onDisconnected();
    }
}
