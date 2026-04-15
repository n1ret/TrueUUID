package cn.alini.trueuuid;

import java.util.Arrays;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import cn.alini.trueuuid.config.TrueuuidConfig;
import cn.alini.trueuuid.server.TrueuuidRuntime;
import net.minecraftforge.fml.common.Mod;

@Mod(Trueuuid.MODID)
public class Trueuuid {
    public static final String MODID = "trueuuid";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static boolean isDebug() {
        try {
            return TrueuuidConfig.debug();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void debug(String message, Object... args) {
        if (!isDebug()) return;
        LOGGER.debug(prefixDebug(message), args);
    }

    public static void debug(Throwable t, String message, Object... args) {
        if (!isDebug()) return;
        LOGGER.debug(prefixDebug(message), appendArg(args, t));
    }

    public static void info(String message, Object... args) {
        LOGGER.info(prefix(message), args);
    }

    public static void warn(String message, Object... args) {
        LOGGER.warn(prefix(message), args);
    }

    public static void warn(Throwable t, String message, Object... args) {
        // Only print stack traces in debug mode to avoid polluting latest.log.
        if (isDebug()) {
            LOGGER.debug(prefixDebug(message), appendArg(args, t));
        }
        LOGGER.warn(prefix(message) + " ({})", appendArg(args, brief(t)));
    }

    public static void error(String message, Object... args) {
        LOGGER.error(prefix(message), args);
    }

    public static void error(Throwable t, String message, Object... args) {
        // Only print stack traces in debug mode to avoid polluting latest.log.
        if (isDebug()) {
            LOGGER.debug(prefixDebug(message), appendArg(args, t));
        }
        LOGGER.error(prefix(message) + " ({})", appendArg(args, brief(t)));
    }

    public Trueuuid() {
        // Register and generate config/trueuuid-common.toml.
        TrueuuidConfig.register();

        // Initialize runtime singletons (registry, recent-IP grace cache, etc.).
        TrueuuidRuntime.init();

        // ===== Mojang connectivity check =====
        // If nomojang is enabled, skip startup connectivity checks.
        if (TrueuuidConfig.nomojangEnabled()) {
            info("nomojang is enabled; skipping Mojang session server connectivity check");
        } else {
            // ===== Mojang connectivity check =====
            try {
                String testUrl = TrueuuidConfig.COMMON.mojangReverseProxy.get()+"/session/minecraft/hasJoined?username=Mojang&serverId=test";
                java.net.URL url = new java.net.URL(testUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000); // 3 second timeout
                conn.setReadTimeout(3000);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200 || responseCode == 204 || responseCode == 403) {
                    info("Connected to Mojang session server, status code: {}", responseCode);
                } else {
                    warn("Unexpected response from Mojang session server, status code: {}", responseCode);
                }
            } catch (Exception e) {
                error(e, "Unable to connect to Mojang session server; please check network or firewall settings");
            }
        }

        info("TrueUUID has been loaded");
    }

    private static String prefix(String message) {
        return "[TrueUUID] " + message;
    }

    private static String prefixDebug(String message) {
        return "[TrueUUID][DEBUG] " + message;
    }

    private static Object[] appendArg(Object[] args, Object extra) {
        if (args == null || args.length == 0) {
            return new Object[]{extra};
        }
        Object[] copy = Arrays.copyOf(args, args.length + 1);
        copy[args.length] = extra;
        return copy;
    }

    private static String brief(Throwable t) {
        if (t == null) return "null";
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) return t.getClass().getSimpleName();
        return t.getClass().getSimpleName() + ": " + msg;
    }
}
