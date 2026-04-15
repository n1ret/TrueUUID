package cn.alini.trueuuid.mixin.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At; // official mappings
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.alini.trueuuid.Trueuuid;
import cn.alini.trueuuid.net.NetIds;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakeMixin {
    @Shadow private Connection connection;

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void trueuuid$onCustomQuery(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        if (!NetIds.AUTH.equals(packet.getIdentifier())) return;

        int txId = packet.getTransactionId();
        FriendlyByteBuf buf = packet.getData();
        String serverId = buf.readUtf();
        long serverTimeoutMs = -1L;
        try {
            if (buf.readableBytes() >= Long.BYTES) {
                serverTimeoutMs = buf.readLong();
            }
        } catch (Throwable t) {
            Trueuuid.debug(t, "Failed to read server timeout field, txId={}", txId);
        }

        Minecraft mc = Minecraft.getInstance();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        User user = mc.getUser();
                        var profile = user.getGameProfile();
                        String token = user.getAccessToken();

                        // The access token is only used locally.
                        mc.getMinecraftSessionService().joinServer(profile, token, serverId);
                        return true;
                    } catch (Throwable t) {
                        Trueuuid.debug(t, "joinServer call failed, txId={}", txId);
                        return false;
                    }
                })
                // Prevent slow joinServer from causing server timeout/compression-stage mismatch.
                .completeOnTimeout(false, Math.max(1000L, serverTimeoutMs > 0 ? (serverTimeoutMs - 500L) : 8000L), TimeUnit.MILLISECONDS)
                .thenAccept(ok -> {
                    // Reply only while still in LOGIN stage to avoid late-packet decode errors.
                    try {
                        if (this.connection.getPacketListener() != (Object) this) {
                            Trueuuid.debug("Skip reply: no longer in LOGIN stage, txId={}", txId);
                            return;
                        }
                    } catch (Throwable t) {
                        Trueuuid.debug(t, "Failed to check LOGIN listener, txId={}", txId);
                        // Keep compatibility: still attempt to send reply if listener check fails.
                    }

                    FriendlyByteBuf resp = new FriendlyByteBuf(Unpooled.buffer());
                    resp.writeBoolean(ok);
                    try {
                        this.connection.send(new ServerboundCustomQueryPacket(txId, resp));
                    } catch (Throwable t) {
                        Trueuuid.debug(t, "Failed to send auth reply packet, txId={}", txId);
                    }
                });

        ci.cancel();
    }

}
