package com.hexa.vanticheat.punishment;

import com.hexa.vanticheat.core.VConfig;

/** Maps detection categories to actions via config punishments.*. Gated by confidence + profile. */
public final class PunishmentPolicy {

    private final VConfig config;

    public PunishmentPolicy(VConfig config) {
        this.config = config;
    }

    public Action actionFor(String category, Action def) {
        String base = "punishments." + category + ".action";
        return Action.parse(config.getString(base, def.name()), def);
    }

    public String durationFor(String category, String def) {
        return config.getString("punishments." + category + ".duration", def);
    }

    /** Confidence-gated escalation with profile bonus (spec §6/§17). Weak singles → ALERT only. */
    public Action gateByConfidence(Action configured, int confidence) {
        if (configured == Action.NONE || configured == Action.ALERT) return configured;
        int gate = 0;
        try { gate = config.profile().gateBonus(); } catch (Throwable ignored) {}
        int c = confidence + gate;
        if (c >= 95) return configured;
        if (c >= 80) return configured == Action.BAN ? Action.TEMPBAN : configured;
        if (c >= 60) return Action.WARN;
        return Action.ALERT;
    }

    /** Diversity gate (spec §17): severe actions need N independent signal families. */
    public boolean diversityAllows(Action action, int diversity) {
        boolean req;
        int min;
        try {
            req = config.getBoolean("punishment.require-independent-signals", true);
            min = config.getInt("punishment.minimum-independent-signals", 2);
        } catch (Throwable t) {
            req = true;
            min = 2;
        }
        return diversityAllows(action, diversity, req, min);
    }

    /** Pure core: headless-testable, no Bukkit access. */
    public static boolean diversityAllows(Action action, int diversity, boolean require, int min) {
        if (action == Action.BAN || action == Action.TEMPBAN) {
            if (!require) return true;
            // Forbidden-client CONFIRMED path bypasses diversity (single strong proof is enough).
            return diversity >= min;
        }
        return true;
    }
}
