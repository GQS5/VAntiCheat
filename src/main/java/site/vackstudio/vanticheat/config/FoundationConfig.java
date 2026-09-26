package site.vackstudio.vanticheat.config;

public record FoundationConfig(boolean enabled, boolean debug, boolean detectionEnabled) {
    public FoundationConfig {
        // The foundation has no feature-specific settings yet.
    }

    public static FoundationConfig defaults() {
        return new FoundationConfig(true, false, true);
    }
}
