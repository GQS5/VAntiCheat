package com.hexa.vanticheat.api;

import java.util.Set;
import java.util.UUID;

/** Immutable player snapshot for external consumers. */
public record PlayerProfileSnapshot(UUID playerId, String playerName, String clientBrand,
                                    Set<String> channels, String matchedClient,
                                    String matchedMod, int confidence) {}
