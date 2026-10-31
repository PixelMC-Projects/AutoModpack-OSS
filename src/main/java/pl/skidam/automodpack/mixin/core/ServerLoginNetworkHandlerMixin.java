package pl.skidam.automodpack.mixin.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack.networking.LoginQueryParser;
import pl.skidam.automodpack.networking.server.OssAuthenticationProof;
/*? if >=1.20.2 {*/
/*import pl.skidam.automodpack.networking.server.LoginRequestPayload;
*//*?}*/
import pl.skidam.automodpack_core.oss.server.DownloadAuthorizer;
import pl.skidam.automodpack_core.oss.server.OssServerRuntime;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 300)
public abstract class ServerLoginNetworkHandlerMixin {
    @Shadow private ServerLoginPacketListenerImpl.State state;
    @Unique private static final int AUTOMODPACK_OSS_QUERY = 0x6F535331;
    @Unique private boolean automodpack$done;
    @Unique private int automodpack$expected;
    @Unique private long automodpack$deadline, automodpack$idleDeadline, automodpack$sessionExpiresAt;
    @Unique private OssAuthenticationProof.Identity automodpack$identity;
    @Unique private DownloadAuthorizer.Grant automodpack$grant;
    @Unique private String automodpack$nonce, automodpack$ip;
    @Unique private boolean automodpack$downloading;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void automodpack$beforeForgeNegotiation(CallbackInfo ci) {
        if (automodpack$done) return;
        OssServerRuntime runtime = OssServerRuntime.get();
        if (runtime == null || !runtime.available()) { automodpack$endDownloadOrContinue(); return; }
        ServerLoginPacketListenerImpl self = (ServerLoginPacketListenerImpl)(Object)this;
        ServerLoginNetworkHandlerAccessor access = (ServerLoginNetworkHandlerAccessor)self;
        if (state != ServerLoginPacketListenerImpl.State.NEGOTIATING) return;
        if (automodpack$expected != 0) {
            if (System.nanoTime() >= automodpack$deadline || automodpack$downloading && System.nanoTime() >= automodpack$idleDeadline) { automodpack$endDownloadOrContinue(); return; }
            ci.cancel(); return;
        }
        automodpack$identity = OssAuthenticationProof.take(self);
        if (automodpack$identity == null || access.getGameProfile() == null
                || !pl.skidam.automodpack_core.oss.server.OnlineSessionGate.eligible(access.getServer().usesAuthentication(), access.getServer().isSingleplayer(), access.getConnection().isMemoryConnection(),
                    automodpack$identity.uuid(), automodpack$identity.name(), access.getGameProfile().getId(), access.getGameProfile().getName())
                || !(access.getConnection().getRemoteAddress() instanceof InetSocketAddress remote) || remote.getAddress() == null) {
            automodpack$finish(false); return;
        }
        try {
            automodpack$ip = remote.getAddress().getHostAddress();
            automodpack$grant = runtime.issue(automodpack$identity.uuid(), automodpack$ip);
            automodpack$expected = AUTOMODPACK_OSS_QUERY;
            automodpack$deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
            JsonObject challenge = new JsonObject(); challenge.addProperty("schema", 1); challenge.addProperty("challenge", true); challenge.addProperty("packId", runtime.trust().packId());
            automodpack$send(automodpack$expected, challenge.toString().getBytes(StandardCharsets.UTF_8));
            ci.cancel();
        } catch (Exception e) { automodpack$finish(false); }
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void automodpack$handleOssResponse(ServerboundCustomQueryPacket packet, CallbackInfo ci) {
        LoginQueryParser query = new LoginQueryParser(packet);
        if (automodpack$expected == 0 || query.queryId != automodpack$expected) return;
        ci.cancel();
        try {
            if (query.buf == null || query.buf.readableBytes() > 4096 || System.nanoTime() >= automodpack$deadline) { automodpack$endDownloadOrContinue(); return; }
            byte[] replyBytes = new byte[query.buf.readableBytes()];
            query.buf.getBytes(query.buf.readerIndex(),replyBytes);
            JsonObject response = pl.skidam.automodpack_core.oss.server.LoginReplyValidator.parse(replyBytes,automodpack$expected,query.queryId,
                    automodpack$expected == AUTOMODPACK_OSS_QUERY ? null : automodpack$nonce);
            OssServerRuntime runtime = OssServerRuntime.get();
            if (runtime == null || !runtime.available()) { automodpack$endDownloadOrContinue(); return; }
            String status = response.get("status").getAsString();
            if (automodpack$expected == AUTOMODPACK_OSS_QUERY && status.equals("challenge")) {
                automodpack$nonce = response.get("nonce").getAsString();
                byte[] offer = runtime.offer(automodpack$identity.uuid(), automodpack$identity.name(), automodpack$nonce, automodpack$grant, AUTOMODPACK_OSS_QUERY + 1);
                automodpack$expected = AUTOMODPACK_OSS_QUERY + 1; automodpack$send(automodpack$expected, offer); return;
            }
            if (automodpack$expected == AUTOMODPACK_OSS_QUERY + 1 && status.equals("accepted") && runtime.accept(automodpack$grant.token())) {
                automodpack$downloading = true;
                automodpack$sessionExpiresAt = java.time.Instant.now().getEpochSecond() + runtime.sessionLifetimeSeconds();
                automodpack$idleDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                automodpack$deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(runtime.sessionLifetimeSeconds());
                automodpack$expected++;
                automodpack$send(automodpack$expected, runtime.downloadReady(automodpack$nonce, automodpack$expected, "download", automodpack$sessionExpiresAt));
                return;
            }
            if (automodpack$downloading) {
                if (!runtime.allowMetadata(automodpack$grant.token(),automodpack$ip)) throw new SecurityException("Metadata rate exceeded or release changed");
                if (!automodpack$nonce.equals(response.get("clientNonce").getAsString())) throw new SecurityException("Nonce mismatch");
                automodpack$idleDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                if (status.equals("keepalive")) {
                    automodpack$expected++;
                    automodpack$send(automodpack$expected, runtime.downloadReady(automodpack$nonce, automodpack$expected, "keepalive", automodpack$sessionExpiresAt));
                    return;
                }
                if (status.equals("file")) {
                    byte[] answer = runtime.downloadMessage(automodpack$grant.token(), automodpack$ip, automodpack$nonce,
                            response.get("releaseId").getAsString(), response.get("path").getAsString(), automodpack$expected + 1);
                    automodpack$expected++; automodpack$send(automodpack$expected, answer); return;
                }
                automodpack$finish(false);
                ((ServerLoginPacketListenerImpl)(Object)this).disconnect(Component.literal(status.equals("complete")
                        ? "Update staged. Restart and join manually when ready." : "Update authorization ended. Join manually when ready."));
                return;
            }
            // ready/declined/unavailable/error continue the ordinary Forge compatibility check.
            automodpack$finish(false);
        } catch (Exception e) { automodpack$endDownloadOrContinue(); }
    }
    @Unique private void automodpack$send(int id, byte[] bytes) {
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        ClientboundCustomQueryPacket packet = new ClientboundCustomQueryPacket(id,
                /*? if <1.20.2 {*/ Common.id("oss_authorization"), data /*?} else {*/ /*new LoginRequestPayload(Common.id("oss_authorization"), data) *//*?}*/);
        ((ServerLoginNetworkHandlerAccessor)this).getConnection().send(packet);
    }
    @Unique private void automodpack$endDownloadOrContinue() {
        boolean disconnect = automodpack$downloading;
        automodpack$finish(false);
        if (disconnect) ((ServerLoginPacketListenerImpl)(Object)this).disconnect(Component.literal("Update authorization expired or was stopped. Join manually to request another session."));
    }
    @Unique private void automodpack$finish(boolean retainGrant) {
        if (!retainGrant && automodpack$grant != null && OssServerRuntime.get() != null) OssServerRuntime.get().revoke(automodpack$grant.token());
        automodpack$done = true; automodpack$expected = 0;
        OssAuthenticationProof.clear((ServerLoginPacketListenerImpl)(Object)this);
    }
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void automodpack$onDisconnect(Component reason, CallbackInfo ci) {
        if (!automodpack$done) automodpack$finish(false);
    }
}
