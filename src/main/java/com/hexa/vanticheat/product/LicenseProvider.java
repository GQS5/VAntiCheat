package com.hexa.vanticheat.product;

import java.util.Set;

/**
 * Licensing interface (§33): modular, testable, no DRM now. Local operation
 * never requires internet (§44). Detectors check entitlements via FeatureFlags,
 * never this provider directly.
 */
public interface LicenseProvider {
    LicenseState state();
    Set<FeatureEntitlement> entitlements();
    /** Local stub: licensed with everything (single-server default). */
    static LicenseProvider local() {
        return new LicenseProvider() {
            @Override public LicenseState state() { return LicenseState.LICENSED; }
            @Override public Set<FeatureEntitlement> entitlements() { return FeatureEntitlement.all(); }
        };
    }
}
