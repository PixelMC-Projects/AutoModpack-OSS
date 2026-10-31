package pl.skidam.automodpack.networking.packet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import java.util.concurrent.CompletableFuture;
/** The untrusted upstream handshake/self-update protocol is permanently disabled. */
public final class HandshakeC2SPacket {
    public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buffer) {
        return CompletableFuture.completedFuture(null);
    }
}
