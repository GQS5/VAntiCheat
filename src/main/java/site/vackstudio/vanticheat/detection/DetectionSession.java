package site.vackstudio.vanticheat.detection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DetectionSession {
    private final UUID sessionId;
    private final UUID targetId;
    private final String detectionId;
    private final Instant createdAt;
    private final List<Evidence> evidence = new ArrayList<>();
    private DetectionSessionState state = DetectionSessionState.NEW;
    private DetectionResult result = DetectionResult.notChecked();

    public DetectionSession(UUID sessionId, UUID targetId, String detectionId, Instant createdAt) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.detectionId = Objects.requireNonNull(detectionId, "detectionId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (detectionId.isBlank()) throw new IllegalArgumentException("Detection id cannot be blank");
    }

    public synchronized void start() {
        requireState(DetectionSessionState.NEW);
        state = DetectionSessionState.STARTING;
        result = DetectionResult.running();
        state = DetectionSessionState.RUNNING;
    }

    public synchronized void beginCompletion() {
        requireState(DetectionSessionState.RUNNING);
        state = DetectionSessionState.COMPLETING;
    }

    public synchronized void complete(DetectionResult completedResult) {
        requireState(DetectionSessionState.COMPLETING);
        Objects.requireNonNull(completedResult, "completedResult");
        if (!isFinal(completedResult.status())) {
            throw new IllegalArgumentException("Result is not terminal");
        }
        ArrayList<Evidence> allEvidence = new ArrayList<>(evidence);
        allEvidence.addAll(completedResult.evidence());
        result = new DetectionResult(completedResult.status(), completedResult.reason(), allEvidence);
        state = DetectionSessionState.COMPLETED;
    }

    public synchronized void cancel(String reason) {
        requireActive();
        result = new DetectionResult(DetectionStatus.SKIPPED, requireReason(reason), evidence);
        state = DetectionSessionState.CANCELLED;
    }

    public synchronized void timeout(String reason) {
        requireActive();
        result = new DetectionResult(DetectionStatus.ERROR, requireReason(reason), evidence);
        state = DetectionSessionState.TIMED_OUT;
    }

    public synchronized void fail(String reason) {
        requireActive();
        result = new DetectionResult(DetectionStatus.ERROR, requireReason(reason), evidence);
        state = DetectionSessionState.FAILED;
    }

    public synchronized void addEvidence(Evidence item) {
        requireActive();
        evidence.add(Objects.requireNonNull(item, "item"));
    }

    public UUID sessionId() { return sessionId; }
    public UUID targetId() { return targetId; }
    public String detectionId() { return detectionId; }
    public Instant createdAt() { return createdAt; }
    public synchronized DetectionSessionState state() { return state; }
    public synchronized DetectionResult result() { return result; }
    public synchronized List<Evidence> evidence() { return List.copyOf(evidence); }

    private void requireState(DetectionSessionState expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + state);
        }
    }

    private void requireActive() {
        if (state.terminal()) throw new IllegalStateException("Session is terminal: " + state);
        if (state == DetectionSessionState.NEW) {
            throw new IllegalStateException("Session has not started");
        }
    }

    private static boolean isFinal(DetectionStatus status) {
        return status == DetectionStatus.CLEAN
                || status == DetectionStatus.DETECTED
                || status == DetectionStatus.UNCERTAIN
                || status == DetectionStatus.PROTECTED
                || status == DetectionStatus.SKIPPED
                || status == DetectionStatus.ERROR;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Reason cannot be blank");
        return reason;
    }
}
