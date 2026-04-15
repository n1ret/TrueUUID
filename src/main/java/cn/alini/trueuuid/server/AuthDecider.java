package cn.alini.trueuuid.server;

import java.util.Optional;
import java.util.UUID;

import cn.alini.trueuuid.config.TrueuuidConfig;

public final class AuthDecider {

    public static class Decision {
        public enum Kind { PREMIUM_GRACE, OFFLINE, DENY }
        public Kind kind;
        public UUID premiumUuid; // Filled when kind is PREMIUM_GRACE
        public String denyMessage;
    }

    public static Decision onFailure(String name, String ip) {
        Decision d = new Decision();

        boolean known = TrueuuidRuntime.NAME_REGISTRY.isKnownPremiumName(name);

        // 1) Name already verified as premium: deny offline fallback.
        if (known && TrueuuidConfig.knownPremiumDenyOffline()) {
            d.kind = Decision.Kind.DENY;
            d.denyMessage = "This name is already bound to a premium UUID. Offline login is not allowed after auth failure. Please check your network and try again.";
            return d;
        }

        // 2) Recent same-IP success grace: temporarily treat as premium.
        if (TrueuuidConfig.recentIpGraceEnabled()) {
            Optional<UUID> p = TrueuuidRuntime.IP_GRACE.tryGrace(name, ip, TrueuuidConfig.recentIpGraceTtlSeconds());
            if (p.isPresent()) {
                d.kind = Decision.Kind.PREMIUM_GRACE;
                d.premiumUuid = p.get();
                return d;
            }
        }

        // 3) Unknown name: allow offline fallback.
        if (TrueuuidConfig.allowOfflineForUnknownOnly() && !known) {
            d.kind = Decision.Kind.OFFLINE;
            return d;
        }

        // 4) Otherwise deny.
        d.kind = Decision.Kind.DENY;
        d.denyMessage = "Authentication failed. Offline login has been blocked to protect your premium player data. Please try again later.";
        return d;
    }

    private AuthDecider() {}

}