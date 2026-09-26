package site.vackstudio.vanticheat.platform;

import java.util.Objects;

public record PlatformContext(Platform platform, Scheduler scheduler) {
    public PlatformContext {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(scheduler, "scheduler");
    }
}
