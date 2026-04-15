// java
package cn.alini.trueuuid.mixin.server;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import cn.alini.trueuuid.Trueuuid;
import cn.alini.trueuuid.config.TrueuuidConfig;
import cn.alini.trueuuid.net.NetIds;
import cn.alini.trueuuid.server.AuthDecider;
import cn.alini.trueuuid.server.AuthState;
import cn.alini.trueuuid.server.SessionCheck;
import cn.alini.trueuuid.server.TrueuuidRuntime;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;

@Mixin(targets = "net.minecraft.server.network.ServerLoginPacketListenerImpl")
public abstract class ServerLoginMixin {
    @Shadow private GameProfile gameProfile;
    @Shadow private MinecraftServer server;
    @Shadow private Connection connection; // In 1.20.1 this is a field.

    @Shadow public abstract void disconnect(Component reason);

    // Handshake state.
    // Avoid conflicts with Forge login negotiation txIds (0/1/2/...).
    @Unique private static final AtomicInteger TRUEUUID$NEXT_TX_ID = new AtomicInteger(0x4000);
    @Unique private int trueuuid$txId = 0;
    @Unique private String trueuuid$nonce = null;
    @Unique private long trueuuid$sentAt = 0L;


    // Prevent duplicate handling of client auth packets within the same handshake.
    @Unique private volatile boolean trueuuid$ackHandled = false;

    @Inject(method = "handleHello", at = @At("TAIL"))
    private void trueuuid$afterHello(ServerboundHelloPacket pkt, CallbackInfo ci) {
        if (this.server.usesAuthentication() || this.gameProfile == null) return;

        // If nomojang is enabled, apply local policy and skip session auth packet.
        if (TrueuuidConfig.nomojangEnabled()) {
            String name = this.gameProfile.getName();
            String ip;
            if (this.connection.getRemoteAddress() instanceof InetSocketAddress isa) {
                ip = isa.getAddress().getHostAddress();
            } else {
                ip = null;
            }
            Trueuuid.debug("nomojang mode: skipping Mojang session authentication, player: {}, ip: {}", name != null ? name : "<unknown>", ip);

            // Try recent same-IP grace hit -> treat as premium.
            if (TrueuuidConfig.recentIpGraceEnabled() && ip != null) {
                var pOpt = TrueuuidRuntime.IP_GRACE.tryGrace(name, ip, TrueuuidConfig.recentIpGraceTtlSeconds());
                if (pOpt.isPresent()) {
                    UUID premium = pOpt.get();
                    if (premium != null) {
                        Trueuuid.debug("nomojang: found same-IP premium record; handling as premium, uuid={}", premium);
                        GameProfile newProfile = new GameProfile(premium, name);
                        this.gameProfile = newProfile;
                        // Record success to keep registry/cache consistent.
                        TrueuuidRuntime.NAME_REGISTRY.recordSuccess(name, premium, ip);
                        TrueuuidRuntime.IP_GRACE.record(name, ip, premium);
                        return; // Done: handled as premium.
                    }
                }
            }

            // Otherwise: allow offline handling (do not block join).
            Trueuuid.debug("nomojang: no same-IP premium record hit, allowing offline path");
            // Do not send custom auth packet; keep default offline behavior.
            return;
        }


        // Reset ack handling flag for the new handshake.
        this.trueuuid$ackHandled = false;

        this.trueuuid$nonce = UUID.randomUUID().toString().replace("-", "");
        this.trueuuid$txId = TRUEUUID$NEXT_TX_ID.getAndIncrement();
        this.trueuuid$sentAt = System.currentTimeMillis();

        Trueuuid.debug("handleHello: starting handshake, player: {}", this.gameProfile != null ? this.gameProfile.getName() : "<unknown>");
        Trueuuid.debug("handshake nonce: {}, txId: {}", this.trueuuid$nonce, this.trueuuid$txId);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(this.trueuuid$nonce);
        // Include server timeout so client can reply early and avoid late LOGIN packet issues.
        buf.writeLong(TrueuuidConfig.timeoutMs());

        this.connection.send(new ClientboundCustomQueryPacket(this.trueuuid$txId, NetIds.AUTH, buf));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void trueuuid$onTick(CallbackInfo ci) {
        if (this.trueuuid$txId == 0 || this.trueuuid$sentAt == 0L) return;
        long timeoutMs = TrueuuidConfig.timeoutMs();
        if (timeoutMs <= 0) return;

        long now = System.currentTimeMillis();
        if (now - this.trueuuid$sentAt < timeoutMs) return;

        Trueuuid.debug("handshake timed out, txId: {}", this.trueuuid$txId);

        if (TrueuuidConfig.allowOfflineOnTimeout()) {
            Trueuuid.debug("timeout allows offline join");
            AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.TIMEOUT);
            reset();
        } else {
            String msg = TrueuuidConfig.timeoutKickMessage();
            Component reason = Component.literal(msg != null ? msg : "Login timed out; account verification not completed");
            sendDisconnectWithReason(reason);
            reset();
        }
    }

    // Block handleAcceptedLogin until TrueUUID handshake is complete.
    @Inject(method = "handleAcceptedLogin", at = @At("HEAD"), cancellable = true)
    private void trueuuid$delayAcceptedLogin(CallbackInfo ci) {
        if (this.trueuuid$txId != 0) {
            ci.cancel();
        }
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void trueuuid$onLoginCustom(ServerboundCustomQueryPacket packet, CallbackInfo ci) {
        if (this.trueuuid$txId == 0) return;
        if (packet.getTransactionId() != this.trueuuid$txId) return;

        String ip;
        if (this.connection.getRemoteAddress() instanceof InetSocketAddress isa) {
            ip = isa.getAddress().getHostAddress();
        } else {
            ip = null;
        }
        Trueuuid.debug("received client auth packet, player: {}, ip: {}, txId: {}", this.gameProfile != null ? this.gameProfile.getName() : "<unknown>", ip, this.trueuuid$txId);

        FriendlyByteBuf data = packet.getData();
        if (data == null) {
            Trueuuid.debug("auth failed, player: {}, ip: {}, reason: missing data", this.gameProfile != null ? this.gameProfile.getName() : "<unknown>", ip);
            handleAuthFailure(ip, "missing data");
            reset(); ci.cancel(); return;
        }

        boolean ackOk = false;
        try {
            ackOk = data.readBoolean();
        } catch (Throwable t) {
            Trueuuid.debug(t, "failed reading ackOk from client auth packet, player={}, ip={}, txId={}", this.gameProfile != null ? this.gameProfile.getName() : "<unknown>", ip, this.trueuuid$txId);
        }
        Trueuuid.debug("client auth packet ackOk: {}", ackOk);
        if (!ackOk) {
            Trueuuid.debug("auth failed, player: {}, ip: {}, reason: client rejected", this.gameProfile != null ? this.gameProfile.getName() : "<unknown>", ip);
            handleAuthFailure(ip, "client rejected");
            reset(); ci.cancel(); return;
        }

        // Idempotency guard: ignore duplicate ack packets for the same handshake.
        if (this.trueuuid$ackHandled) {
            Trueuuid.debug("duplicate auth packet ignored, txId: {}", this.trueuuid$txId);
            ci.cancel();
            return;
        }
        this.trueuuid$ackHandled = true;

        // Key point: use async API and avoid blocking the main thread.
        try {
            // Cancel original handling immediately, but keep state until callback completes.
            ci.cancel();

            final int expectedTxId = this.trueuuid$txId;
            final String expectedNonce = this.trueuuid$nonce;
            final String expectedName = this.gameProfile.getName();

            SessionCheck.hasJoinedAsync(expectedName, expectedNonce, ip)
                    .whenComplete((resOpt, throwable) -> {
                        // Always continue on the main thread.
                        server.execute(() -> {
                            // Ignore stale callbacks after timeout/reset/stage switch.
                            if (this.trueuuid$txId != expectedTxId || this.trueuuid$nonce == null || !this.trueuuid$nonce.equals(expectedNonce)) {
                                Trueuuid.debug("stale callback ignored, expectedTxId={}, currentTxId={}, noncePresent={}", expectedTxId, this.trueuuid$txId, this.trueuuid$nonce != null);
                                return;
                            }
                            try {
                                if (throwable != null) {
                                    Trueuuid.debug(throwable, "auth async callback threw exception, player={}, ip={}, txId={}", expectedName, ip, expectedTxId);
                                    handleAuthFailure(ip, "server exception");
                                    return;
                                }

                                if (resOpt.isEmpty()) {
                                    Trueuuid.debug("auth failed, player: {}, ip: {}, reason: invalid session", this.gameProfile != null ? this.gameProfile.getName() : "<unknown>", ip);
                                    handleAuthFailure(ip, "invalid session");
                                    return;
                                }

                                var res = resOpt.get();

                                // Success: record registry/recent IP and replace profile with premium UUID + corrected name + skin properties.
                                TrueuuidRuntime.NAME_REGISTRY.recordSuccess(res.name(), res.uuid(), ip);
                                TrueuuidRuntime.IP_GRACE.record(res.name(), ip, res.uuid());

                                GameProfile newProfile = new GameProfile(res.uuid(), res.name());
                                var propMap = newProfile.getProperties();
                                propMap.removeAll("textures");
                                for (var p : res.properties()) {
                                    if (p.signature() != null) {
                                        propMap.put(p.name(), new Property(p.name(), p.value(), p.signature()));
                                    } else {
                                        propMap.put(p.name(), new Property(p.name(), p.value()));
                                    }
                                }
                                this.gameProfile = newProfile;
                            } catch (Throwable t) {
                                Trueuuid.debug(t, "exception during async auth handling, player={}, ip={}, txId={}", expectedName, ip, expectedTxId);
                                handleAuthFailure(ip, "server exception");
                            } finally {
                                reset();
                            }
                        });
                    });

        } catch (Throwable t) {
            // If async setup fails (rare), fall back to failure handling and reset.
            Trueuuid.debug(t, "error starting async authentication, player={}, ip={}, txId={}", this.gameProfile != null ? this.gameProfile.getName() : "<unknown>", ip, this.trueuuid$txId);
            handleAuthFailure(ip, "server exception");
            reset();
            this.trueuuid$ackHandled = false;
        }
    }

    @Unique
    private void handleAuthFailure(String ip, String why) {
        String name = this.gameProfile != null ? this.gameProfile.getName() : "<unknown>";
        Trueuuid.debug("authentication failed, player: {}, ip: {}, reason: {}", name, ip, why);
        AuthDecider.Decision d = AuthDecider.onFailure(name, ip);

        switch (d.kind) {
            case PREMIUM_GRACE -> {
                UUID premium = d.premiumUuid != null ? d.premiumUuid
                        : TrueuuidRuntime.NAME_REGISTRY.getPremiumUuid(name).orElse(null);
                if (premium != null) {
                    this.gameProfile = new GameProfile(premium, name);
                } else {
                    AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
                }
            }
            case OFFLINE -> {
                Trueuuid.debug("offline join granted, player: {}, ip: {}", name, ip);
                AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
            }
            case DENY -> {
                String msg = d.denyMessage != null ? d.denyMessage
                        : "Authentication failed. Offline login has been blocked to protect your premium player data. Please try again later.";
                Trueuuid.debug("authentication denied, player: {}, ip: {}, message: {}", name, ip, msg);
                sendDisconnectWithReason(Component.literal(msg));
            }
        }
    }

    @Unique
    private void sendDisconnectWithReason(Component reason) {
        // Disconnect asynchronously to avoid stalling the main thread.
        new Thread(() -> {
            try {
                this.connection.send(new ClientboundLoginDisconnectPacket(reason));
                this.connection.send(new ClientboundDisconnectPacket(reason));
            } catch (Throwable t) {
                Trueuuid.debug(t, "failed to send disconnect reason packet");
            }
            this.connection.disconnect(reason);
        }, "TrueUUID-AsyncDisconnect").start();
    }

    @Unique
    private void reset() {
        Trueuuid.debug("state reset, txId: {}", this.trueuuid$txId);
        this.trueuuid$txId = 0;
        this.trueuuid$nonce = null;
        this.trueuuid$sentAt = 0L;
        this.trueuuid$ackHandled = false;
    }
}
