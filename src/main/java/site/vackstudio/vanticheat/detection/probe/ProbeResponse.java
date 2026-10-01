package site.vackstudio.vanticheat.detection.probe;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;

public record ProbeResponse(
        UUID sessionId,
        UUID targetId,
        List<String> lines,
        Outcome outcome,
        List<String> componentIdentities) {
    public enum Outcome { RESPONSE, TIMEOUT, DISCONNECTED, CANCELLED, ERROR, UNSUPPORTED, SKIPPED }

    public ProbeResponse(UUID sessionId, UUID targetId, List<String> lines, Outcome outcome) {
        this(sessionId, targetId, lines, outcome, Collections.nCopies(lines.size(), null));
    }

    public ProbeResponse {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(componentIdentities, "componentIdentities");
        lines = Collections.unmodifiableList(new ArrayList<>(lines));
        componentIdentities = Collections.unmodifiableList(new ArrayList<>(componentIdentities));
        if (componentIdentities.size() != lines.size()) {
            throw new IllegalArgumentException("componentIdentities must match response line count");
        }
    }

    public static ProbeResponse timeout(ProbeRequest request) {
        return new ProbeResponse(request.sessionId(), request.target().id(), List.of(), Outcome.TIMEOUT);
    }

    public static ProbeResponse disconnected(ProbeRequest request) {
        return new ProbeResponse(request.sessionId(), request.target().id(), List.of(), Outcome.DISCONNECTED);
    }

    public static ProbeResponse error(ProbeRequest request) {
        return new ProbeResponse(request.sessionId(), request.target().id(), List.of(), Outcome.ERROR);
    }

    public static ProbeResponse skipped(ProbeRequest request) {
        return new ProbeResponse(request.sessionId(), request.target().id(), List.of(), Outcome.SKIPPED);
    }
}
