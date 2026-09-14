package com.hexa.vanticheat.checks;

import com.hexa.vanticheat.core.VConfig;
import org.bukkit.entity.Player;

/** Modular check contract. Implementations must be stateless-ish, fast, non-allocating. */
public interface Check {
    String id();
    String name();
    CheckCategory category();
    /** Max VL before strong escalation (snapshot-backed; no YAML in hot path). */
    default double maxVl(VConfig cfg) {
        return 8.0;
    }
    default boolean enabled(VConfig cfg) {
        return cfg.snapshot().behaviorEnabled;
    }
}
