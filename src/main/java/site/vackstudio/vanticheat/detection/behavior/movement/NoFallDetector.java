package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.BehaviorDefinition;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModule;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorObservation;
import site.vackstudio.vanticheat.detection.behavior.FallDamageObservation;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.PlayerBehaviorSession;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.HashMap;
import java.util.Map;

public final class NoFallDetector implements BehaviorModule {
    private final BehaviorDefinition definition;

    public NoFallDetector(boolean enabled) {
        definition = new BehaviorDefinition("no-fall", "NoFall", enabled);
    }

    @Override public BehaviorDefinition definition() { return definition; }

    @Override
    public void observe(BehaviorObservation observation, PlayerBehaviorSession session,
                        BehaviorModuleContext context) {
        NoFallAnalysis analysis;
        if (observation instanceof MovementObservation movement) {
            analysis = session.noFallState().observeMovement(movement);
        } else if (observation instanceof FallDamageObservation impact) {
            analysis = session.noFallState().observeImpact(impact);
        } else {
            return;
        }
        if (analysis == null) return;
        Map<String, String> fields = new HashMap<>();
        fields.put("fallDistance", Double.toString(analysis.estimatedFallDistance()));
        fields.put("startY", Double.toString(analysis.startY()));
        fields.put("lowestY", Double.toString(analysis.lowestY()));
        fields.put("landingY", Double.toString(analysis.landingY()));
        fields.put("expectedImpact", Boolean.toString(analysis.expectedImpact()));
        fields.put("observedImpact", Boolean.toString(analysis.observedImpact()));
        fields.put("mismatchStreak", Integer.toString(analysis.mismatchStreak()));
        NoFallEvidence evidence = new NoFallEvidence("no-fall", analysis.timestampNanos(), analysis.playerId(),
                analysis.signalTypes(), analysis.fallDurationMillis(), analysis.estimatedFallDistance(),
                analysis.startY(), analysis.lowestY(), analysis.landingY(), analysis.state(),
                analysis.expectedImpact(), analysis.observedImpact(), analysis.confidence(), fields);
        context.noFallEvidenceSink().accept(evidence);
        context.signalSink().accept(new ViolationSignal("no-fall", analysis.playerId(), analysis.timestampNanos(),
                analysis.confidence(), "fall consequence evidence requires review", fields));
    }
}
