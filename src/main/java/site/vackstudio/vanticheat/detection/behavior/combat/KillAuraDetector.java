package site.vackstudio.vanticheat.detection.behavior.combat;

import site.vackstudio.vanticheat.detection.behavior.BehaviorDefinition;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModule;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorObservation;
import site.vackstudio.vanticheat.detection.behavior.CombatObservation;
import site.vackstudio.vanticheat.detection.behavior.PlayerBehaviorSession;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.HashMap;
import java.util.Map;

public final class KillAuraDetector implements BehaviorModule {
    private final BehaviorDefinition definition;

    public KillAuraDetector(boolean enabled) {
        definition = new BehaviorDefinition("killaura", "KillAura", enabled);
    }

    @Override public BehaviorDefinition definition() { return definition; }

    @Override
    public void observe(BehaviorObservation observation, PlayerBehaviorSession session,
                        BehaviorModuleContext context) {
        if (!(observation instanceof CombatObservation combat)) return;
        CombatResult result = session.combatState().observe(combat, session.rotation().snapshot());
        if (result.confidence() == CombatConfidence.CLEAR) return;

        CombatEvidence evidence = evidence(combat, result);
        context.evidenceSink().accept(evidence);
        Map<String, String> fields = new HashMap<>();
        fields.put("confidence", result.confidence().name());
        fields.put("signals", evidence.signalTypes().toString());
        fields.put("intervalMillis", Long.toString(result.sequence().intervalMillis()));
        fields.put("rotationDelta", Double.toString(result.sequence().rotationDelta()));
        fields.put("sequenceIndex", Long.toString(result.sequence().index()));
        context.signalSink().accept(new ViolationSignal("killaura", combat.playerId(), combat.timestampNanos(),
                toSignalLevel(result.confidence()), "repeated combat evidence requires review", fields));
    }

    private CombatEvidence evidence(CombatObservation combat, CombatResult result) {
        return new CombatEvidence("killaura", combat.timestampNanos(), combat.playerId(), combat.targetId(),
                result.sequence().intervalMillis(), combat.distanceToTargetBounds(),
                result.sequence().rotationDelta(), result.sequence().index(), result.sequence().signals(),
                result.confidence(), Map.of("repeatedTiming", Integer.toString(result.repeatedTiming()),
                "repeatedTargetSwitches", Integer.toString(result.repeatedTargetSwitches()),
                "repeatedRotations", Integer.toString(result.repeatedRotations()),
                "repeatedReach", Integer.toString(result.repeatedReach()),
                "attackStrength", Float.toString(combat.attackStrength())));
    }

    private SignalLevel toSignalLevel(CombatConfidence confidence) {
        return switch (confidence) {
            case CLEAR -> SignalLevel.CLEAR;
            case UNCERTAIN -> SignalLevel.UNCERTAIN;
            case SUSPICIOUS -> SignalLevel.SUSPICIOUS;
            case STRONG_SIGNAL -> SignalLevel.STRONG_SIGNAL;
        };
    }
}
