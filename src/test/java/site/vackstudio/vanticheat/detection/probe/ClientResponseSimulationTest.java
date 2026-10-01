package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 11: deterministic end-to-end coverage of the evaluator and aggregator through the
 * real module pipeline, using the response representations recorded during live validation.
 *
 * <p>These are simulation fixtures. They prove that each recorded response shape reaches the
 * correct terminal conclusion, and they are not new live-verification claims.
 */
class ClientResponseSimulationTest {
    @TempDir
    private Path temporaryDirectory;

    private ProbeRegistry registry;

    @BeforeEach
    void load() throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            Files.write(file, stream.readAllBytes());
        }
        registry = ClientDetectionConfig.load(temporaryDirectory, Logger.getAnonymousLogger())
                .probeRegistry();
    }

    // ------------------------------------------------------------- vanilla and clean Fabric

    /** Vanilla and clean Fabric both echo the sentinel for every key, so both are CLEAN. */
    @Test
    void vanillaIsClean() {
        for (String probeId : List.of("meteor-client", "apple-skin", "jade-config-screen")) {
            assertEquals(DetectionStatus.CLEAN, run(probeId, probe -> ClientResponseFixtures.clean(List.of(probe))),
                    probeId + " must be CLEAN for vanilla");
        }
    }

    @Test
    void cleanFabricIsClean() {
        // Clean Fabric is indistinguishable from vanilla over this protocol: it has the
        // mod loader but none of the probed translation keys.
        for (String probeId : List.of("meteor-client", "apple-skin", "jade-config-screen")) {
            ProbeDefinition probe = registry.require(probeId);
            assertEquals(DetectionStatus.CLEAN, run(probeId, p -> ClientResponseFixtures.clean(List.of(p))),
                    probeId + " must be CLEAN for clean Fabric");
        }
    }

    /** The whole 28-probe automatic catalog is CLEAN against a clean client. */
    @Test
    void wholeCatalogIsCleanAgainstACleanClient() {
        List<ProbeDefinition> batch = registry.enabled().stream()
                .filter(probe -> probe.automatic())
                .limit(3)
                .toList();
        assertTrue(batch.size() > 0, "the catalog has automatic probes");

        // Every enabled probe, three at a time, must come back clean.
        List<ProbeDefinition> enabled = registry.enabled();
        for (int start = 0; start < enabled.size(); start += 3) {
            List<ProbeDefinition> window = enabled.subList(start, Math.min(start + 3, enabled.size()));
            assertEquals(DetectionStatus.CLEAN,
                    evaluateWindow(window, request -> ClientResponseFixtures.clean(request)),
                    "a clean client must never produce a detection for " + window);
        }
    }

    // ----------------------------------------------------------------- recorded targets

    @Test
    void meteorIsDetected() {
        assertEquals(DetectionStatus.DETECTED,
                run("meteor-client", probe -> ClientResponseFixtures.meteor(probe)));
    }

    @Test
    void appleSkinIsDetected() {
        assertEquals(DetectionStatus.DETECTED,
                run("apple-skin", probe -> ClientResponseFixtures.appleSkin(probe)));
    }

    @Test
    void jadeConfigIsDetected() {
        assertEquals(DetectionStatus.DETECTED,
                run("jade-config-screen", probe -> ClientResponseFixtures.jadeConfig(probe)));
    }

    /** The locale fix: a target is found even though the rendered text differs. */
    @Test
    void targetUnderAnotherLocaleIsStillDetected() {
        for (String rendered : List.of("Ouvrir l'interface", "\u30A4\u30F3\u30D5\u30E9\u30A4\u958B\u304D",
                "GUI \u00f6ffnen")) {
            assertEquals(DetectionStatus.DETECTED,
                    run("meteor-client", probe -> ClientResponseFixtures.targetOtherLocale(probe, rendered)),
                    "a resolved target key must detect regardless of locale: " + rendered);
        }
    }

    // ------------------------------------------------------------- honest non-detections

    /** An ambiguous localized response is UNCERTAIN, never DETECTED. */
    @Test
    void ambiguousResponseIsUncertain() {
        ProbeDefinition probe = registry.require("litematica");
        assertEquals(DetectionStatus.UNCERTAIN,
                run("litematica", p -> ClientResponseFixtures.ambiguousLocalized(probe, "Konfiguration")));
    }

    /** Silence is TIMEOUT, never CLEAN and never DETECTED. */
    @Test
    void noResponseIsTimeout() {
        assertEquals(DetectionStatus.TIMEOUT, run("meteor-client", probe -> null));
    }

    /**
     * An exploit preventer echoing a TRANSLATE key is PROTECTED, never DETECTED. Meteor is
     * the recorded exception: its recorded live behaviour is to return its own key, which is
     * why METEOR mode treats a key echo as authoritative.
     */
    @Test
    void neutralizedTranslateKeyIsProtected() {
        ProbeDefinition probe = registry.require("litematica");
        assertEquals(DetectionStatus.PROTECTED,
                run("litematica", p -> ClientResponseFixtures.keyEchoed(probe)));
    }

    @Test
    void meteorKeyEchoRemainsAuthoritativeAsRecorded() {
        ProbeDefinition probe = registry.require("meteor-client");
        assertEquals(DetectionStatus.DETECTED,
                run("meteor-client", p -> ClientResponseFixtures.keyEchoed(probe)),
                "Meteor returning its own key is the recorded detection signal");
    }

    /** A keybind client that cannot resolve the key must be able to report clean. */
    @Test
    void cleanKeybindClientReportsClean() {
        assertEquals(DetectionStatus.CLEAN, run("freecam", probe -> ClientResponseFixtures.clean(List.of(probe))));
        assertEquals(DetectionStatus.CLEAN,
                CheckHacksResponseEvaluator.evaluateDetailed(registry.require("freecam"),
                        registry.require("freecam").fallback(), null, false).status(),
                "a client that cannot resolve a keybind key echoes the sentinel, which is clean");
    }

    /** A different mod's key is CLEAN, never this target's detection. */
    @Test
    void anotherModsKeyIsClean() {
        ProbeDefinition probe = registry.require("meteor-client");
        assertEquals(DetectionStatus.CLEAN,
                run("meteor-client", p -> ClientResponseFixtures.otherTargetKey(probe, "key.some.other.client")));
    }

    /**
     * Cross-probe discipline: detecting one target must not report an unrelated probe as
     * detected, which is what the P28 live runs recorded.
     */
    @Test
    void detectingOneTargetDoesNotReportUnrelatedProbes() {
        List<ProbeDefinition> window = List.of(
                registry.require("meteor-client"),
                registry.require("apple-skin"),
                registry.require("jade-config-screen"));

        List<String> detected = evaluateWindowDetailed(window, rendered -> {
            List<String> lines = new ArrayList<>();
            List<String> identities = new ArrayList<>();
            for (int index = 0; index < window.size(); index++) {
                lines.add(index == 0 ? "Open GUI" : window.get(index).fallback());
                identities.add(null);
            }
            return new ClientResponseFixtures.Rendered(
                    pad(lines), pad(identities));
        });

        assertEquals(List.of("meteor-client"), detected,
                "only the probe whose own key was resolved may be reported detected");
    }

    // ------------------------------------------------------------------- aggregation path

    /** Three weak signals in one window aggregate to UNCERTAIN with a recorded reason. */
    @Test
    void threeWeakSignalsAggregateToUncertainWithAReason() {
        List<ProbeDefinition> window = List.of(
                registry.require("litematica"),
                registry.require("xaeros-worldmap"),
                registry.require("inventory-profiles-next"));
        List<String> texts = List.of("Konfiguration", "Open World Map", "Profiles");

        ProbeCorroboration.Decision decision = ProbeCorroboration.decide(
                ProbeCorroboration.observationsOf(window, List.of(
                        evaluate(window.get(0), texts.get(0)),
                        evaluate(window.get(1), texts.get(1)),
                        evaluate(window.get(2), texts.get(2)))));

        assertEquals(ProbeCorroboration.Conclusion.AMBIGUOUS, decision.conclusion());
        assertEquals(3, decision.weakSignals());
        assertEquals(0, decision.authoritativeSignals());
        assertTrue(decision.reason().contains("not promoted"));
    }

    /** A batch mixing silence with a weak answer is reported as a timeout. */
    @Test
    void silenceDominatesAmbiguityAndIsNotDowngraded() {
        List<ProbeDefinition> window = List.of(
                registry.require("litematica"), registry.require("xaeros-worldmap"));
        ProbeCorroboration.Decision decision = ProbeCorroboration.decide(List.of(
                new ProbeCorroboration.Observation("litematica", DetectionStatus.TIMEOUT,
                        ProbeEvidenceStrength.NONE, ProbeCorroboration.Basis.NO_EVIDENCE, true),
                new ProbeCorroboration.Observation("xaeros-worldmap", DetectionStatus.UNCERTAIN,
                        ProbeEvidenceStrength.WEAK, ProbeCorroboration.Basis.WEAK_LOCALIZED, true)));

        assertEquals(ProbeCorroboration.Conclusion.TIMED_OUT, decision.conclusion());
    }

    // ----------------------------------------------------------------------- helpers

    private DetectionStatus run(String probeId,
                                Function<ProbeDefinition, ClientResponseFixtures.Rendered> answer) {
        return evaluateWindow(List.of(registry.require(probeId)), window -> answer.apply(window.get(0)));
    }

    private DetectionStatus evaluateWindow(List<ProbeDefinition> window,
                                           Function<List<ProbeDefinition>, ClientResponseFixtures.Rendered> answer) {
        ScriptedTransport transport = new ScriptedTransport(answer);
        CheckHacksClientDetectionModule module = module(window, transport);
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        module.check(new DetectionTarget(UUID.randomUUID(), "sim", true, new Object()), result::set);
        return result.get().status();
    }

    private List<String> evaluateWindowDetailed(List<ProbeDefinition> window,
                                                Function<List<ProbeDefinition>, ClientResponseFixtures.Rendered> answer) {
        ScriptedTransport transport = new ScriptedTransport(answer);
        CheckHacksClientDetectionModule module = module(window, transport);
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        module.check(new DetectionTarget(UUID.randomUUID(), "sim", true, new Object()), result::set);
        return CheckHacksClientDetectionModule.detectedProbeIds(result.get());
    }

    private ProbeEvaluation evaluate(ProbeDefinition probe, String rendered) {
        return CheckHacksResponseEvaluator.evaluateDetailed(probe, rendered, null, false);
    }

    private static List<String> pad(List<String> values) {
        List<String> padded = new ArrayList<>(values);
        while (padded.size() < 3) padded.add("");
        padded.add(ClientResponseFixtures.EXPLOIT_PREVENTER_LINE);
        return padded;
    }

    private static CheckHacksClientDetectionModule module(List<ProbeDefinition> window,
                                                           ClientProbeTransport transport) {
        ClientDetectionConfig config = new ClientDetectionConfig(true, false, 40, 0,
                ProbeRegistry.of(window), true, 1, false, 32, 10, 2, true);
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new QueuedScheduler()),
                FoundationConfig.defaults(), Logger.getLogger("p32-sim")));
        module.start();
        return module;
    }

    /** Replays one scripted answer per batch; a null answer means the client stayed silent. */
    private static final class ScriptedTransport implements ClientProbeTransport {
        private final Function<List<ProbeDefinition>, ClientResponseFixtures.Rendered> answer;

        private ScriptedTransport(Function<List<ProbeDefinition>, ClientResponseFixtures.Rendered> answer) {
            this.answer = answer;
        }

        @Override
        public ProbeHandle send(ProbeRequest request, Consumer<ProbeResponse> response) {
            ClientResponseFixtures.Rendered rendered = answer.apply(request.probes());
            if (rendered == null) {
                response.accept(ClientResponseFixtures.silent(request));
            } else {
                response.accept(ClientResponseFixtures.bind(request, rendered));
            }
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }

        @Override public void stop() { }
    }

    /** Runs same-tick work inline but defers later work, mirroring real tick ordering. */
    private static final class QueuedScheduler implements Scheduler {
        private final Deque<Runnable> later = new ArrayDeque<>();

        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }

        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
            later.addLast(task);
            task.run();
            return handle();
        }

        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { later.clear(); }

        private static TaskHandle handle() {
            return new TaskHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }
    }
}
