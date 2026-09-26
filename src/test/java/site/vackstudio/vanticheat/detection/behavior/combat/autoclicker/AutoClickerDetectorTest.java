package site.vackstudio.vanticheat.detection.behavior.combat.autoclicker;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorRegistry;
import site.vackstudio.vanticheat.detection.behavior.BoundingBox3d;
import site.vackstudio.vanticheat.detection.behavior.CombatObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;
import site.vackstudio.vanticheat.detection.behavior.Position3d;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoClickerDetectorTest {
    private static final long MILLIS = 1_000_000L;
    private static final BoundingBox3d TARGET = new BoundingBox3d(-.5, 63, -.5, .5, 65, .5);

    @Test
    void tickQuantizationIsUncertainRatherThanAutoclickerEvidence() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 14; i++) registry.observe(attack(player, i * 50L, 50, false));
        assertTrue(signals.stream().noneMatch(value -> value.level().ordinal() >= SignalLevel.SUSPICIOUS.ordinal()));
    }

    @Test
    void irregularAndChangingCadenceRemainsBelowSuspicious() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        long[] intervals = {37, 61, 44, 83, 52, 91, 46, 72, 39, 67, 58, 86};
        long timestamp = 0;
        for (long interval : intervals) {
            timestamp += interval;
            registry.observe(attack(player, timestamp, interval, false));
        }
        assertTrue(signals.stream().noneMatch(value -> value.level().ordinal() >= SignalLevel.SUSPICIOUS.ordinal()));
    }

    @Test
    void persistentNonQuantizedRegularCadenceProducesStructuredEvidence() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 16; i++) registry.observe(attack(player, i * 37L, 37, false));
        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.SUSPICIOUS));
        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.STRONG_SIGNAL));
    }

    @Test
    void uncertainCombatAndObservationGapsDoNotAccumulateConfidence() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 8; i++) registry.observe(attack(player, i * 37L, 37, false));
        registry.observe(attack(player, 1_000, 0, true));
        for (int i = 9; i < 16; i++) registry.observe(attack(player, 1_000 + i * 37L, 37, false));
        assertTrue(signals.stream().noneMatch(value -> value.level() == SignalLevel.STRONG_SIGNAL));
    }

    @Test
    void playersHaveIndependentCadenceState() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            registry.observe(attack(first, i * 37L, 37, false));
            registry.observe(attack(second, i * 71L, 71, false));
        }
        assertTrue(signals.stream().allMatch(value -> value.playerId().equals(first)
                || value.playerId().equals(second)));
    }

    private static BehaviorRegistry registry(CopyOnWriteArrayList<ViolationSignal> signals) {
        BehaviorRegistry registry = new BehaviorRegistry(128);
        registry.register(new AutoClickerDetector(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signals::add));
        return registry;
    }

    private static CombatObservation attack(UUID player, long millis, long interval, boolean uncertain) {
        UUID target = UUID.nameUUIDFromBytes((player + ":target").getBytes());
        Position3d attacker = new Position3d(0, 64, 0);
        return new CombatObservation(player, millis * MILLIS, target, attacker,
                new Position3d(0, 65.6, 0), new Position3d(0, 64, 0), TARGET, 2,
                interval, true, uncertain, 1.0f, false, false, false, false);
    }
}
