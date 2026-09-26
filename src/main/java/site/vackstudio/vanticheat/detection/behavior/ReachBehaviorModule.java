package site.vackstudio.vanticheat.detection.behavior;

import java.util.Map;

public final class ReachBehaviorModule implements BehaviorModule {
    private static final double SUSPICIOUS_DISTANCE = 3.15;
    private static final double STRONG_DISTANCE = 3.50;
    private final BehaviorDefinition definition;

    public ReachBehaviorModule(boolean enabled) {
        definition = new BehaviorDefinition("reach", "Reach", enabled);
    }

    @Override public BehaviorDefinition definition() { return definition; }

    @Override
    public void observe(BehaviorObservation observation, PlayerBehaviorSession session,
                        BehaviorModuleContext context) {
        if (!(observation instanceof CombatObservation combat)) return;
        double distance = combat.distanceToTargetBounds();
        SignalLevel level = combat.uncertain() ? SignalLevel.UNCERTAIN
                : distance >= STRONG_DISTANCE ? SignalLevel.STRONG_SIGNAL
                : distance >= SUSPICIOUS_DISTANCE ? SignalLevel.SUSPICIOUS : SignalLevel.CLEAR;
        String reason = level == SignalLevel.CLEAR ? "within conservative reach envelope"
                : level == SignalLevel.UNCERTAIN ? "reach observation lacks sufficient certainty"
                : "attack distance exceeded conservative reach envelope";
        context.signalSink().accept(new ViolationSignal("reach", combat.playerId(), combat.timestampNanos(),
                level, reason, Map.of("distance", Double.toString(distance),
                "uncertain", Boolean.toString(combat.uncertain()),
                "target", combat.targetId().toString())));
        session.combatState().recordReach(level);
    }
}
