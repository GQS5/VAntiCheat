package com.hexa.vanticheat.checks.player;

import com.hexa.vanticheat.core.VConfig;

import java.util.UUID;

/** Player-category checks (BadPackets/Timer placeholders with honest scope). */
public final class PlayerChecks {

    private final VConfig config;

    public PlayerChecks(VConfig config) { this.config = config; }

    public void purge(UUID id) { }
}
