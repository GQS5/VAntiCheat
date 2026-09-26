package site.vackstudio.vanticheat.detection;

import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.platform.PlatformContext;

import java.util.Objects;
import java.util.logging.Logger;

public record DetectionContext(
        DetectionTarget target,
        DetectionSession session,
        PlatformContext platform,
        FoundationConfig configuration,
        Logger logger) {
    public DetectionContext {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(logger, "logger");
        if (!target.id().equals(session.targetId())) {
            throw new IllegalArgumentException("Target does not belong to session");
        }
    }
}
