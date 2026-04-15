package cn.alini.trueuuid.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public final class TrueuuidConfig {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        COMMON = new Common(b);
        COMMON_SPEC = b.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    public static long timeoutMs() {
        return COMMON.timeoutMs.get();
    }

    public static boolean allowOfflineOnTimeout() {
        return COMMON.allowOfflineOnTimeout.get();
    }

    // Old switch: kept for compatibility, but the new strategy is more granular
    public static boolean allowOfflineOnFailure() {
        return COMMON.allowOfflineOnFailure.get();
    }

    public static String timeoutKickMessage() {
        return COMMON.timeoutKickMessage.get();
    }

    public static String offlineFallbackMessage() {
        return COMMON.offlineFallbackMessage.get();
    }

    // Added: short subtitle (for the Title area on screen)
    public static String offlineShortSubtitle() {
        return COMMON.offlineShortSubtitle.get();
    }

    public static String onlineShortSubtitle() {
        return COMMON.onlineShortSubtitle.get();
    }

    // Added: policy-related
    public static boolean knownPremiumDenyOffline() {
        return COMMON.knownPremiumDenyOffline.get();
    }

    public static boolean allowOfflineForUnknownOnly() {
        return COMMON.allowOfflineForUnknownOnly.get();
    }

    public static boolean recentIpGraceEnabled() {
        return COMMON.recentIpGraceEnabled.get();
    }

    public static int recentIpGraceTtlSeconds() {
        return COMMON.recentIpGraceTtlSeconds.get();
    }

    public static boolean debug() {
        return COMMON.debug.get();
    }
    // Added nomojang switch accessors

    public static boolean nomojangEnabled() {
        return COMMON.nomojangEnabled.get();
    }

    public static String mojangReverseProxy() {
        return COMMON.mojangReverseProxy.get();
    }

    public static final class Common {

        public final ForgeConfigSpec.LongValue timeoutMs;
        public final ForgeConfigSpec.BooleanValue allowOfflineOnTimeout;
        public final ForgeConfigSpec.BooleanValue allowOfflineOnFailure;
        public final ForgeConfigSpec.ConfigValue<String> timeoutKickMessage;
        public final ForgeConfigSpec.ConfigValue<String> offlineFallbackMessage;

        // Added
        public final ForgeConfigSpec.ConfigValue<String> offlineShortSubtitle;
        public final ForgeConfigSpec.ConfigValue<String> onlineShortSubtitle;

        // Added nomojang config
        public final ForgeConfigSpec.BooleanValue nomojangEnabled;
        public final ForgeConfigSpec.ConfigValue<String> mojangReverseProxy;

        // Added: policy-related
        public final ForgeConfigSpec.BooleanValue knownPremiumDenyOffline;
        public final ForgeConfigSpec.BooleanValue allowOfflineForUnknownOnly;
        public final ForgeConfigSpec.BooleanValue recentIpGraceEnabled;
        public final ForgeConfigSpec.IntValue recentIpGraceTtlSeconds;
        public final ForgeConfigSpec.BooleanValue debug;

        Common(ForgeConfigSpec.Builder b) {
            b.push("auth");

            timeoutMs = b.defineInRange("timeoutMs", 10_000L, 1_000L, 600_000L);
            allowOfflineOnTimeout = b.comment("false: kick on timeout (default); true: allow offline on timeout").define("allowOfflineOnTimeout", true);
            allowOfflineOnFailure = b.comment("false: kick on failure; true: treat any authentication failure as offline (default)").define("allowOfflineOnFailure", true);

            timeoutKickMessage = b.define("timeoutKickMessage", "Login timed out; account verification not completed");
            offlineFallbackMessage = b.define(
                    "offlineFallbackMessage",
                    "Notice: You are entering the server in offline mode; if you are a legitimate (paid) account, network issues may have prevented successful authentication—please log in again and retry. If you continue to play and later authentication succeeds, player data may be lost."
            );

            // Default: short, non-intrusive
            offlineShortSubtitle = b.define("offlineShortSubtitle", "Authentication failed: Offline mode");
            onlineShortSubtitle = b.define("onlineShortSubtitle", "Passed online verification");

            // Policy items
            knownPremiumDenyOffline = b.comment("Once this name has been successfully verified as premium, deny offline entry on subsequent auth failures.")
                    .define("knownPremiumDenyOffline", false);
            allowOfflineForUnknownOnly = b.comment("Allow offline fallback only for names never verified as premium.")
                    .define("allowOfflineForUnknownOnly", true);
            recentIpGraceEnabled = b.comment("Enable 'recent same IP success' tolerance: treat failures as premium within TTL.")
                    .define("recentIpGrace.enabled", true);
            recentIpGraceTtlSeconds = b.comment("TTL seconds for 'recent same IP success' tolerance. Recommended 60~600.")
                    .defineInRange("recentIpGrace.ttlSeconds", 300, 30, 3600);
            debug = b.comment("Enable debug log output").define("debug", false);
            // Added: skip Mojang session authentication (when enabled, sessionserver checks are disabled)
            nomojangEnabled = b.comment("When enabled, disable Mojang session service online verification; names with recent premium success from the same IP are treated using their premium UUID, others are handled as offline entry.")
                    .define("nomojang.enabled", false);
            mojangReverseProxy = b.comment("Mojang reverse proxy address, for those who don't want to set up a proxy; default is Mojang address").define("mojangReverseProxy", "https://sessionserver.mojang.com");
            b.pop();
        }
    }

    private TrueuuidConfig() {
    }

}
