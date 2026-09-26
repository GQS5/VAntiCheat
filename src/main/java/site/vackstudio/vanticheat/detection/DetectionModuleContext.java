package site.vackstudio.vanticheat.detection;

import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.platform.PlatformContext;

import java.util.Objects;
import java.util.logging.Logger;

public record DetectionModuleContext(
        PlatformContext platform,
        FoundationConfig configuration,
        Logger logger) {
    public DetectionModuleContext {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(logger, "logger");
    }
}
