package site.vackstudio.vanticheat.detection.behavior.combat;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorRegistry;
import site.vackstudio.vanticheat.detection.behavior.BoundingBox3d;
import site.vackstudio.vanticheat.detection.behavior.CombatObservation;
import site.vackstudio.vanticheat.detection.behavior.Position3d;
import site.vackstudio.vanticheat.detection.behavior.RotationObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillAuraDetectorTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    void oneFastAttackRemainsUncertain() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        registry.observe(combat(player, target, 0, 0, 3.0, true, false));
        registry.observe(combat(player, target, 100 * MILLIS, 100, 3.0, true, false));

        assertEquals(SignalLevel.UNCERTAIN, signals.stream()
                .filter(signal -> signal.detectorId().equals("killaura"))
                .findFirst().orElseThrow().level());
    }

    @Test
    void repeatedIndependentSignalsBecomeStrongWithoutEnforcement() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<CombatEvidence> evidence = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = new BehaviorRegistry(16);
        registry.register(new site.vackstudio.vanticheat.detection.behavior.ReachBehaviorModule(true));
        registry.register(new KillAuraDetector(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signals::add, evidence::add));
        UUID player = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            long timestamp = (i + 1) * 100 * MILLIS;
            registry.observe(new RotationObservation(player, timestamp - MILLIS, 20, 0, 20, 0));
            registry.observe(combat(player, i % 2 == 0 ? first : second, timestamp, 100, 3.6, true, false));
        }

        assertTrue(signals.stream().anyMatch(signal -> signal.detectorId().equals("killaura")
                && signal.level() == SignalLevel.STRONG_SIGNAL));
        assertTrue(evidence.stream().anyMatch(value -> value.confidence() == CombatConfidence.STRONG_SIGNAL));
    }

    @Test
    void movementOrKnockbackKeepsCombinedEvidenceUncertain() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            long timestamp = (i + 1) * 100 * MILLIS;
            registry.observe(new RotationObservation(player, timestamp - MILLIS, 20, 0, 20, 0));
            registry.observe(combat(player, target, timestamp, 100, 3.6, true, true));
        }

        assertTrue(signals.stream().filter(signal -> signal.detectorId().equals("killaura"))
                .allMatch(signal -> signal.level() == SignalLevel.UNCERTAIN));
    }

    @Test
    void disconnectClearsCombatStateAndPlayersRemainIsolated() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<CombatEvidence> evidence = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = new BehaviorRegistry(16);
        registry.register(new site.vackstudio.vanticheat.detection.behavior.ReachBehaviorModule(true));
        registry.register(new KillAuraDetector(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signals::add, evidence::add));
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        registry.observe(combat(player, target, 0, 0, 3.0, true, false));
        registry.observe(combat(other, target, 0, 0, 3.0, true, false));
        registry.observe(combat(player, target, 100 * MILLIS, 100, 3.0, true, false));
        int evidenceBeforeReconnect = evidence.size();

        registry.remove(player);
        registry.observe(combat(player, target, 200 * MILLIS, 0, 3.0, true, false));

        assertEquals(2, registry.sessionCount());
        assertEquals(evidenceBeforeReconnect, evidence.size());
    }

    @Test
    void combatWindowIsBounded() {
        CombatState state = new CombatState(4);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            state.observe(combat(player, UUID.randomUUID(), i * 100 * MILLIS, 100,
                    3.0, true, false), java.util.List.of());
        }
        assertEquals(4, state.attackCount());
    }

    private static BehaviorRegistry registry(CopyOnWriteArrayList<ViolationSignal> signals) {
        BehaviorRegistry registry = new BehaviorRegistry(16);
        registry.register(new site.vackstudio.vanticheat.detection.behavior.ReachBehaviorModule(true));
        registry.register(new KillAuraDetector(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signals::add));
        return registry;
    }

    private static CombatObservation combat(UUID player, UUID target, long timestamp, long interval,
                                             double distance, boolean lineOfSight, boolean knockback) {
        Position3d eye = new Position3d(0, 65.62, 0);
        BoundingBox3d bounds = new BoundingBox3d(distance, 64, -.3, distance + .6, 65.8, .3);
        return new CombatObservation(player, timestamp, target, new Position3d(0, 64, 0), eye,
                new Position3d(distance, 64, 0), bounds, distance, interval, lineOfSight, false,
                1.0f, false, false, knockback, false);
    }
}
