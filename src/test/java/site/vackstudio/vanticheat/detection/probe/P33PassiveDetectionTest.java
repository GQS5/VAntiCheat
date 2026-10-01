package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.enforcement.ConfirmedDetection;
import site.vackstudio.vanticheat.enforcement.DefaultEnforcementPolicy;
import site.vackstudio.vanticheat.enforcement.EnforcementDecision;
import site.vackstudio.vanticheat.platform.ClientPlatform;
import site.vackstudio.vanticheat.platform.ClientPlatformService;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P33: the recovered passive detection path, its safety properties, and adversarial cases.
 *
 * <p>All evidence here is deterministic protocol-level test data. Nothing in this file is a
 * live-client verification claim.
 */
class P33PassiveDetectionTest {
    private static final String JADE_CHANNEL = "jade:client_handshake";
    private static final String JADE_PROBE = "jade-network-handshake";

    @TempDir
    private Path temporaryDirectory;

    // -------------------------------------------------- 1. the passive path works at all

    @Test
    void observedDeclaredChannelIsAuthoritativeIdentityEvidence() throws Exception {
        Fixture fixture = new Fixture();
        DetectionTarget player = player();
        long generation = fixture.module.beginPassiveContext(player.id(), ClientPlatform.JAVA, "fabric");
        fixture.module.signals().observe(player.id(), generation, JADE_CHANNEL, JADE_PROBE, 4,
                ClientPlatform.JAVA, "fabric");
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        fixture.module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.DETECTED, result.get().status());
        assertTrue(result.get().evidence().stream().anyMatch(e ->
                        JADE_PROBE.equals(e.metadata().get("probe"))
                                && "STRONG".equals(e.metadata().get("evidenceStrength"))
                                && "PASSIVE".equals(e.metadata().get("transport"))),
                "the evidence must record the passive transport as its source");
    }

    @Test
    void absenceOfTheChannelProvesNothingAndStaysClean() throws Exception {
        Fixture fixture = new Fixture();
        DetectionTarget player = player();
        fixture.module.beginPassiveContext(player.id(), ClientPlatform.JAVA, "vanilla");
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        fixture.module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.CLEAN, result.get().status(),
                "a clean client that never sends the channel must never be detected");
    }

    @Test
    void theBundledCatalogDeclaresExactlyOnePassiveChannel() throws Exception {
        assertEquals(Map.of(JADE_CHANNEL, JADE_PROBE), bundled().passiveChannels());
        assertEquals(1, bundled().passiveProbeCount());
    }

    // ----------------------------------------- 2. player impact: the primary requirement

    /** An automatic passive scan must not open UI, touch the world, or change player state. */
    @Test
    void automaticPassiveScanHasNoPlayerImpact() throws Exception {
        Fixture fixture = new Fixture();
        DetectionTarget player = player();
        long generation = fixture.module.beginPassiveContext(player.id(), ClientPlatform.JAVA, "fabric");
        fixture.module.signals().observe(player.id(), generation, JADE_CHANNEL, JADE_PROBE, 4,
                ClientPlatform.JAVA, "fabric");

        fixture.module.checkAutomaticIfIdle(player, "JOIN", ignored -> { });

        assertEquals(0, fixture.transport.uiOpenings.get(), "no client screen may be opened");
        assertEquals(0, fixture.transport.worldMutations.get(), "no block may be changed");
        assertEquals(0, fixture.transport.playerStateChanges.get(),
                "no velocity, teleport, freeze, or movement lock");
        assertEquals(0, fixture.transport.requests.size(), "no transport dispatch at all");
    }

    /** Interactive probes still behave exactly as before, and still clean up once. */
    @Test
    void manualInteractiveProbeRetainsControlledBehaviorAndCleansUpOnce() throws Exception {
        Fixture fixture = new Fixture();
        ProbeDefinition meteor = bundled().probeRegistry().require("meteor-client");
        fixture.transport.rendered = ClientResponseFixtures.meteor(meteor);
        DetectionTarget player = player();
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        fixture.module.check(player, List.of(meteor), result::set);

        assertEquals(2, fixture.transport.uiOpenings.get(),
                "the interactive path is unchanged: one editor for the initial pass and one for "
                        + "the confirmation pass");
        assertEquals(2, fixture.transport.requests.size());
        assertEquals(2, fixture.transport.cleanups.get(), "cleanup runs exactly once per operation");
        assertEquals(2, result.get().evidence().size(), "one record per pass");
        assertEquals(0, fixture.transport.activeOperations.get(), "no leaked operation");
        assertEquals(0, fixture.module.activeSessionCount(), "no leaked session");
        assertEquals(DetectionStatus.DETECTED, result.get().status(),
                "the recorded interactive detection path is unchanged");
    }

    // --------------------------------------------------- 3. P17 platform safety (Phase 4)

    @Test
    void bedrockNeverReceivesPassiveProbes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.classification = id -> new ClientPlatformService.Classification(
                ClientPlatformService.State.BEDROCK, ClientPlatformService.Reason.FLOODGATE, "FLOODGATE");
        DetectionTarget player = player();
        long generation = fixture.module.beginPassiveContext(player.id(), ClientPlatform.JAVA, "fabric");
        fixture.module.signals().observe(player.id(), generation, JADE_CHANNEL, JADE_PROBE, 4,
                ClientPlatform.JAVA, "fabric");
        fixture.module.setProbeEligibility(id -> false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        fixture.module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.SKIPPED, result.get().status(),
                "Bedrock must be excluded before any probe, passive included");
        assertEquals(0, fixture.transport.requests.size());
        assertEquals(0, fixture.transport.uiOpenings.get());
    }

    @Test
    void unknownPlatformRemainsFailClosed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.classification = id -> new ClientPlatformService.Classification(
                ClientPlatformService.State.UNKNOWN, ClientPlatformService.Reason.PROVIDER_NOT_READY,
                "GEYSER_NOT_READY");
        DetectionTarget player = player();
        long generation = fixture.module.beginPassiveContext(player.id(), ClientPlatform.UNKNOWN, null);
        fixture.module.signals().observe(player.id(), generation, JADE_CHANNEL, JADE_PROBE, 4,
                ClientPlatform.UNKNOWN, null);
        fixture.module.setProbeEligibility(id -> false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        fixture.module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.SKIPPED, result.get().status(),
                "UNKNOWN must never be treated as Java because a Java-shaped packet arrived");
    }

    /** A passive observation must not reclassify the platform. */
    @Test
    void passiveObservationNeverUpgradesPlatformClassification() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.UNKNOWN, null);
        collector.observe(id, generation, JADE_CHANNEL, JADE_PROBE, 4, ClientPlatform.UNKNOWN, null);

        assertEquals(ClientPlatform.UNKNOWN, collector.entry(id).platform(),
                "observing a Java-only channel must not make an UNKNOWN platform probe-eligible");
    }

    // ------------------------------------------ 4. brand is context only (Phase 5)

    @Test
    void brandAloneNeverProducesEvidenceOrDetection() throws Exception {
        // Fabric + Sodium, Fabric + Meteor, and Fabric + AppleSkin all present the same brand.
        for (String brand : List.of("fabric", "vanilla", "fml", "meteor", "lunarclient")) {
            Fixture fixture = new Fixture();
            DetectionTarget player = player();
            fixture.module.beginPassiveContext(player.id(), ClientPlatform.JAVA, brand);
            AtomicReference<DetectionResult> result = new AtomicReference<>();
            fixture.module.checkAutomaticIfIdle(player, "JOIN", result::set);

            assertEquals(DetectionStatus.CLEAN, result.get().status(),
                    "brand '" + brand + "' must never be treated as mod identity");
        }
    }

    @Test
    void aTargetLookingBrandStillProducesNothing() throws Exception {
        Fixture fixture = new Fixture();
        DetectionTarget player = player();
        long generation = fixture.module.beginPassiveContext(player.id(), ClientPlatform.JAVA, "jade");
        fixture.module.signals().observe(player.id(), generation, "jade:mod_name", "jade-config-screen", 9,
                ClientPlatform.JAVA, "jade");
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        fixture.module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.CLEAN, result.get().status(),
                "an undeclared channel is never evidence, however target-looking it is");
    }

    // ----------------------------------- 5. false positive attacks (Phase 14)

    @Test
    void aTargetLookingChannelThatIsNotDeclaredIsIgnored() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");

        collector.observe(id, generation, "jade:client_handshake_extra", JADE_PROBE, 8,
                ClientPlatform.JAVA, "fabric");
        collector.observe(id, generation, "notjade:client_handshake", JADE_PROBE, 8,
                ClientPlatform.JAVA, "fabric");
        collector.observe(id, generation, "jade:client_handshake ", JADE_PROBE, 8,
                ClientPlatform.JAVA, "fabric");

        assertFalse(collector.has(id, JADE_PROBE),
                "only the exact declared channel may be evidence; no prefix or suffix matching");
    }

    @Test
    void duplicateAndReorderedObservationsDoNotInflateEvidence() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");

        for (int index = 0; index < 50; index++) {
            collector.observe(id, generation, JADE_CHANNEL, JADE_PROBE, index,
                    ClientPlatform.JAVA, "fabric");
        }
        assertEquals(1, collector.entry(id).probeIds().size(),
                "repeated packets collapse to one recorded fact, never a score");
    }

    @Test
    void malformedAndTruncatedPayloadsAreIrrelevantToIdentity() {
        // payload shape cannot change the conclusion
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");
        for (int bytes : new int[]{0, 1, 32767}) {
            collector.observe(id, generation, JADE_CHANNEL, JADE_PROBE, bytes, ClientPlatform.JAVA, "fabric");
        }
        assertEquals(1, collector.entry(id).probeIds().size());
        assertTrue(collector.has(id, JADE_PROBE));
    }

    @Test
    void staleEvidenceFromAPreviousConnectionIsRejected() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long first = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");
        collector.observe(id, first, JADE_CHANNEL, JADE_PROBE, 4, ClientPlatform.JAVA, "fabric");
        assertTrue(collector.has(id, JADE_PROBE));

        // Reconnect: a fresh context must not inherit the old evidence.
        collector.beginConnection(id, ClientPlatform.JAVA, "fabric");
        assertFalse(collector.has(id, JADE_PROBE),
                "a reconnect must never see the previous connection's evidence");

        // A late packet from the old connection must be refused.
        collector.observe(id, first, JADE_CHANNEL, JADE_PROBE, 4, ClientPlatform.JAVA, "fabric");
        assertFalse(collector.has(id, JADE_PROBE), "a stale generation must be rejected");
    }

    @Test
    void delayedPacketAfterDisconnectCannotResurrectEvidence() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");
        collector.remove(id);

        collector.observe(id, generation, JADE_CHANNEL, JADE_PROBE, 4, ClientPlatform.JAVA, "fabric");

        assertFalse(collector.has(id, JADE_PROBE), "a packet after disconnect carries no weight");
        assertEquals(0, collector.trackedPlayers());
    }

    @Test
    void aSubstringOfTheChannelInsideAPayloadIsNeverEvidence() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");
        assertFalse(collector.has(id, JADE_PROBE));
        assertEquals(0, collector.snapshot().observed());
    }

    // --------------------------------------- 6. enforcement safety (Phase 7 judgement)

    @Test
    void passiveOnlyDetectionDoesNotReachEnforcementByDefault() throws Exception {
        Fixture fixture = new Fixture();
        DetectionTarget player = player();
        long generation = fixture.module.beginPassiveContext(player.id(), ClientPlatform.JAVA, "fabric");
        fixture.module.signals().observe(player.id(), generation, JADE_CHANNEL, JADE_PROBE, 4,
                ClientPlatform.JAVA, "fabric");
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        fixture.module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.DETECTED, result.get().status());
        assertFalse(ConfirmedDetection.isConfirmed(result.get(), false),
                "a passive-only detection must not be confirmable by default");
        EnforcementDecision decision = new DefaultEnforcementPolicy(true, false).decide(result.get());
        assertEquals(site.vackstudio.vanticheat.enforcement.EnforcementAction.NONE, decision.action(),
                "Jade is a legitimate utility mod: kicking on it by default would be a false positive");
    }

    @Test
    void operatorCanOptInToEnforcingPassiveIdentity() throws Exception {
        Fixture fixture = new Fixture();
        fixture.module.setPassiveEnforcement(true);
        DetectionTarget player = player();
        long generation = fixture.module.beginPassiveContext(player.id(), ClientPlatform.JAVA, "fabric");
        fixture.module.signals().observe(player.id(), generation, JADE_CHANNEL, JADE_PROBE, 4,
                ClientPlatform.JAVA, "fabric");
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        fixture.module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertTrue(ConfirmedDetection.isConfirmed(result.get(), true),
                "an explicit opt-in must make passive identity actionable");
    }

    // -------------------------------------------------- 7. memory and state bounds

    @Test
    void perPlayerEvidenceIsBounded() {
        ClientSignalCollector collector = new ClientSignalCollector();
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");
        for (int index = 0; index < 500; index++) {
            collector.observe(id, generation, "mod:channel" + index, "probe-" + index, 1,
                    ClientPlatform.JAVA, "fabric");
        }
        assertTrue(collector.entry(id).probeIds().size() <= ClientSignalCollector.MAX_SIGNALS_PER_PLAYER,
                "per-player evidence must stay bounded");
    }

    @Test
    void trackedPlayerCountIsBounded() {
        ClientSignalCollector collector = new ClientSignalCollector();
        for (int index = 0; index < ClientSignalCollector.MAX_PLAYERS + 200; index++) {
            collector.beginConnection(UUID.randomUUID(), ClientPlatform.JAVA, "fabric");
        }
        assertTrue(collector.trackedPlayers() <= ClientSignalCollector.MAX_PLAYERS,
                "tracked players must stay bounded with no global unbounded map");
    }

    @Test
    void noRawPayloadIsRetained() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");
        collector.observe(id, generation, JADE_CHANNEL, JADE_PROBE, 1234, ClientPlatform.JAVA, "fabric");

        String rendered = collector.entry(id).toString();
        assertFalse(rendered.contains("bytes=1234"),
                "only the length is kept; payload bytes are never stored");
    }

    // -------------------------------------------------- 8. performance (Phase 15)

    /**
     * The observer must be effectively constant time for irrelevant traffic: an undeclared
     * channel is rejected by a single map lookup, with no parsing and no allocation.
     */
    @Test
    void undeclaredChannelRejectionIsConstantTime() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");

        for (int index = 0; index < 20_000; index++) {
            collector.observe(id, generation, "other:channel", "other-probe", 64,
                    ClientPlatform.JAVA, "fabric");
        }
        long before = System.nanoTime();
        int iterations = 200_000;
        for (int index = 0; index < iterations; index++) {
            collector.observe(id, generation, "other:channel", "other-probe", 64,
                    ClientPlatform.JAVA, "fabric");
        }
        long perRejection = (System.nanoTime() - before) / iterations;

        assertTrue(perRejection < 2_000L,
                "an irrelevant channel must cost a map lookup, measured " + perRejection + "ns");
        assertEquals(0, collector.snapshot().observed(), "irrelevant traffic records nothing");
    }

    @Test
    void aDeclaredObservationIsCheapAndAllocatesNoPayload() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");

        for (int index = 0; index < 20_000; index++) {
            collector.observe(id, generation, JADE_CHANNEL, JADE_PROBE, index % 64,
                    ClientPlatform.JAVA, "fabric");
        }
        long before = System.nanoTime();
        for (int index = 0; index < 200_000; index++) {
            collector.observe(id, generation, JADE_CHANNEL, JADE_PROBE, 8, ClientPlatform.JAVA, "fabric");
        }
        long perObservation = (System.nanoTime() - before) / 200_000;

        assertTrue(perObservation < 5_000L,
                "recording one declared signal must stay cheap, measured " + perObservation + "ns");
    }

    @Test
    void evidenceLookupOnTheAutomaticPathIsCheap() {
        ClientSignalCollector collector = new ClientSignalCollector(Map.of(JADE_CHANNEL, JADE_PROBE));
        UUID id = UUID.randomUUID();
        long generation = collector.beginConnection(id, ClientPlatform.JAVA, "fabric");
        collector.observe(id, generation, JADE_CHANNEL, JADE_PROBE, 4, ClientPlatform.JAVA, "fabric");

        for (int index = 0; index < 20_000; index++) collector.has(id, JADE_PROBE);
        long before = System.nanoTime();
        for (int index = 0; index < 200_000; index++) collector.has(id, JADE_PROBE);

        assertTrue((System.nanoTime() - before) / 200_000 < 2_000L,
                "an automatic scan reads this per probe, so it must stay cheap");
    }

    // ------------------------------------------------------------------- helpers

    private ClientDetectionConfig bundled() throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            Files.write(file, stream.readAllBytes());
        }
        return ClientDetectionConfig.load(temporaryDirectory, Logger.getAnonymousLogger());
    }

    private static DetectionTarget player() {
        return new DetectionTarget(UUID.randomUUID(), "player", true, new Object());
    }

    private static final class Fixture {
        private final RecordingTransport transport = new RecordingTransport();
        private final CheckHacksClientDetectionModule module;
        private java.util.function.Function<UUID, ClientPlatformService.Classification> classification =
                id -> new ClientPlatformService.Classification(ClientPlatformService.State.JAVA,
                        ClientPlatformService.Reason.JAVA_CONFIRMED, "PROVIDERS");

        private Fixture() throws Exception {
            ClientDetectionConfig config = bundledConfigStatic();
            module = new CheckHacksClientDetectionModule(config, transport);
            module.initialize(new DetectionModuleContext(
                    new PlatformContext(Platform.PAPER, new InlineScheduler()),
                    FoundationConfig.defaults(), Logger.getLogger("p33")));
            module.setProbeEligibility(id -> classification.apply(id).canProbe());
            module.start();
        }
    }

    private static ClientDetectionConfig bundledConfigStatic() throws Exception {
        Path directory = Files.createTempDirectory("p33-config");
        Path file = directory.resolve("client-detection.yml");
        try (InputStream stream = P33PassiveDetectionTest.class.getResourceAsStream("/client-detection.yml")) {
            Files.write(file, stream.readAllBytes());
        }
        return ClientDetectionConfig.load(directory, Logger.getAnonymousLogger());
    }

    private static final class RecordingTransport implements ClientProbeTransport {
        private final List<ProbeRequest> requests = new ArrayList<>();
        private final AtomicInteger uiOpenings = new AtomicInteger();
        private final AtomicInteger worldMutations = new AtomicInteger();
        private final AtomicInteger playerStateChanges = new AtomicInteger();
        private final AtomicInteger activeOperations = new AtomicInteger();
        private final AtomicInteger cleanups = new AtomicInteger();
        private ClientResponseFixtures.Rendered rendered;

        @Override
        public ProbeHandle send(ProbeRequest request, Consumer<ProbeResponse> response) {
            requests.add(request);
            activeOperations.incrementAndGet();
            if (request.probes().get(0).transport().opensClientUi()) {
                uiOpenings.incrementAndGet();
                worldMutations.incrementAndGet();
            }
            response.accept(ClientResponseFixtures.bind(request,
                    rendered != null ? rendered : ClientResponseFixtures.clean(request.probes())));
            cleanups.incrementAndGet();
            activeOperations.decrementAndGet();
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }

        @Override public void stop() { }
    }

    private static final class InlineScheduler implements Scheduler {
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) { task.run(); return handle(); }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }

        private static TaskHandle handle() {
            return new TaskHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }
    }
}
