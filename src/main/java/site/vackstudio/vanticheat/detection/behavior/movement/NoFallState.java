package site.vackstudio.vanticheat.detection.behavior.movement;

import site.vackstudio.vanticheat.detection.behavior.FallDamageObservation;
import site.vackstudio.vanticheat.detection.behavior.MovementContext;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.SignalLevel;

import java.util.EnumSet;
import java.util.UUID;

public final class NoFallState {
    private final int capacity;
    private MovementObservation previous;
    private NoFallStateKind state = NoFallStateKind.RESET;
    private double startY;
    private double lowestY;
    private long fallStartNanos;
    private long lastImpactNanos;
    private double lastImpactDamage;
    private boolean contextConflict;
    private PendingFall pending;
    private int mismatchStreak;
    private SignalLevel lastReported;

    public NoFallState(int capacity) {
        if (capacity < 4) throw new IllegalArgumentException("capacity must be at least 4");
        this.capacity = capacity;
    }

    public synchronized NoFallAnalysis observeMovement(MovementObservation current) {
        if (previous == null) {
            previous = current;
            state = current.grounded() ? NoFallStateKind.NOT_FALLING : NoFallStateKind.UNCERTAIN;
            return null;
        }
        NoFallAnalysis completed = finalizeIfReady(current.timestampNanos());
        if (current.timestampNanos() <= previous.timestampNanos() || current.durationMillis() > 250) {
            resetInternal(NoFallStateKind.UNCERTAIN);
            previous = current;
            return completed;
        }
        if (movementResetContext(current.context())) {
            resetInternal(NoFallStateKind.UNCERTAIN);
            previous = current;
            return completed;
        }
        if (current.context().uncertain()) {
            contextConflict = true;
            state = NoFallStateKind.UNCERTAIN;
            previous = current;
            return completed;
        }
        if (current.context().externalVelocity()) contextConflict = true;

        if (!current.grounded()) {
            if (current.deltaY() < -.02 && fallStartNanos == 0) {
                startY = previous.to().y();
                lowestY = Math.min(startY, current.to().y());
                fallStartNanos = current.timestampNanos();
                contextConflict = current.context().externalVelocity();
                state = NoFallStateKind.FALLING;
            } else if (fallStartNanos != 0) {
                lowestY = Math.min(lowestY, current.to().y());
                state = startY - lowestY >= 3.0 ? NoFallStateKind.LONG_FALL : NoFallStateKind.FALLING;
            }
            previous = current;
            return completed;
        }

        if (!previous.grounded() && fallStartNanos != 0) {
            lowestY = Math.min(lowestY, current.to().y());
            double distance = Math.max(0.0, startY - lowestY);
            long duration = Math.max(0, (current.timestampNanos() - fallStartNanos) / 1_000_000);
            MovementContext context = current.context();
            boolean safe = context.safeLanding() || context.fallDamageReducing() || context.specialMovement();
            boolean conflict = contextConflict || context.uncertain();
            boolean expected = distance >= 3.0 && !safe && !conflict;
            if (expected || conflict) {
                boolean impact = lastImpactNanos >= fallStartNanos
                        && lastImpactNanos <= current.timestampNanos() + 750_000_000L
                        && lastImpactDamage > 0.0;
                pending = new PendingFall(current.timestampNanos(), duration, distance, startY, lowestY,
                        current.to().y(), expected, impact, conflict, context.landingSurface());
                state = NoFallStateKind.LANDING_PENDING;
            } else {
                state = NoFallStateKind.LANDED;
                clearFall();
            }
            previous = current;
            return completed;
        }
        state = NoFallStateKind.NOT_FALLING;
        previous = current;
        return completed;
    }

    public synchronized NoFallAnalysis observeImpact(FallDamageObservation impact) {
        if (impact.finalDamage() <= 0.0) return null;
        lastImpactNanos = impact.timestampNanos();
        lastImpactDamage = impact.finalDamage();
        if (pending != null && impact.timestampNanos() <= pending.landingNanos() + 750_000_000L) {
            pending = pending.withObservedImpact(true);
        }
        return null;
    }

    private NoFallAnalysis finalizeIfReady(long now) {
        if (pending == null) return null;
        if (pending.observedImpact()) {
            pending = null;
            clearFall();
            state = NoFallStateKind.LANDED;
            return null;
        }
        if (now - pending.landingNanos() < 100_000_000L) return null;
        PendingFall completed = pending;
        pending = null;
        clearFall();
        state = completed.contextConflict() ? NoFallStateKind.UNCERTAIN : NoFallStateKind.LANDED;
        if (!completed.expectedImpact() && !completed.contextConflict()) return null;
        if (completed.contextConflict()) {
            mismatchStreak = 0;
            lastReported = SignalLevel.UNCERTAIN;
            return analysis(completed, EnumSet.of(NoFallSignalType.CONTEXT_CONFLICT), SignalLevel.UNCERTAIN);
        }
        mismatchStreak = Math.min(capacity, mismatchStreak + 1);
        SignalLevel confidence = mismatchStreak >= 3 ? SignalLevel.STRONG_SIGNAL
                : mismatchStreak >= 2 ? SignalLevel.SUSPICIOUS : SignalLevel.UNCERTAIN;
        if (confidence == lastReported) return null;
        lastReported = confidence;
        return analysis(completed, EnumSet.of(NoFallSignalType.FALL_SEQUENCE,
                NoFallSignalType.EXCESSIVE_FALL_DISTANCE, NoFallSignalType.MISSING_EXPECTED_IMPACT,
                NoFallSignalType.LANDING_CONSISTENCY), confidence);
    }

    private NoFallAnalysis analysis(PendingFall fall, EnumSet<NoFallSignalType> signals,
                                    SignalLevel confidence) {
        return new NoFallAnalysis(previous.playerId(), fall.landingNanos(), signals, fall.durationMillis(),
                fall.distance(), fall.startY(), fall.lowestY(), fall.landingY(), state,
                fall.expectedImpact(), fall.observedImpact(), confidence, mismatchStreak);
    }

    private void clearFall() {
        startY = 0;
        lowestY = 0;
        fallStartNanos = 0;
        lastImpactNanos = 0;
        lastImpactDamage = 0;
        contextConflict = false;
    }

    private static boolean movementResetContext(MovementContext context) {
        return context.fluid() || context.climbable() || context.slime() || context.ice()
                || context.vehicle() || context.gliding() || context.specialGameMode()
                || context.activeEffect() || context.teleportReset();
    }

    private void resetInternal(NoFallStateKind resetState) {
        pending = null;
        clearFall();
        mismatchStreak = 0;
        lastReported = null;
        state = resetState;
    }

    public synchronized void reset() {
        previous = null;
        resetInternal(NoFallStateKind.RESET);
    }

    public synchronized NoFallStateKind state() { return state; }

    private record PendingFall(long landingNanos, long durationMillis, double distance,
                               double startY, double lowestY, double landingY,
                               boolean expectedImpact, boolean observedImpact,
                               boolean contextConflict, String landingSurface) {
        private PendingFall withObservedImpact(boolean value) {
            return new PendingFall(landingNanos, durationMillis, distance, startY, lowestY, landingY,
                    expectedImpact, value, contextConflict, landingSurface);
        }
    }
}
