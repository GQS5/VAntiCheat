package site.vackstudio.vanticheat.detection.probe;

/**
 * Observable client-probe readiness for one player. This is transport and client-channel
 * health only.
 *
 * <p>It is deliberately a separate type from {@link site.vackstudio.vanticheat.detection.DetectionStatus}
 * and can never be promoted to {@code DETECTED}. A client can be perfectly healthy and
 * simply not be running any probed target, and a client can be unresponsive without
 * being a cheater. It says nothing about CPU, GPU, RAM, FPS, or device quality.
 */
public enum ClientProbeHealth {
    /** Probing is possible but no scan has been attempted yet. */
    READY,
    /** A response arrived from the client. */
    RESPONSIVE,
    /** The transport cannot carry this probe at all. */
    UNSUPPORTED,
    /** A response was expected inside the deadline and none arrived. */
    TIMED_OUT,
    /** The client answered but with content that carried no usable identity. */
    UNRESPONSIVE,
    /** The operation ended early: disconnect, shutdown, reload, or session supersession. */
    INTERRUPTED;

    /** Health states are never evidence of cheating. */
    public boolean detectionSignal() {
        return false;
    }

    public static ClientProbeHealth from(ProbeResponse.Outcome outcome) {
        if (outcome == null) return READY;
        return switch (outcome) {
            case RESPONSE -> RESPONSIVE;
            case TIMEOUT -> TIMED_OUT;
            case UNSUPPORTED -> UNSUPPORTED;
            case CANCELLED, DISCONNECTED, SKIPPED, ERROR -> INTERRUPTED;
        };
    }
}
