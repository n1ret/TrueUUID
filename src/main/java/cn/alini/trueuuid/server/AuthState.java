package cn.alini.trueuuid.server;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.Connection;

/**
 * Records connections granted offline fallback during login;
 * consumed after full join for post-login notification.
 */
public final class AuthState {
    public enum FallbackReason { TIMEOUT, FAILURE }

    private static final ConcurrentHashMap<Connection, FallbackReason> OFFLINE_FALLBACK = new ConcurrentHashMap<>();

    public static void markOfflineFallback(Connection conn, FallbackReason reason) {
        if (conn != null && reason != null) {
            OFFLINE_FALLBACK.put(conn, reason);
        }
    }

    public static Optional<FallbackReason> consume(Connection conn) {
        if (conn == null) return Optional.empty();
        FallbackReason r = OFFLINE_FALLBACK.remove(conn);
        return Optional.ofNullable(r);
    }

    private AuthState() {}
}