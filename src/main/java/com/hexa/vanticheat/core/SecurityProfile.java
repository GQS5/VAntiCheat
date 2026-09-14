package com.hexa.vanticheat.core;

/** Security profiles (spec §6). Aggression scales weights/thresholds; paranoid collects more but still gates punishment. */
public enum SecurityProfile {
    LENIENT, STANDARD, HARDCORE, PARANOID;

    public static SecurityProfile parse(String s) {
        if (s == null) return STANDARD;
        return switch (s.trim().toLowerCase()) {
            case "lenient" -> LENIENT;
            case "hardcore" -> HARDCORE;
            case "paranoid" -> PARANOID;
            default -> STANDARD;
        };
    }

    /** Multiplier applied to confidence contributions. */
    public double weightMultiplier() {
        return switch (this) {
            case LENIENT -> 0.7;
            case STANDARD -> 1.0;
            case HARDCORE -> 1.25;
            case PARANOID -> 1.1;
        };
    }

    /** Extra confidence required before WARN+. */
    public int gateBonus() {
        return switch (this) {
            case LENIENT -> 15;
            case STANDARD -> 0;
            case HARDCORE -> -5;
            case PARANOID -> 0;
        };
    }
}
