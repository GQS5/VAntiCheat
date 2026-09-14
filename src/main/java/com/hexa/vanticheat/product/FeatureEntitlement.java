package com.hexa.vanticheat.product;

/** Feature entitlements by edition (§32). Defaults all-on for single-server use. */
public enum FeatureEntitlement {
    CORE, CLIENT_SECURITY, BEHAVIOR, ANTI_XRAY, ANTI_ESP, ADVANCED_SIMULATION;

    public static java.util.Set<FeatureEntitlement> all() {
        return java.util.EnumSet.allOf(FeatureEntitlement.class);
    }
}
