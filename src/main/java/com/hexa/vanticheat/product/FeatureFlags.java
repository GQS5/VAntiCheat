package com.hexa.vanticheat.product;

import com.hexa.vanticheat.core.VConfig;

import java.util.Set;

/**
 * Feature flags (§32): config gates without engine duplication.
 * Keys: features.<core|client-security|behavior|anti-xray|anti-esp|advanced-simulation>.
 */
public final class FeatureFlags {
    private final VConfig config;
    private final LicenseProvider license;

    public FeatureFlags(VConfig config, LicenseProvider license) {
        this.config = config;
        this.license = license;
    }

    public boolean enabled(FeatureEntitlement f) {
        if (!license.entitlements().contains(f)) return false;
        return switch (f) {
            case CORE -> config.getBoolean("features.core", true);
            case CLIENT_SECURITY -> config.getBoolean("features.client-security", true);
            case BEHAVIOR -> config.getBoolean("features.behavior", true);
            case ANTI_XRAY -> config.getBoolean("features.anti-xray", true);
            case ANTI_ESP -> config.getBoolean("features.anti-esp", true);
            case ADVANCED_SIMULATION -> config.getBoolean("features.advanced-simulation", true);
        };
    }
}
