package site.vackstudio.vanticheat.diagnostics;

import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.core.VAntiCheatCore;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.detection.probe.ProbeDiagnostics;
import site.vackstudio.vanticheat.detection.probe.ProbeRegistry;
import site.vackstudio.vanticheat.lunar.LunarClientService;
import site.vackstudio.vanticheat.platform.AutomaticCheckCoordinator;
import site.vackstudio.vanticheat.platform.ClientPlatformService;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One read-only, immutable snapshot boundary for administrator diagnostics. */
public final class VAntiCheatDiagnostics {
    public enum Health { HEALTHY, DEGRADED, ERROR }

    public record Reload(String state, Instant at, String summary, long registryVersion,
                         boolean lastKnownGoodActive) { }

    public record Configuration(boolean loaded, boolean detectionEnabled, long registryVersion,
                                int totalProbes, int enabledProbes, int manualProbes,
                                int automaticProbes, int verifiedProbes, int unverifiedProbes,
                                Reload lastReload) { }

    public record Automatic(boolean enabled, int active, int capacity, long admitted,
                            long started, long released, long skippedAdmissions, long failures) { }

    public record Platform(ClientPlatformService.ProviderSnapshot providers,
                           long javaCached, long bedrockCached, long unknownCached,
                           long noProviderCached) { }

    public record Lunar(String integration, boolean ready, String lastAction) { }

    public record Snapshot(boolean enabled, String version, String runtime, Health health,
                           String healthReason, Configuration configuration, Automatic automatic,
                           int activeScans, int trustedPlayers, Platform platform, Lunar lunar,
                           String engineState, Map<String, Long> results, long timeouts, long errors,
                           long detections, long cleans, long protectedResults, long completedScans,
                           long lastScanDurationMillis, long averageScanDurationMillis,
                           List<ProbeDiagnostics.ActiveScan> activeScanDetails,
                           List<ProbeDiagnostics.RecentScan> recentScans) {
        public Snapshot {
            results = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(results));
            activeScanDetails = List.copyOf(activeScanDetails);
            recentScans = List.copyOf(recentScans);
        }
    }

    private final String version;
    private final String runtime;
    private final VAntiCheatCore core;
    private final Supplier<ClientDetectionConfig> configuration;
    private final BooleanSupplier configurationLoaded;
    private final CheckHacksClientDetectionModule module;
    private final Supplier<AutomaticCheckCoordinator.MetricsSnapshot> automaticMetrics;
    private final ClientPlatformService platforms;
    private final LunarClientService lunar;
    private final TrustedPlayerService trusted;
    private final LongSupplier registryVersion;
    private final AtomicReference<Reload> lastReload;

    public VAntiCheatDiagnostics(String version, String runtime, VAntiCheatCore core,
                                 Supplier<ClientDetectionConfig> configuration,
                                 BooleanSupplier configurationLoaded,
                                 CheckHacksClientDetectionModule module,
                                 Supplier<AutomaticCheckCoordinator.MetricsSnapshot> automaticMetrics,
                                 ClientPlatformService platforms, LunarClientService lunar,
                                 TrustedPlayerService trusted, LongSupplier registryVersion,
                                 AtomicReference<Reload> lastReload) {
        this.version = Objects.requireNonNull(version, "version");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.core = core;
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.configurationLoaded = Objects.requireNonNull(configurationLoaded, "configurationLoaded");
        this.module = module;
        this.automaticMetrics = Objects.requireNonNull(automaticMetrics, "automaticMetrics");
        this.platforms = platforms;
        this.lunar = lunar;
        this.trusted = Objects.requireNonNull(trusted, "trusted");
        this.registryVersion = Objects.requireNonNull(registryVersion, "registryVersion");
        this.lastReload = Objects.requireNonNull(lastReload, "lastReload");
    }

    /** Uses only cached/atomic service snapshots; performs no player, provider, Apollo, or world lookups. */
    public Snapshot snapshot() {
        ClientDetectionConfig config = configuration.get();
        ProbeRegistry registry = config == null ? ProbeRegistry.empty() : config.probeRegistry();
        ProbeDiagnostics.Snapshot probeStats = module == null ? emptyProbeSnapshot() : module.diagnostics();
        AutomaticCheckCoordinator.MetricsSnapshot auto = automaticMetrics.get();
        if (auto == null) auto = new AutomaticCheckCoordinator.MetricsSnapshot(0, 0, 0, 0, 0, 0, 0);
        ClientPlatformService.ProviderSnapshot providerStates = platforms == null
                ? new ClientPlatformService.ProviderSnapshot(ClientPlatformService.ProviderState.NOT_PRESENT,
                ClientPlatformService.ProviderState.NOT_PRESENT, ClientPlatformService.Readiness.READY)
                : platforms.providerSnapshot();
        Platform platform = new Platform(providerStates,
                platforms == null ? 0 : platforms.count(ClientPlatformService.State.JAVA),
                platforms == null ? 0 : platforms.count(ClientPlatformService.State.BEDROCK),
                platforms == null ? 0 : platforms.count(ClientPlatformService.State.UNKNOWN),
                platforms == null ? 0 : platforms.count(ClientPlatformService.State.NO_PROVIDER));
        site.vackstudio.vanticheat.lunar.LunarClientIntegration.Availability lunarAvailability = lunar == null
                ? site.vackstudio.vanticheat.lunar.LunarClientIntegration.Availability.DISABLED
                : lunar.integrationStatus();
        Lunar lunarStatus = lunar == null
                ? new Lunar("DISABLED", false, "NOT_RUN")
                : new Lunar(lunarAvailability.name(),
                lunarAvailability == site.vackstudio.vanticheat.lunar.LunarClientIntegration.Availability.AVAILABLE,
                lunar.lastAction().name());
        Reload reload = lastReload.get();
        if (reload == null) reload = new Reload("NEVER", null, "No reload attempted", registryVersion.getAsLong(),
                module != null && module.isStarted());
        Configuration configStatus = new Configuration(configurationLoaded.getAsBoolean(), config != null && config.enabled(),
                registryVersion.getAsLong(), registry.size(), registry.enabledCount(), registry.manualCount(),
                registry.automaticCount(), registry.verifiedCount(), registry.unverifiedCount(), reload);
        Automatic automatic = new Automatic(config != null && config.enabled() && config.autoCheckOnJoin()
                && module != null && module.isStarted(),
                auto.active(), auto.capacity(), auto.admitted(), auto.started(), auto.released(),
                auto.skippedAdmissions(), auto.failures());
        boolean enabled = core != null && core.running();
        String engineState = module == null ? "UNAVAILABLE" : module.isStarted() ? "READY" : "STOPPED";
        HealthResult health = health(enabled, configurationLoaded.getAsBoolean(), config, module);
        Map<String, Long> results = resultMap(probeStats);
        return new Snapshot(enabled, version, runtime, health.health(), health.reason(), configStatus,
                automatic, probeStats.activeScans().size(), trusted.size(), platform, lunarStatus,
                engineState, results, probeStats.timeouts(), probeStats.errors(), probeStats.detections(),
                probeStats.cleans(), probeStats.protectedResults(), probeStats.completedScans(),
                probeStats.lastDurationMillis(), probeStats.averageDurationMillis(), probeStats.activeScans(),
                probeStats.recentScans());
    }

    public List<ProbeDiagnostics.RecentScan> recentDetections() {
        return snapshot().recentScans().stream()
                .filter(scan -> scan.result() == site.vackstudio.vanticheat.detection.DetectionStatus.DETECTED)
                .toList();
    }

    public ProbeRegistry probeRegistry() {
        ClientDetectionConfig config = configuration.get();
        return config == null ? ProbeRegistry.empty() : config.probeRegistry();
    }

    private static HealthResult health(boolean enabled, boolean configLoaded, ClientDetectionConfig config,
                                       CheckHacksClientDetectionModule module) {
        if (!enabled) return new HealthResult(Health.ERROR, "plugin/core is not running");
        if (!configLoaded || config == null) return new HealthResult(Health.ERROR, "client configuration unavailable");
        if (module == null || !module.isStarted()) {
            return new HealthResult(Health.DEGRADED, "client detection engine unavailable");
        }
        if (config.enabled() && module.registry().size() == 0) {
            return new HealthResult(Health.DEGRADED, "probe registry is empty");
        }
        return new HealthResult(Health.HEALTHY, "core and configured detection engine operational");
    }

    private static Map<String, Long> resultMap(ProbeDiagnostics.Snapshot stats) {
        java.util.LinkedHashMap<String, Long> result = new java.util.LinkedHashMap<>();
        for (var status : site.vackstudio.vanticheat.detection.DetectionStatus.values()) {
            result.put(status.name(), stats.count(status));
        }
        return result;
    }

    private static ProbeDiagnostics.Snapshot emptyProbeSnapshot() {
        java.util.EnumMap<site.vackstudio.vanticheat.detection.DetectionStatus, Long> counts =
                new java.util.EnumMap<>(site.vackstudio.vanticheat.detection.DetectionStatus.class);
        for (var status : site.vackstudio.vanticheat.detection.DetectionStatus.values()) counts.put(status, 0L);
        return new ProbeDiagnostics.Snapshot(counts, 0, 0, 0, List.of(), List.of());
    }

    private record HealthResult(Health health, String reason) { }
}
