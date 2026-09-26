package site.vackstudio.vanticheat.detection.behavior.combat.autoclicker;

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

public final class AutoClickerDetector implements BehaviorModule {
    private final BehaviorDefinition definition;

    public AutoClickerDetector(boolean enabled) {
        definition = new BehaviorDefinition("autoclicker", "AutoClicker", enabled);
    }

    @Override public BehaviorDefinition definition() { return definition; }

    @Override
    public void observe(BehaviorObservation observation, PlayerBehaviorSession session,
                        BehaviorModuleContext context) {
        if (!(observation instanceof CombatObservation combat)) return;
        AutoClickerAnalysis analysis = session.autoClickerState().observe(combat, session.combat().snapshot());
        if (analysis == null) return;
        Map<String, String> fields = new HashMap<>();
        fields.put("sampleCount", Integer.toString(analysis.sampleCount()));
        fields.put("meanIntervalMillis", Double.toString(analysis.meanIntervalMillis()));
        fields.put("intervalVarianceMillisSquared", Double.toString(analysis.intervalVarianceMillisSquared()));
        fields.put("repeatedIntervalCount", Integer.toString(analysis.repeatedIntervalCount()));
        fields.put("burstLength", Integer.toString(analysis.burstLength()));
        fields.put("periodicity", Double.toString(analysis.periodicity()));
        fields.put("attackCadence", Double.toString(analysis.attackCadence()));
        fields.put("timingQuality", analysis.timingQuality().name());
        fields.put("target", combat.targetId().toString());
        AutoClickerEvidence evidence = new AutoClickerEvidence("autoclicker", combat.timestampNanos(), combat.playerId(),
                analysis.sampleCount(), analysis.meanIntervalMillis(), analysis.intervalVarianceMillisSquared(),
                analysis.repeatedIntervalCount(), analysis.burstLength(), analysis.periodicity(),
                analysis.attackCadence(), analysis.timingQuality(), analysis.signalTypes(), analysis.confidence(), fields);
        context.autoClickerEvidenceSink().accept(evidence);
        context.signalSink().accept(new ViolationSignal("autoclicker", combat.playerId(), combat.timestampNanos(),
                analysis.confidence(), "observable attack cadence requires review", fields));
    }
}
