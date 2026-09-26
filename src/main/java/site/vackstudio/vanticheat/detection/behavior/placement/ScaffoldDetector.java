package site.vackstudio.vanticheat.detection.behavior.placement;

import site.vackstudio.vanticheat.detection.behavior.BehaviorDefinition;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModule;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorObservation;
import site.vackstudio.vanticheat.detection.behavior.PlacementObservation;
import site.vackstudio.vanticheat.detection.behavior.PlayerBehaviorSession;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.HashMap;
import java.util.Map;

public final class ScaffoldDetector implements BehaviorModule {
    private final BehaviorDefinition definition;

    public ScaffoldDetector(boolean enabled) {
        definition = new BehaviorDefinition("scaffold", "Scaffold", enabled);
    }

    @Override public BehaviorDefinition definition() { return definition; }

    @Override
    public void observe(BehaviorObservation observation, PlayerBehaviorSession session,
                        BehaviorModuleContext context) {
        if (!(observation instanceof PlacementObservation placement)) return;
        ScaffoldAnalysis analysis = session.scaffoldState().observe(placement,
                session.rotation().snapshot(), session.movement().snapshot(), session.flyState().state());
        if (analysis == null) return;
        Map<String, String> fields = new HashMap<>();
        fields.put("intervalMillis", Long.toString(analysis.intervalMillis()));
        fields.put("facingDot", Double.toString(analysis.facingDot()));
        fields.put("supportAdjacent", Boolean.toString(analysis.supportAdjacent()));
        fields.put("supportContinues", Boolean.toString(analysis.supportContinues()));
        fields.put("sequenceLength", Integer.toString(analysis.sequenceLength()));
        fields.put("sneaking", Boolean.toString(placement.sneaking()));
        ScaffoldEvidence evidence = new ScaffoldEvidence("scaffold", placement.timestampNanos(), placement.playerId(),
                analysis.signalTypes(), format(placement.placedPosition()), format(placement.supportPosition()),
                placement.face(), format(placement.playerPosition()), placement.yaw(), placement.pitch(),
                analysis.intervalMillis(), analysis.movementState(), placement.grounded(), placement.sneaking(),
                analysis.sequenceLength(), analysis.facingDot(), analysis.confidence(), fields);
        context.scaffoldEvidenceSink().accept(evidence);
        context.signalSink().accept(new ViolationSignal("scaffold", placement.playerId(), placement.timestampNanos(),
                analysis.confidence(), "repeated placement evidence requires review", fields));
    }

    private static String format(site.vackstudio.vanticheat.detection.behavior.Position3d position) {
        return position.x() + "," + position.y() + "," + position.z();
    }
}
