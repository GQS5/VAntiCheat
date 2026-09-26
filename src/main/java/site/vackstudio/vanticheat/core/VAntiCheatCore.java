package site.vackstudio.vanticheat.core;

import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionRegistry;
import site.vackstudio.vanticheat.lifecycle.LifecycleState;
import site.vackstudio.vanticheat.platform.PlatformContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** Owns only foundation lifecycle. Feature modules are added in later phases. */
public final class VAntiCheatCore {
    private final FoundationConfig config;
    private final PlatformContext context;
    private final Logger logger;
    private final DetectionRegistry detectionRegistry;
    private final AtomicReference<LifecycleState> state =
            new AtomicReference<>(LifecycleState.NEW);

    public VAntiCheatCore(FoundationConfig config, PlatformContext context, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.context = Objects.requireNonNull(context, "context");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.detectionRegistry = new DetectionRegistry();
    }

    public synchronized void start() {
        if (!state.compareAndSet(LifecycleState.NEW, LifecycleState.INITIALIZING)) {
            throw new IllegalStateException("Core has already been started or stopped");
        }
        try {
            if (config.detectionEnabled()) {
                DetectionModuleContext moduleContext = new DetectionModuleContext(
                        context, config, logger);
                detectionRegistry.initializeAll(moduleContext);
                detectionRegistry.startAll();
            } else {
                detectionRegistry.disable();
            }
            state.set(LifecycleState.RUNNING);
        } catch (RuntimeException exception) {
            detectionRegistry.stopAll();
            state.set(LifecycleState.STOPPED);
            throw exception;
        }
    }

    public synchronized void shutdown() {
        LifecycleState current = state.get();
        if (current == LifecycleState.STOPPED) return;
        if (current == LifecycleState.NEW) {
            state.set(LifecycleState.STOPPED);
            return;
        }
        state.set(LifecycleState.STOPPING);
        detectionRegistry.stopAll();
        context.scheduler().shutdown();
        state.set(LifecycleState.STOPPED);
    }

    public FoundationConfig config() { return config; }
    public PlatformContext context() { return context; }
    public DetectionRegistry detectionRegistry() { return detectionRegistry; }
    public site.vackstudio.vanticheat.platform.Platform platform() { return context.platform(); }
    public LifecycleState state() { return state.get(); }
    public boolean running() { return state() == LifecycleState.RUNNING; }
}
