package site.vackstudio.vanticheat.detection.behavior.movement;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.behavior.BehaviorModuleContext;
import site.vackstudio.vanticheat.detection.behavior.BehaviorRegistry;
import site.vackstudio.vanticheat.detection.behavior.MovementContext;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.Position3d;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;
import site.vackstudio.vanticheat.detection.behavior.ViolationSignal;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedDetectorTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    void normalWalkSprintAndJumpRatesRemainClear() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(move(player, 0, .0, true, normal()));
        registry.observe(move(player, 50, .25, true, normal()));
        registry.observe(move(player, 100, .30, true, sprinting()));
        registry.observe(move(player, 150, .30, false, sprinting()));
        assertTrue(signals.isEmpty());
    }

    @Test
    void sustainedElevatedGroundAndAirRateAggregatesConservatively() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(move(player, 0, 0, true, normal()));
        for (int i = 1; i <= 12; i++) registry.observe(move(player, i * 50L, .5, true, normal()));

        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.UNCERTAIN));
        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.SUSPICIOUS));
        assertTrue(signals.stream().anyMatch(value -> value.level() == SignalLevel.STRONG_SIGNAL));
    }

    @Test
    void modifiersAndExternalVelocitySuppressSpeedEvidence() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(move(player, 0, 0, true, normal()));
        registry.observe(move(player, 50, .8, true, ice()));
        registry.observe(move(player, 100, .3, true, normal()));
        registry.observe(move(player, 150, .8, true, externalVelocity()));
        for (int i = 4; i <= 7; i++) registry.observe(move(player, i * 50L, .8, true, normal()));

        assertTrue(signals.isEmpty());
    }

    @Test
    void timingGapsAndResetDoNotBecomeHighSpeedSamples() {
        CopyOnWriteArrayList<ViolationSignal> signals = new CopyOnWriteArrayList<>();
        BehaviorRegistry registry = registry(signals);
        UUID player = UUID.randomUUID();
        registry.observe(move(player, 0, 0, true, normal()));
        registry.observe(moveWithDuration(player, 300, .8, 300, normal()));
        registry.observe(move(player, 350, .2, true, normal()));
        registry.remove(player);
        registry.observe(move(player, 1000, .8, true, normal()));
        assertTrue(signals.isEmpty());
    }

    private static BehaviorRegistry registry(CopyOnWriteArrayList<ViolationSignal> signals) {
        BehaviorRegistry registry = new BehaviorRegistry(128);
        registry.register(new SpeedDetector(true));
        registry.start(new BehaviorModuleContext(Logger.getAnonymousLogger(), signals::add));
        return registry;
    }

    private static MovementContext normal() { return MovementContext.normal(); }

    private static MovementContext sprinting() {
        return context(false, false, false, false, false, false, false, false,
                false, false, false, false, false, "UNKNOWN", true, false, false, false, false);
    }

    private static MovementContext ice() {
        return context(false, false, false, true, false, false, false, false,
                false, false, false, false, false, "ICE", false, false, false, false, false);
    }

    private static MovementContext externalVelocity() {
        return context(false, false, false, false, false, false, false, false,
                true, false, false, false, false, "UNKNOWN", false, false, false, false, false);
    }

    private static MovementContext context(boolean fluid, boolean climbable, boolean slime, boolean ice,
                                           boolean vehicle, boolean gliding, boolean specialGameMode,
                                           boolean activeEffect, boolean externalVelocity,
                                           boolean teleportReset, boolean uncertain, boolean safeLanding,
                                           boolean fallDamageReducing, String surface, boolean sprinting,
                                           boolean speedEffect, boolean slownessEffect,
                                           boolean correction, boolean timing) {
        return new MovementContext(fluid, climbable, slime, ice, vehicle, gliding, specialGameMode,
                activeEffect, externalVelocity, teleportReset, uncertain, safeLanding,
                fallDamageReducing, surface, sprinting, speedEffect, slownessEffect, correction, timing);
    }

    private static MovementObservation move(UUID player, long millis, double distance,
                                            boolean grounded, MovementContext context) {
        return moveWithDuration(player, millis, distance, 50, context, grounded);
    }

    private static MovementObservation moveWithDuration(UUID player, long millis, double distance,
                                                        long duration, MovementContext context) {
        return moveWithDuration(player, millis, distance, duration, context, true);
    }

    private static MovementObservation moveWithDuration(UUID player, long millis, double distance,
                                                        long duration, MovementContext context, boolean grounded) {
        return new MovementObservation(player, millis * MILLIS,
                new Position3d(0, 64, 0), new Position3d(distance, 64, 0), distance, 0, 0,
                distance, 0, duration, grounded, false, new Position3d(0, 0, 0), context);
    }
}
