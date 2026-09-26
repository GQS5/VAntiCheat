package site.vackstudio.vanticheat.detection.behavior;

import java.util.UUID;

public sealed interface BehaviorObservation
        permits MovementObservation, RotationObservation, CombatObservation, InteractionObservation,
        FallDamageObservation, PlacementObservation {
    UUID playerId();
    long timestampNanos();
    ObservationType type();
}
