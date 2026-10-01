package site.vackstudio.vanticheat.detection.probe;

/**
 * Declared transport capability of a probe. This is configuration metadata, never
 * inferred from the probe id.
 *
 * <p>{@link #PASSIVE} probes observe an already-available client signal. They never open
 * any client UI, never open a screen, never capture movement or input, and are therefore
 * the only probes allowed to run as a background automatic check.
 *
 * <p>{@link #INTERACTIVE} probes must make the client open and submit UI before the server
 * can observe a response. Running one as a background automatic check captures the player's
 * client UI and interrupts normal gameplay, so that requires explicit operator opt-in.
 */
public enum ProbeTransportMode {
    /** No client UI, no input capture, safe for unattended automatic detection. */
    PASSIVE,
    /** Requires client UI interaction; may capture movement and input. */
    INTERACTIVE;

    public boolean opensClientUi() {
        return this == INTERACTIVE;
    }

    public boolean safeForAutomatic() {
        return this == PASSIVE;
    }

    public static ProbeTransportMode parse(String value, String defaultMode) {
        if (value == null || value.isBlank()) {
            return defaultMode == null ? INTERACTIVE : valueOf(defaultMode.toUpperCase(java.util.Locale.ROOT));
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
