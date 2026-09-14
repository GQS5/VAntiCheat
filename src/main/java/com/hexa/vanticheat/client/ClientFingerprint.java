package com.hexa.vanticheat.client;

import java.util.Locale;
import java.util.Optional;

/**
 * Fingerprinting with strength (spec §7): brand + channel + protocol signals.
 * Only STRONG fingerprints are eligible for immediate punishment; medium/weak
 * are corroboration only. No bare-substring as sole proof (spec §9).
 */
public final class ClientFingerprint {

    public enum Strength { STRONG, MEDIUM, WEAK }

    public record Result(KnownClients.Fingerprint fingerprint, Strength strength,
                         String detectionMethod, String matchedIdentifier) {}

    private ClientFingerprint() {}

    /** Legacy: match brand, full-token contains (kept for ScopeTest compat). */
    public static Optional<KnownClients.Fingerprint> matchBrand(String brand) {
        return matchBrandDetailed(brand).map(Result::fingerprint);
    }

    public static Optional<Result> matchBrandDetailed(String brand) {
        if (brand == null) return Optional.empty();
        String n = ForbiddenModRegistry.normalize(brand);
        if (n.isEmpty()) return Optional.empty();
        for (KnownClients.Fingerprint f : KnownClients.ALL) {
            if (!f.detectable()) continue;
            for (String sub : f.brandSubstrings()) {
                String sn = ForbiddenModRegistry.normalize(sub);
                if (sn.isEmpty()) continue;
                if (n.equals(sn)) return Optional.of(new Result(f, Strength.STRONG, "exact-brand", sub));
                if (n.contains(sn) && sn.length() >= 5)
                    return Optional.of(new Result(f, Strength.MEDIUM, "brand-token", sub));
            }
        }
        return Optional.empty();
    }

    /** Legacy: match channel. */
    public static Optional<KnownClients.Fingerprint> matchChannel(String channel) {
        return matchChannelDetailed(channel, null).map(Result::fingerprint);
    }

    public static Optional<Result> matchChannelDetailed(String channel, String brand) {
        if (channel == null) return Optional.empty();
        // Split namespace BEFORE normalize: normalize() strips ':' and would
        // otherwise make "meteor:settings" unmatchable (detection hole).
        String lower = channel.toLowerCase(Locale.ROOT);
        String ns = lower.contains(":") ? lower.substring(0, lower.indexOf(':')) : lower;
        String normNs = ForbiddenModRegistry.normalize(ns);
        String normFull = ForbiddenModRegistry.normalize(lower);
        if (normNs.isEmpty()) return Optional.empty();
        for (KnownClients.Fingerprint f : KnownClients.ALL) {
            if (!f.detectable()) continue;
            for (String sub : f.channelSubstrings()) {
                String sn = ForbiddenModRegistry.normalize(sub);
                if (sn.isEmpty()) continue;
                if (normFull.equals(sn) || normNs.equals(sn))
                    return Optional.of(new Result(f, Strength.STRONG, "exact-channel", sub));
            }
        }
        return Optional.empty();
    }

    public static boolean looksVanilla(String brand) {
        if (brand == null) return false;
        String n = ForbiddenModRegistry.normalize(brand);
        return n.equals("vanilla") || n.equals("vanillaclient");
    }

    public static String normalizeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
