package com.hexa.vanticheat.detection;

import com.hexa.vanticheat.core.ConfigSnapshot;
import com.hexa.vanticheat.core.LagContext;
import com.hexa.vanticheat.simulation.InvestigationState;
import com.hexa.vanticheat.version.ClientVersionProfile;
import com.hexa.vanticheat.version.ServerProfile;

/**
 * Shared detector lifecycle (§15): FAST PRECHECK → COLLECT → VALIDATE →
 * ANALYZE → EMIT. Hot path must early-return; expensive work only on trigger.
 * Cost contract documented per detector: hot O(1), triggered snapshot, deep sim.
 */
public interface Detector {
    String id();

    /** Hot path: pure cheap reject. Must not allocate, block, or read config. */
    default boolean precheck() { return true; }

    /** Cost tier for docs/telemetry. */
    default String cost() { return "hot=O(1) trigger=snapshot deep=targeted-sim"; }

    /** Shared context carrier — built once per trigger, not per event. */
    record DetectionContext(ConfigSnapshot config, LagContext lag, ServerProfile server,
                            ClientVersionProfile client, InvestigationState investigation) {}
}
