package site.vackstudio.vanticheat.detection.behavior;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import site.vackstudio.vanticheat.detection.behavior.combat.CombatEvidence;
import site.vackstudio.vanticheat.detection.behavior.combat.autoclicker.AutoClickerEvidence;
import site.vackstudio.vanticheat.detection.behavior.movement.FlyEvidence;
import site.vackstudio.vanticheat.detection.behavior.movement.NoFallEvidence;
import site.vackstudio.vanticheat.detection.behavior.movement.SpeedEvidence;
import site.vackstudio.vanticheat.detection.behavior.placement.ScaffoldEvidence;

public record BehaviorModuleContext(Logger logger, Consumer<ViolationSignal> signalSink,
                                     Consumer<CombatEvidence> evidenceSink,
                                     Consumer<AutoClickerEvidence> autoClickerEvidenceSink,
                                     Consumer<FlyEvidence> flyEvidenceSink,
                                    Consumer<NoFallEvidence> noFallEvidenceSink,
                                    Consumer<SpeedEvidence> speedEvidenceSink,
                                    Consumer<ScaffoldEvidence> scaffoldEvidenceSink) {
    public BehaviorModuleContext(Logger logger, Consumer<ViolationSignal> signalSink) {
        this(logger, signalSink, evidence -> { }, evidence -> { }, evidence -> { }, evidence -> { }, evidence -> { }, evidence -> { });
    }

    public BehaviorModuleContext(Logger logger, Consumer<ViolationSignal> signalSink,
                                 Consumer<CombatEvidence> evidenceSink) {
        this(logger, signalSink, evidenceSink, evidence -> { }, evidence -> { }, evidence -> { }, evidence -> { }, evidence -> { });
    }

    public BehaviorModuleContext(Logger logger, Consumer<ViolationSignal> signalSink,
                                 Consumer<CombatEvidence> evidenceSink,
                                 Consumer<FlyEvidence> flyEvidenceSink) {
        this(logger, signalSink, evidenceSink, evidence -> { }, flyEvidenceSink, evidence -> { }, evidence -> { }, evidence -> { });
    }

    public BehaviorModuleContext(Logger logger, Consumer<ViolationSignal> signalSink,
                                 Consumer<CombatEvidence> evidenceSink,
                                 Consumer<FlyEvidence> flyEvidenceSink,
                                 Consumer<NoFallEvidence> noFallEvidenceSink) {
        this(logger, signalSink, evidenceSink, evidence -> { }, flyEvidenceSink, noFallEvidenceSink, evidence -> { }, evidence -> { });
    }

    public BehaviorModuleContext(Logger logger, Consumer<ViolationSignal> signalSink,
                                 Consumer<CombatEvidence> evidenceSink,
                                 Consumer<FlyEvidence> flyEvidenceSink,
                                 Consumer<NoFallEvidence> noFallEvidenceSink,
                                 Consumer<SpeedEvidence> speedEvidenceSink) {
        this(logger, signalSink, evidenceSink, evidence -> { }, flyEvidenceSink, noFallEvidenceSink,
                speedEvidenceSink, evidence -> { });
    }

    public BehaviorModuleContext {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(signalSink, "signalSink");
        Objects.requireNonNull(evidenceSink, "evidenceSink");
        Objects.requireNonNull(autoClickerEvidenceSink, "autoClickerEvidenceSink");
        Objects.requireNonNull(flyEvidenceSink, "flyEvidenceSink");
        Objects.requireNonNull(noFallEvidenceSink, "noFallEvidenceSink");
        Objects.requireNonNull(speedEvidenceSink, "speedEvidenceSink");
        Objects.requireNonNull(scaffoldEvidenceSink, "scaffoldEvidenceSink");
    }
}
