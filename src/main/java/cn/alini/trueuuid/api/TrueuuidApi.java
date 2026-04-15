package cn.alini.trueuuid.api;

import java.util.UUID;

import cn.alini.trueuuid.server.TrueuuidRuntime;

/**
 * TrueUUID API: provides premium-status query interfaces for add-on mods.
 */
public class TrueuuidApi {
    /**
     * Check whether the specified player name is recognized as premium by TrueUUID.
     * @param name Player name (case-insensitive)
     * @return true if recognized as premium, otherwise false.
     */
    public static boolean isPremium(String name) {
        return TrueuuidRuntime.NAME_REGISTRY.isKnownPremiumName(name);
    }

    /**
        * Get the premium UUID for the specified player name, if available.
        * @param name Player name (case-insensitive)
        * @return Premium UUID if present, otherwise null.
     */
    public static UUID getPremiumUuid(String name) {
        return TrueuuidRuntime.NAME_REGISTRY.getPremiumUuid(name).orElse(null);
    }
}

