package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.BehaviorDefinition;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModule;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorObservation;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.PlayerBehaviorSession;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.HashMap;
import java.util.Map;

public final class FlyDetector implements BehaviorModule {
    private final BehaviorDefinition definition;

    public FlyDetector(boolean enabled) {
        definition = new BehaviorDefinition("fly", "Fly", enabled);
    }

    @Override public BehaviorDefinition definition() { return definition; }

    @Override
    public void observe(BehaviorObservation observation, PlayerBehaviorSession session,
                        BehaviorModuleContext context) {
        if (!(observation instanceof MovementObservation movement)) return;
        FlyAnalysis analysis = session.flyState().observe(movement);
        if (analysis == null) return;
        Map<String, String> fields = new HashMap<>();
        fields.put("airborneMillis", Long.toString(analysis.airborneDurationMillis()));
        fields.put("state", analysis.state().name());
        fields.put("anomalyStreak", Integer.toString(analysis.anomalyStreak()));
        fields.put("velocityY", Double.toString(movement.velocity().y()));
        fields.put("contextSpecial", Boolean.toString(movement.context().specialMovement()));
        FlyEvidence evidence = new FlyEvidence("fly", movement.timestampNanos(), movement.playerId(),
                analysis.signalTypes(), analysis.airborneDurationMillis(), movement.deltaY(),
                movement.horizontalDistance(), analysis.state(), analysis.confidence(), fields);
        context.flyEvidenceSink().accept(evidence);
        context.signalSink().accept(new ViolationSignal("fly", movement.playerId(), movement.timestampNanos(),
                analysis.confidence(), "repeated aerial movement evidence requires review", fields));
    }
}
