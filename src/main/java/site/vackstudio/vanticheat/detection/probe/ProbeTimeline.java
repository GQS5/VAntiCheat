package site.vackstudio.vanticheat.detection.probe;

import java.util.UUID;
import java.util.logging.Logger;

/** Debug-only lifecycle timing; disabled instances do no logging work. */
public final class ProbeTimeline {
    private final Logger logger;
    private final boolean enabled;

    public ProbeTimeline(Logger logger, boolean enabled) {
        this.logger = logger;
        this.enabled = enabled;
    }

    public long start() {
        return System.nanoTime();
    }

    public void event(String name, long startNanos, ProbeRequest request, String detail) {
        if (!enabled) return;
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        logger.info("[ClientProbe] " + name
                + " session=" + request.sessionId()
                + " player=" + request.target().id()
                + " trigger=" + request.trigger()
                + " batch=" + request.batchIndex()
                + " batchSize=" + request.probes().size()
                + " elapsedMs=" + elapsedMillis
                + (detail == null || detail.isBlank() ? "" : " " + detail));
    }

    public void event(String name, long startNanos, UUID sessionId, UUID playerId,
                      String trigger, int batchIndex, int batchSize, String detail) {
        if (!enabled) return;
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        logger.info("[ClientProbe] " + name
                + " session=" + sessionId
                + " player=" + playerId
                + " trigger=" + trigger
                + " batch=" + batchIndex
                + " batchSize=" + batchSize
                + " elapsedMs=" + elapsedMillis
                + (detail == null || detail.isBlank() ? "" : " " + detail));
    }
}
