package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.BehaviorDefinition;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModule;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorObservation;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.PlayerBehaviorSession;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.HashMap;
import java.util.Map;

public final class SpeedDetector implements BehaviorModule {
    private final BehaviorDefinition definition;

    public SpeedDetector(boolean enabled) {
        definition = new BehaviorDefinition("speed", "Speed", enabled);
    }

    @Override public BehaviorDefinition definition() { return definition; }

    @Override
    public void observe(BehaviorObservation observation, PlayerBehaviorSession session,
                        BehaviorModuleContext context) {
        if (!(observation instanceof MovementObservation movement)) return;
        SpeedAnalysis analysis = session.speedState().observe(movement, session.flyState().state());
        if (analysis == null) return;
        Map<String, String> fields = new HashMap<>();
        fields.put("rateBlocksPerSecond", Double.toString(analysis.normalizedRate()));
        fields.put("previousRate", Double.toString(analysis.previousRate()));
        fields.put("acceleration", Double.toString(analysis.acceleration()));
        fields.put("elevatedStreak", Integer.toString(analysis.elevatedStreak()));
        fields.put("sprinting", Boolean.toString(movement.context().sprinting()));
        fields.put("speedEffect", Boolean.toString(movement.context().speedEffect()));
        fields.put("slownessEffect", Boolean.toString(movement.context().slownessEffect()));
        SpeedEvidence evidence = new SpeedEvidence("speed", movement.timestampNanos(), movement.playerId(),
                analysis.signalTypes(), movement.horizontalDistance(), analysis.normalizedRate(),
                analysis.previousRate(), analysis.acceleration(), analysis.movementState(), movement.grounded(),
                movement.durationMillis(), analysis.confidence(), fields);
        context.speedEvidenceSink().accept(evidence);
        context.signalSink().accept(new ViolationSignal("speed", movement.playerId(), movement.timestampNanos(),
                analysis.confidence(), "sustained horizontal movement evidence requires review", fields));
    }
}
