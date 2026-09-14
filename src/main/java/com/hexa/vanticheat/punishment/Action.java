package com.hexa.vanticheat.punishment;

/** Configurable enforcement actions. Detectors never execute these directly. */
public enum Action {
    NONE, ALERT, WARN, KICK, TEMPBAN, BAN;

    public static Action parse(String s, Action def) {
        if (s == null) return def;
        try { return Action.valueOf(s.trim().toUpperCase()); } catch (Exception e) { return def; }
    }
}
