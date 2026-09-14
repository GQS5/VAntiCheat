package com.hexa.vanticheat.core;

/**
 * Immutable precomputed configuration (1.2 hot-path engineering).
 * Built once per reload; hot path reads plain fields — zero string lookups,
 * zero YAML traversal, zero allocation. REGION/ENTITY threads read safely
 * (final fields, volatile swap in VConfig).
 */
public final class ConfigSnapshot {
    public final boolean behaviorEnabled;
    public final boolean reachEnabled;
    public final boolean killauraEnabled;
    public final boolean flyEnabled;
    public final boolean speedEnabled;
    public final boolean nofallEnabled;
    public final boolean stepEnabled;
    public final boolean fastplaceEnabled;
    public final boolean fastbreakEnabled;
    public final boolean antixrayEnabled;
    public final boolean antiespEnabled;
    public final boolean clientSecurityEnabled;
    public final boolean modlistEnabled;
    public final boolean antispoofEnabled;
    public final boolean punishmentEnabled;
    public final boolean alertsEnabled;
    public final double maxReach;
    public final double hiddenRatio;
    public final double rarePerHour;
    public final int hiddenStreak;
    public final double minAvgSpacing;
    public final int minSample;
    public final int espHistorySec;
    public final SecurityProfile profile;
    public final double profileMultiplier;
    public final int profileGateBonus;
    public final boolean requireIndependentSignals;
    public final int minimumIndependentSignals;
    public final int maxEvidencePerPlayer;
    public final boolean profilingEnabled;
    public final int profilingSampleRate;

    public ConfigSnapshot(boolean behaviorEnabled, boolean reachEnabled, boolean killauraEnabled,
                          boolean flyEnabled, boolean speedEnabled, boolean nofallEnabled, boolean stepEnabled,
                          boolean fastplaceEnabled, boolean fastbreakEnabled, boolean antixrayEnabled,
                          boolean antiespEnabled, boolean clientSecurityEnabled, boolean modlistEnabled,
                          boolean antispoofEnabled, boolean punishmentEnabled, boolean alertsEnabled,
                          double maxReach, double hiddenRatio, double rarePerHour, int hiddenStreak,
                          double minAvgSpacing, int minSample, int espHistorySec, SecurityProfile profile,
                          boolean requireIndependentSignals, int minimumIndependentSignals,
                          int maxEvidencePerPlayer, boolean profilingEnabled, int profilingSampleRate) {
        this.behaviorEnabled = behaviorEnabled;
        this.reachEnabled = reachEnabled;
        this.killauraEnabled = killauraEnabled;
        this.flyEnabled = flyEnabled;
        this.speedEnabled = speedEnabled;
        this.nofallEnabled = nofallEnabled;
        this.stepEnabled = stepEnabled;
        this.fastplaceEnabled = fastplaceEnabled;
        this.fastbreakEnabled = fastbreakEnabled;
        this.antixrayEnabled = antixrayEnabled;
        this.antiespEnabled = antiespEnabled;
        this.clientSecurityEnabled = clientSecurityEnabled;
        this.modlistEnabled = modlistEnabled;
        this.antispoofEnabled = antispoofEnabled;
        this.punishmentEnabled = punishmentEnabled;
        this.alertsEnabled = alertsEnabled;
        this.maxReach = maxReach;
        this.hiddenRatio = hiddenRatio;
        this.rarePerHour = rarePerHour;
        this.hiddenStreak = hiddenStreak;
        this.minAvgSpacing = minAvgSpacing;
        this.minSample = minSample;
        this.espHistorySec = espHistorySec;
        this.profile = profile;
        this.profileMultiplier = profile.weightMultiplier();
        this.profileGateBonus = profile.gateBonus();
        this.requireIndependentSignals = requireIndependentSignals;
        this.minimumIndependentSignals = minimumIndependentSignals;
        this.maxEvidencePerPlayer = maxEvidencePerPlayer;
        this.profilingEnabled = profilingEnabled;
        this.profilingSampleRate = Math.max(1, profilingSampleRate);
    }

    public static ConfigSnapshot defaults() {
        return new ConfigSnapshot(true, true, true, true, true, true, true, true, true,
                true, true, true, true, true, true, true, 3.4, 0.75, 12.0, 4, 25.0, 40, 8,
                SecurityProfile.STANDARD, true, 2, 200, false, 20);
    }
}
