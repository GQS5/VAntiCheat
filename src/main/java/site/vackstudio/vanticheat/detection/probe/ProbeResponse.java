package site.vackstudio.vanticheat.detection.probe;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProbeResponse(
        UUID sessionId,
        UUID targetId,
        List<String> lines,
        Outcome outcome) {
    public enum Outcome { RESPONSE, TIMEOUT, DISCONNECTED, ERROR }

    public ProbeResponse {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(outcome, "outcome");
        lines = List.copyOf(lines);
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
}
