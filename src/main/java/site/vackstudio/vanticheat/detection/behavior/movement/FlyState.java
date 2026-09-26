package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.EnumSet;

public final class FlyState {
    private final int capacity;
    private MovementObservation previous;
    private FlyMovementState state = FlyMovementState.UNCERTAIN;
    private long airborneSince;
    private int hoverStreak;
    private int horizontalStreak;
    private int reversalCount;
    private int anomalyStreak;
    private SignalLevel lastReported;

    public FlyState(int capacity) {
        if (capacity < 8) throw new IllegalArgumentException("capacity must be at least 8");
        this.capacity = capacity;
    }

    public synchronized FlyAnalysis observe(MovementObservation current) {
        if (previous == null) {
            previous = current;
            state = current.grounded() ? FlyMovementState.GROUNDED : FlyMovementState.AIRBORNE;
            airborneSince = current.grounded() ? 0 : current.timestampNanos();
            return null;
        }
        if (current.timestampNanos() <= previous.timestampNanos()
                || current.durationMillis() > 250) {
            state = FlyMovementState.UNCERTAIN;
            clearCounters();
            previous = current;
            return null;
        }
        if (current.context().specialMovement()) {
            state = FlyMovementState.SPECIAL_MOVEMENT;
            clearCounters();
            airborneSince = current.grounded() ? 0 : current.timestampNanos();
            previous = current;
            lastReported = null;
            return null;
        }
        if (current.context().uncertain()) {
            state = FlyMovementState.UNCERTAIN;
            clearCounters();
            previous = current;
            lastReported = null;
            return null;
        }
        if (current.grounded()) {
            state = previous.grounded() ? FlyMovementState.GROUNDED : FlyMovementState.LANDING;
            clearCounters();
            airborneSince = 0;
            previous = current;
            lastReported = null;
            return null;
        }
        if (previous.grounded() || airborneSince == 0) airborneSince = current.timestampNanos();
        long airborneMillis = Math.max(0, (current.timestampNanos() - airborneSince) / 1_000_000);
        double horizontalSpeed = current.durationMillis() == 0 ? 0
                : current.horizontalDistance() * 1000.0 / current.durationMillis();
        if (Math.abs(current.deltaY()) < .015 && Math.abs(current.velocity().y()) < .04
                && airborneMillis >= 300) hoverStreak++;
        else hoverStreak = 0;
        if (horizontalSpeed > 9.0 && !current.context().externalVelocity()) horizontalStreak++;
        else horizontalStreak = 0;
        if (state == FlyMovementState.FALLING && current.deltaY() > .05) reversalCount++;
        if (current.deltaY() > .02) state = FlyMovementState.RISING;
        else if (current.deltaY() < -.02) state = FlyMovementState.FALLING;
        else state = FlyMovementState.AIRBORNE;

        EnumSet<FlySignalType> signals = EnumSet.noneOf(FlySignalType.class);
        if (hoverStreak >= 6) signals.add(FlySignalType.HOVER_ANOMALY);
        if (airborneMillis >= 1800) signals.add(FlySignalType.AIR_TIME_ANOMALY);
        if (reversalCount >= 2) signals.add(FlySignalType.VERTICAL_TRAJECTORY);
        if (horizontalStreak >= 4) signals.add(FlySignalType.HORIZONTAL_AIR_MOVEMENT);
        if (signals.isEmpty()) anomalyStreak = 0;
        else anomalyStreak = Math.min(capacity, anomalyStreak + 1);
        SignalLevel confidence = confidence(signals, airborneMillis);
        previous = current;
        if (signals.isEmpty() || confidence == lastReported) return null;
        lastReported = confidence;
        return new FlyAnalysis(current, signals, airborneMillis, state, confidence, anomalyStreak);
    }

    private SignalLevel confidence(EnumSet<FlySignalType> signals, long airborneMillis) {
        if (signals.isEmpty()) return SignalLevel.CLEAR;
        if (signals.size() >= 3 && anomalyStreak >= 6 && airborneMillis >= 2500) {
            return SignalLevel.STRONG_SIGNAL;
        }
        if (anomalyStreak >= 3 || signals.size() >= 2) return SignalLevel.SUSPICIOUS;
        return SignalLevel.UNCERTAIN;
    }

    private void clearCounters() {
        hoverStreak = 0;
        horizontalStreak = 0;
        reversalCount = 0;
        anomalyStreak = 0;
    }

    public synchronized void reset() {
        previous = null;
        state = FlyMovementState.UNCERTAIN;
        airborneSince = 0;
        clearCounters();
        lastReported = null;
    }

    public synchronized FlyMovementState state() { return state; }
}
