package com.hexa.vanticheat.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Centralized fingerprint registry (1.2 §7). Single place for all
 * brand/channel matching — no scattered substring checks.
 * Pre-normalized immutable maps built once; hot path is map lookups only.
 * Vague tokens (e.g. bare "xaero") never resolve: only full identifiers.
 */
public final class FingerprintEngine {

    public enum Category { CHEAT_CLIENT, FORBIDDEN_MOD }
    public enum MatchType { EXACT_ID, NORMALIZED_ID, EXACT_CHANNEL, BRAND_HINT, MULTI_SIGNAL }
    public enum Strength { STRONG, MEDIUM, WEAK }

    public record Definition(String id, String display, Category category, MatchType matchType,
                             Strength strength, int confidence, boolean spoofRelevant) {}
    public record Match(Definition def, String matchedIdentifier, String method) {}

    private final Map<String, Definition> brandExact = new HashMap<>();
    private final Map<String, Definition> channelExact = new HashMap<>();
    private final Map<String, Definition> identifiers = new HashMap<>();

    public FingerprintEngine(ForbiddenModRegistry mods) {
        // Cheat clients: exact brand/channel tokens.
        for (KnownClients.Fingerprint f : KnownClients.ALL) {
            if (!f.detectable()) continue;
            Definition d = new Definition(f.id(), f.display(), Category.CHEAT_CLIENT,
                    MatchType.EXACT_ID, Strength.STRONG, KnownClients.DEFAULT_WEIGHTS.getOrDefault(f.id(), 60), true);
            for (String b : f.brandSubstrings()) {
                String n = ForbiddenModRegistry.normalize(b);
                if (n.length() >= 4) brandExact.putIfAbsent(n, d);
            }
            for (String c : f.channelSubstrings()) {
                String n = ForbiddenModRegistry.normalize(c);
                if (n.length() >= 4) channelExact.putIfAbsent(n, d);
            }
        }
        // Forbidden mods from registry: full identifiers only.
        for (ForbiddenModRegistry.ModEntry e : mods.all()) {
            Definition d = new Definition(e.id(), e.display(), Category.FORBIDDEN_MOD,
                    MatchType.NORMALIZED_ID, Strength.STRONG, 65, false);
            for (String id : e.identifiers()) {
                if (!id.isEmpty()) identifiers.putIfAbsent(id, d);
            }
        }
    }

    public Optional<Match> matchBrand(String brand) {
        String n = ForbiddenModRegistry.normalize(brand);
        if (n.isEmpty()) return Optional.empty();
        Definition d = brandExact.get(n);
        if (d != null) return Optional.of(new Match(d, brand, "exact-brand"));
        // No contains() fallback: vague tokens must not produce confidence.
        return Optional.empty();
    }

    /** Brand-token corroboration only (MEDIUM at best, never sole proof). */
    public Optional<Match> brandHint(String brand, String modId) {
        String n = ForbiddenModRegistry.normalize(brand);
        String m = ForbiddenModRegistry.normalize(modId);
        if (n.isEmpty() || m.isEmpty()) return Optional.empty();
        if (n.contains(m) && m.length() >= 6) {
            Definition d = identifiers.get(m);
            if (d != null) return Optional.of(new Match(
                    new Definition(d.id(), d.display(), d.category(), MatchType.BRAND_HINT, Strength.MEDIUM, 20, false),
                    brand, "brand-hint"));
        }
        return Optional.empty();
    }

    public Optional<Match> matchChannel(String channel) {
        if (channel == null) return Optional.empty();
        String lower = channel.toLowerCase();
        String ns = lower.contains(":") ? lower.substring(0, lower.indexOf(':')) : lower;
        String n = ForbiddenModRegistry.normalize(ns);
        if (n.isEmpty()) return Optional.empty();
        Definition d = channelExact.get(n);
        if (d != null) return Optional.of(new Match(d, channel, "exact-channel"));
        d = identifiers.get(n);
        if (d != null) return Optional.of(new Match(d, channel, "exact-namespace"));
        // Full-string normalized fallback (namespaced "modid:main").
        Definition d2 = identifiers.get(ForbiddenModRegistry.normalize(lower));
        if (d2 != null) return Optional.of(new Match(d2, channel, "normalized-channel"));
        return Optional.empty();
    }

    public static List<String> allowedByDefault() {
        return List.of("sodium", "lithium", "iris", "ferrite", "immediatelyfast", "entityculling");
    }
}
