package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.MovementContext;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.EnumSet;

public final class SpeedState {
    private MovementObservation previous;
    private double previousRate;
    private int elevatedStreak;
    private int velocityGrace;
    private SignalLevel lastReported;

    public synchronized SpeedAnalysis observe(MovementObservation current, FlyMovementState movementState) {
        if (previous == null) {
            previous = current;
            return null;
        }
        long elapsed = current.durationMillis();
        if (current.timestampNanos() <= previous.timestampNanos() || elapsed < 10 || elapsed > 250
                || current.context().timingUncertain() || current.context().uncertain()
                || current.context().positionCorrection()) {
            resetFor(current);
            return null;
        }
        if (environmentContext(current.context())) {
            resetFor(current);
            return null;
        }
        if (current.context().externalVelocity() || velocityGrace > 0) {
            if (current.context().externalVelocity()) velocityGrace = 4;
            else velocityGrace--;
            elevatedStreak = 0;
            previousRate = 0;
            previous = current;
            lastReported = null;
            return null;
        }
        double rate = current.horizontalDistance() * 1000.0 / elapsed;
        double acceleration = previousRate <= 0.0 ? 0.0 : rate - previousRate;
        double threshold = current.grounded()
                ? current.context().sprinting() ? 8.0 : 6.5
                : current.context().sprinting() ? 8.5 : 7.5;
        boolean elevated = rate > threshold;
        if (elevated) elevatedStreak++;
        else elevatedStreak = 0;
        EnumSet<SpeedSignalType> signals = EnumSet.noneOf(SpeedSignalType.class);
        if (elevated) signals.add(current.grounded() ? SpeedSignalType.GROUND_SPEED_ANOMALY
                : SpeedSignalType.AIR_SPEED_ANOMALY);
        if (elevatedStreak >= 3) signals.add(SpeedSignalType.SUSTAINED_SPEED_ANOMALY);
        if (elevated && elevatedStreak >= 2 && acceleration > 3.0) {
            signals.add(SpeedSignalType.ACCELERATION_ANOMALY);
        }
        SignalLevel confidence = confidence(signals);
        previous = current;
        previousRate = rate;
        if (signals.isEmpty() || confidence == lastReported) return null;
        lastReported = confidence;
        return new SpeedAnalysis(current, signals, rate, previousRate - acceleration, acceleration,
                movementState, confidence, elevatedStreak);
    }

    private SignalLevel confidence(EnumSet<SpeedSignalType> signals) {
        if (signals.isEmpty()) return SignalLevel.CLEAR;
        if (elevatedStreak >= 10 && signals.contains(SpeedSignalType.SUSTAINED_SPEED_ANOMALY)) {
            return SignalLevel.STRONG_SIGNAL;
        }
        if (elevatedStreak >= 4) return SignalLevel.SUSPICIOUS;
        return SignalLevel.UNCERTAIN;
    }

    private static boolean environmentContext(MovementContext context) {
        return context.fluid() || context.climbable() || context.slime() || context.ice()
                || context.vehicle() || context.gliding() || context.specialGameMode()
                || context.activeEffect() || context.teleportReset();
    }

    private void resetFor(MovementObservation current) {
        previous = current;
        previousRate = 0;
        elevatedStreak = 0;
        velocityGrace = 0;
        lastReported = null;
    }

    public synchronized void reset() {
        previous = null;
        previousRate = 0;
        elevatedStreak = 0;
        velocityGrace = 0;
        lastReported = null;
    }
}
