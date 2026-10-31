package pl.skidam.automodpack.mixin.core;

import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retained mixin entry for upstream compatibility. No game-port file protocol is installed. */
@Mixin(targets = "net/minecraft/server/network/ServerConnectionListener$1", priority = 2137)
public abstract class ServerNetworkIoMixin {
    @Inject(method = "initChannel", at = @At("TAIL"))
    private void injectAutoModpackHost(Channel channel, CallbackInfo ci) {
        // All release files are fetched directly from the configured OSS bucket.
    }
}
