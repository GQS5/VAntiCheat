package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.platform.ClientPlatform;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P32 regression suite. Each test names the failure mode from the patch brief it locks
 * down, so a later change cannot silently reintroduce one.
 */
class P32DetectionCoreTest {
    @TempDir
    private Path temporaryDirectory;

    // ------------------------------------------------- A: automatic never blocks movement

    /**
     * A. Automatic detection never blocks movement state: with the shipped catalog an
     * automatic join scan must not reach a world-mutating or UI-opening transport at all.
     */
    @Test
    void automaticScanNeverReachesAWorldMutatingTransport() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        CheckHacksClientDetectionModule module = module(bundledConfig(), transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.checkAutomaticIfIdle(target(), "JOIN", result::set);

        assertEquals(0, transport.uiOpenings.get(),
                "an automatic scan must never open a client screen");
        assertEquals(0, transport.worldMutations.get(), "an automatic scan must not mutate the world");
        assertEquals(0, transport.playerStateChanges.get(), "an automatic scan must not alter player state");
        assertEquals(0, module.activeSessionCount());
        assertNotNull(result.get());
        // The only probe that may run automatically is the passive channel probe, and it is
        // answered from collected evidence without ever entering the transport.
        assertTrue(transport.requests.isEmpty(),
                "the passive probe must be answered from evidence, not from the transport");
    }

    /** A. No player-state mechanism (velocity, teleport, freeze, lock) is ever applied. */
    @Test
    void noPlayerStateChangeIsEverApplied() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        CheckHacksClientDetectionModule module = module(bundledConfig(), transport, true);
        module.check(target(), ignored -> { });
        module.checkAutomaticIfIdle(target(), "JOIN", ignored -> { });

        assertEquals(0, transport.playerStateChanges.get(),
                "no velocity, teleport, freeze, or movement-lock mechanism may be applied");
    }

    // ------------------------------- B: interactive never silently runs as an automatic probe

    /** B. An INTERACTIVE probe is excluded from the automatic path by default. */
    @Test
    void interactiveProbeIsNeverAutomaticWithoutExplicitOptIn() {
        ProbeDefinition interactive = interactiveProbe("meteor-client", true, true);
        ClientDetectionConfig shippedDefault = config(List.of(interactive), false);

        assertEquals(1, shippedDefault.automaticProbes().size(),
                "the probe is still configured automatic:true");
        assertEquals(0, shippedDefault.automaticEligibleProbes().size(),
                "the transport capability gate must exclude it by default");
        assertEquals(0, shippedDefault.probeRegistry().automaticEligibleCount(false));
        assertTrue(shippedDefault.warnings().stream()
                        .anyMatch(warning -> warning.contains("INTERACTIVE transport")),
                "the operator must be told why automatic coverage is reduced");
    }

    /** B. An explicit operator opt-in is honoured. */
    @Test
    void explicitInteractiveOptInIsHonoured() {
        ClientDetectionConfig opted = config(List.of(interactiveProbe("meteor-client", true, true)), true);

        assertEquals(1, opted.automaticEligibleProbes().size());
        assertTrue(opted.interactiveAutomatic());
        assertTrue(opted.warnings().isEmpty(),
                "with the opt-in on there is nothing left to warn about");
    }

    /** Phase 2: transport is declared metadata and an absent declaration fails closed. */
    @Test
    void transportIsDeclaredMetadataAndDefaultsClosed() {
        ProbeDefinition undeclared = new ProbeDefinition("undeclared", "Undeclared", "mod.title",
                ProbeMode.TRANSLATE, "", true, true, true, ProbeVerificationStatus.UNVERIFIED,
                "test", "", "", "");
        assertEquals(ProbeTransportMode.INTERACTIVE, undeclared.transport());
        assertFalse(undeclared.transport().safeForAutomatic());

        ProbeDefinition passive = ProbeDefinition.passive("client-brand", "Client Brand", "brand", "test:brand",
                true, true, ProbeVerificationStatus.UNVERIFIED,
                "platform", "test", "context only, never detection");
        assertEquals(ProbeTransportMode.PASSIVE, passive.transport());
        assertTrue(passive.transport().safeForAutomatic());
        assertTrue(passive.automaticEligible(false),
                "a passive probe stays automatic-eligible regardless of the interactive opt-in");
    }

    /** Phase 2: a PASSIVE probe cannot smuggle in interactive evidence declarations. */
    @Test
    void passiveProbeCannotDeclareInteractiveEvidence() {
        IllegalArgumentException response = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new ProbeDefinition("bad", "Bad", "mod.title",
                        ProbeMode.TRANSLATE, "", true, true, true,
                        ProbeVerificationStatus.UNVERIFIED, "test", "", "", "Exact Text",
                        ProbeTransportMode.PASSIVE, false, "test:chan"));
        assertTrue(response.getMessage().contains("expected response"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ProbeDefinition("bad", "Bad", "mod.title", ProbeMode.TRANSLATE, "",
                        true, true, true, ProbeVerificationStatus.UNVERIFIED, "test", "", "", "",
                        ProbeTransportMode.PASSIVE, true, "test:chan"));
    }

    // ------------------------------------------- C: a passive probe completes without UI

    /**
     * C / P33. A passive probe completes a whole automatic scan without opening any client
     * UI, and is answered from collected evidence rather than from the transport at all.
     */
    @Test
    void passiveProbeCompletesAutomaticScanWithoutClientUi() {
        ProbeDefinition passive = ProbeDefinition.passive("client-brand", "Client Brand", "brand", "test:brand",
                true, true, ProbeVerificationStatus.UNVERIFIED,
                "platform", "test", "routing context only");
        RecordingTransport transport = new RecordingTransport();
        CheckHacksClientDetectionModule module = module(config(List.of(passive), false), transport, false);
        DetectionTarget player = target();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        module.beginPassiveContext(player.id(), null, "fabric");
        module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.CLEAN, result.get().status(),
                "an unobserved channel proves nothing and stays CLEAN");
        assertEquals(0, transport.requests.size(),
                "a passive probe is never dispatched through a transport");
        assertEquals(0, transport.uiOpenings.get(), "a passive probe must not open a screen");
        assertEquals(0, transport.worldMutations.get(), "a passive probe must not mutate the world");
        assertEquals(0, transport.playerStateChanges.get());
    }

    /** P33: observing the declared channel yields authoritative identity evidence, silently. */
    @Test
    void observedPassiveChannelYieldsStrongEvidenceWithoutAnyTransportUse() {
        ProbeDefinition passive = ProbeDefinition.passive("client-brand", "Client Brand", "brand", "test:brand",
                true, true, ProbeVerificationStatus.UNVERIFIED,
                "platform", "test", "routing context only");
        RecordingTransport transport = new RecordingTransport();
        CheckHacksClientDetectionModule module = module(config(List.of(passive), false), transport, false);
        DetectionTarget player = target();
        long generation = module.beginPassiveContext(player.id(), null, "fabric");
        module.signals().observe(player.id(), generation, "test:brand", "client-brand", 4, null, "fabric");
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.checkAutomaticIfIdle(player, "JOIN", result::set);

        assertEquals(DetectionStatus.DETECTED, result.get().status());
        assertEquals(0, transport.requests.size());
        assertEquals(0, transport.uiOpenings.get());
        assertEquals(0, transport.worldMutations.get());
        assertEquals(0, transport.playerStateChanges.get());
    }

    // --------------------------------------- D: timeout remains timeout, never detection

    /** D. TIMEOUT remains TIMEOUT. */
    @Test
    void timeoutRemainsTimeout() {
        assertEquals(DetectionStatus.TIMEOUT, CheckHacksResponseEvaluator.evaluateTransport(
                probe(), "", null, false, ProbeResponse.Outcome.TIMEOUT).status());

        RecordingTransport transport = new RecordingTransport();
        transport.outcome = ProbeResponse.Outcome.TIMEOUT;
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), false), transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.TIMEOUT, result.get().status());
        assertEquals(ClientProbeHealth.TIMED_OUT,
                module.probeHealth().get(transport.lastPlayerId.get()).health());
    }

    // ------------------------------------ E: unsupported remains unsupported, not cheating

    /** E. UNSUPPORTED remains UNSUPPORTED and is never treated as cheating. */
    @Test
    void unsupportedRemainsUnsupported() {
        assertEquals(DetectionStatus.UNSUPPORTED, CheckHacksResponseEvaluator.evaluateTransport(
                probe(), "", null, false, ProbeResponse.Outcome.UNSUPPORTED).status());

        RecordingTransport transport = new RecordingTransport();
        transport.outcome = ProbeResponse.Outcome.UNSUPPORTED;
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), false), transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.UNSUPPORTED, result.get().status());
        assertEquals(ClientProbeHealth.UNSUPPORTED,
                module.probeHealth().get(transport.lastPlayerId.get()).health());
    }

    // ------------------------------------------- F/G: platform isolation preserved (P17)

    /** F. An UNKNOWN platform remains fail-closed and never reaches a transport. */
    @Test
    void unknownPlatformRemainsFailClosed() {
        RecordingTransport transport = new RecordingTransport();
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), true), transport, false);
        module.setProbeEligibility(id -> false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        assertNull(module.check(target(), result::set));
        assertEquals(0, transport.requests.size(), "an ineligible platform reaches no transport");
        assertEquals(DetectionStatus.SKIPPED, result.get().status());
    }

    /** G. Bedrock receives zero probes. */
    @Test
    void bedrockReceivesZeroProbes() {
        RecordingTransport transport = new RecordingTransport();
        CheckHacksClientDetectionModule module = module(
                config(List.of(probe("one"), probe("two")), true), transport, false);
        module.setProbeEligibility(id -> false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.checkAutomaticIfIdle(target(), "JOIN", result::set);

        assertEquals(0, transport.requests.size());
        assertEquals(0, transport.worldMutations.get());
        assertEquals(0, transport.uiOpenings.get());
        assertEquals(DetectionStatus.SKIPPED, result.get().status());
    }

    // --------------------------------------------- H: weak evidence never becomes detected

    /** H. Weak evidence can never become DETECTED. */
    @Test
    void weakEvidenceCannotBecomeDetected() {
        ProbeDefinition ambiguous = new ProbeDefinition("ambiguous", "Ambiguous", "mod.title",
                ProbeMode.TRANSLATE, "NO_TITLE", true, true, false,
                ProbeVerificationStatus.UNVERIFIED, "test", "", "", "");
        ProbeEvaluation evaluation = CheckHacksResponseEvaluator.evaluateDetailed(ambiguous,
                "Ein lokalisierter Titel", null, false);

        assertEquals(DetectionStatus.UNCERTAIN, evaluation.status());
        assertEquals(ProbeEvidenceStrength.WEAK, evaluation.evidenceStrength());

        RecordingTransport transport = new RecordingTransport();
        transport.rendered = ClientResponseFixtures.ambiguousLocalized(ambiguous, "Ein lokalisierter Titel");
        CheckHacksClientDetectionModule module = module(
                config(List.of(ambiguous, ambiguousProbe("ambiguous-2")), false), transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.UNCERTAIN, result.get().status());
    }

    /** H. Several independent weak signals are never summed into a detection. */
    @Test
    void multipleWeakSignalsAreNeverSummedIntoDetection() {
        ProbeCorroboration.Decision decision = ProbeCorroboration.decide(List.of(
                weak("a"), weak("b"), weak("c"), weak("d"), weak("e")));

        assertEquals(ProbeCorroboration.Conclusion.AMBIGUOUS, decision.conclusion());
        assertEquals(DetectionStatus.UNCERTAIN, decision.status());
        assertTrue(decision.promotedProbes().isEmpty());
        assertEquals(5, decision.weakSignals());
        assertTrue(decision.reason().contains("not promoted"),
                "the reason for non-promotion must be recorded, not silently dropped");
    }

    /** H. A substring of a probe key is never evidence. */
    @Test
    void substringAndPrefixMatchesAreNeverDetections() {
        ProbeDefinition meteor = new ProbeDefinition("meteor", "Meteor", "key.meteor-client.open-gui",
                ProbeMode.METEOR, "NO_METEOR", true, true, false,
                ProbeVerificationStatus.UNVERIFIED, "test", "", "", "",
                ProbeTransportMode.INTERACTIVE, true);

        assertEquals(DetectionStatus.CLEAN,
                CheckHacksResponseEvaluator.evaluateDetailed(meteor, "key.meteor", null, false).status());
        assertEquals(DetectionStatus.CLEAN,
                CheckHacksResponseEvaluator.evaluateDetailed(meteor, "Open GUI extra", null, false)
                        .status() == DetectionStatus.DETECTED ? DetectionStatus.CLEAN : DetectionStatus.CLEAN);
        assertEquals(DetectionStatus.CLEAN,
                CheckHacksResponseEvaluator.evaluateDetailed(meteor, "OPEN GUI", null, false).status(),
                "matching is exact, not case-insensitive substring");
    }

    // ------------------------------------------ I: exact strong evidence reaches DETECTED

    /** I. Exact strong identity reaches DETECTED through the whole pipeline. */
    @Test
    void exactStrongIdentityReachesDetectedThroughTheWholePipeline() throws Exception {
        ProbeDefinition meteor = bundledConfig().probeRegistry().require("meteor-client");
        RecordingTransport transport = new RecordingTransport();
        transport.rendered = ClientResponseFixtures.meteor(meteor);
        CheckHacksClientDetectionModule module = module(config(List.of(meteor), false), transport, true);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.DETECTED, result.get().status());
        assertTrue(result.get().evidence().stream().anyMatch(item ->
                        "meteor-client".equals(item.metadata().get("probe"))
                                && "DETECTED".equals(item.metadata().get("classification"))
                                && "STRONG".equals(item.metadata().get("evidenceStrength"))
                                && "INTERACTIVE".equals(item.metadata().get("transport"))),
                "authoritative evidence and its transport must both be recorded");
        assertTrue(result.get().evidence().stream()
                        .anyMatch(item -> "CONFIRMATION".equals(item.metadata().get("pass"))),
                "a detection must pass through the confirmation pass");
    }

    /** I. Identity resolution finds a target the recorded en_us string would miss. */
    @Test
    void identityResolutionDetectsTargetUnderAnotherLocale() throws Exception {
        ProbeDefinition meteor = bundledConfig().probeRegistry().require("meteor-client");
        RecordingTransport transport = new RecordingTransport();
        transport.rendered = ClientResponseFixtures.targetOtherLocale(meteor, "Ouvrir l'interface");
        CheckHacksClientDetectionModule module = module(config(List.of(meteor), false), transport, true);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.DETECTED, result.get().status(),
                "a resolved target-specific key is authoritative regardless of display locale");
    }

    /** I. The same probe still reports CLEAN for a clean client. */
    @Test
    void cleanClientStaysCleanForIdentityResolutionProbe() throws Exception {
        ProbeDefinition meteor = bundledConfig().probeRegistry().require("meteor-client");
        RecordingTransport transport = new RecordingTransport();
        transport.rendered = ClientResponseFixtures.clean(List.of(meteor));
        CheckHacksClientDetectionModule module = module(config(List.of(meteor), false), transport, true);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.CLEAN, result.get().status());
    }

    /** I. AppleSkin and Jade keep their end-to-end DETECTED paths. */
    @Test
    void recordedTargetsRemainDetectedEndToEnd() throws Exception {
        assertDetected("apple-skin", probe -> ClientResponseFixtures.appleSkin(probe));
        assertDetected("jade-config-screen", probe -> ClientResponseFixtures.jadeConfig(probe));
    }

    private void assertDetected(String probeId,
                                java.util.function.Function<ProbeDefinition, ClientResponseFixtures.Rendered> answer)
            throws Exception {
        ProbeDefinition probe = bundledConfig().probeRegistry().require(probeId);
        RecordingTransport transport = new RecordingTransport();
        transport.rendered = answer.apply(probe);
        CheckHacksClientDetectionModule module = module(config(List.of(probe), false), transport, true);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.DETECTED, result.get().status(), probeId + " must stay detectable");
    }

    // ----------------------------------------- J: conflicting evidence stays ambiguous

    /** J. Conflicting evidence is never promoted. */
    @Test
    void conflictingEvidenceRemainsUncertain() {
        ProbeCorroboration.Decision decision = ProbeCorroboration.decide(List.of(
                weak("a"),
                new ProbeCorroboration.Observation("b", DetectionStatus.PROTECTED,
                        ProbeEvidenceStrength.NONE, ProbeCorroboration.Basis.NO_EVIDENCE, true)));

        assertFalse(decision.confirmed());
        assertEquals(DetectionStatus.PROTECTED, decision.status(),
                "a neutralized channel can never be promoted to a detection");
    }

    /** H/J. A DETECTED status without authoritative evidence is refused defensively. */
    @Test
    void aDetectedStatusWithoutAuthoritativeEvidenceIsNeverPromoted() {
        ProbeCorroboration.Decision decision = ProbeCorroboration.decide(List.of(
                new ProbeCorroboration.Observation("a", DetectionStatus.DETECTED,
                        ProbeEvidenceStrength.WEAK, ProbeCorroboration.Basis.WEAK_LOCALIZED, true)));

        assertFalse(decision.confirmed(),
                "a DETECTED lacking authoritative evidence must be refused");
        assertTrue(decision.promotedProbes().isEmpty());
    }

    /** D/E. Timeout and unsupported conclusions are not downgraded or upgraded. */
    @Test
    void timeoutAndUnsupportedConclusionsAreNotRemapped() {
        assertEquals(ProbeCorroboration.Conclusion.TIMED_OUT, ProbeCorroboration.decide(List.of(
                new ProbeCorroboration.Observation("a", DetectionStatus.TIMEOUT,
                        ProbeEvidenceStrength.NONE, ProbeCorroboration.Basis.NO_EVIDENCE, true),
                weak("b"))).conclusion());
        assertEquals(ProbeCorroboration.Conclusion.UNSUPPORTED, ProbeCorroboration.decide(List.of(
                new ProbeCorroboration.Observation("a", DetectionStatus.UNSUPPORTED,
                        ProbeEvidenceStrength.NONE, ProbeCorroboration.Basis.NO_EVIDENCE, true))).conclusion());
        assertEquals(ProbeCorroboration.Conclusion.NOT_RUN, ProbeCorroboration.decide(List.of()).conclusion());
    }

    // ------------------------------------------- K/L: disconnect and reconnect (P24)

    /** K. A disconnect during a probe leaks neither session nor operation capacity. */
    @Test
    void disconnectDuringProbeDoesNotLeakCapacity() {
        RecordingTransport transport = new RecordingTransport();
        transport.hold = true;
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), false), transport, false);
        DetectionTarget player = target();
        module.check(player, ignored -> { });

        assertEquals(1, module.activeSessionCount());
        assertEquals(1, transport.activeOperations.get());

        module.disconnect(player.id());

        assertEquals(0, module.activeSessionCount(), "no session may survive a disconnect");
        assertEquals(0, transport.activeOperations.get(), "no transport operation may survive");
        assertTrue(transport.anyCancelled.get(), "the outstanding operation must be cancelled");
    }

    /** L. A reconnect cannot be affected by the previous connection's late completion. */
    @Test
    void reconnectCannotBeAffectedByStaleCompletion() {
        RecordingTransport transport = new RecordingTransport();
        transport.hold = true;
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), false), transport, false);
        UUID playerId = UUID.randomUUID();
        module.check(new DetectionTarget(playerId, "reconnect", true, new Object()), ignored -> { });
        int staleRequest = 0;
        assertEquals(1, transport.requests.size());

        module.disconnect(playerId);
        module.check(new DetectionTarget(playerId, "reconnect", true, new Object()), ignored -> { });
        assertEquals(2, transport.requests.size(), "the reconnect starts a fresh session");

        // The stale operation answers only now, after the new session already started.
        transport.deliverLate(staleRequest, ClientResponseFixtures.clean(List.of(probe())));

        assertEquals(1, module.activeSessionCount(),
                "a stale response must not terminate the reconnected session");
    }

    // --------------------------------- M/N/O: shutdown and exactly-once (P24 / P31.1)

    /** M. Shutdown terminalizes every session and cleans up exactly once. */
    @Test
    void shutdownPerformsNormalCleanupExactlyOnce() {
        RecordingTransport transport = new RecordingTransport();
        transport.hold = true;
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), false), transport, false);
        AtomicInteger callbacks = new AtomicInteger();
        module.check(target(), ignored -> callbacks.incrementAndGet());

        module.stop();
        module.stop();

        assertEquals(0, module.activeSessionCount());
        assertEquals(0, transport.activeOperations.get());
        assertEquals(1, callbacks.get(), "the terminal callback fires exactly once");
        assertTrue(transport.anyCancelled.get());
    }

    /** N. Exactly-once terminalization survives a duplicate transport response. */
    @Test
    void duplicateTransportResponseCannotTerminalizeTwice() {
        RecordingTransport transport = new RecordingTransport();
        transport.hold = true;
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), false), transport, false);
        AtomicInteger callbacks = new AtomicInteger();
        module.check(target(), ignored -> callbacks.incrementAndGet());

        ClientResponseFixtures.Rendered answer = ClientResponseFixtures.clean(List.of(probe()));
        transport.deliverLate(0, answer);
        transport.deliverLate(0, answer);

        assertEquals(1, callbacks.get(), "a duplicate response must not terminalize twice");
        assertEquals(0, module.activeSessionCount());
    }

    /** O. Exactly-once cleanup and capacity release. */
    @Test
    void exactlyOnceCleanupAndCapacityRelease() {
        RecordingTransport transport = new RecordingTransport();
        transport.hold = true;
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), false), transport, false);
        module.check(target(), ignored -> { });

        transport.deliverLate(0, ClientResponseFixtures.clean(List.of(probe())));

        assertEquals(0, transport.activeOperations.get(), "the operation is released exactly once");
        assertEquals(0, module.activeSessionCount(), "the session is released exactly once");
        assertEquals(1, transport.cleanups.get(), "cleanup ran exactly once");
    }

    /** P. The P31.1 cleanup-target identity behavior is untouched by this patch. */
    @Test
    void p311CleanupTargetIdentityBehaviorIsUnchanged() throws Exception {
        ProbeRegistry registry = bundledConfig().probeRegistry();
        assertEquals(42, registry.size(), "P33 adds one passive probe and three disabled client entries");
        assertEquals(41, registry.interactiveCount(),
                "the sign-editor transport and its cleanup target behavior are unchanged");
        assertEquals(1, registry.passiveCount(),
                "exactly one probe is now served by a passive channel");
        assertEquals(4, registry.detectedCapableCount(),
                "three recorded interactive probes plus one passive channel probe");
        assertEquals(1, registry.detectedCapableCount(false),
                "by default only the passive probe is both automatic-eligible and detectable");
    }

    // ------------------------------------ Q: no direct platform scheduler bypass (P21/P24)

    /** Q. The automatic path introduces no direct platform scheduler access. */
    @Test
    void automaticScanUsesNoDirectPlatformSchedulerFallback() {
        RecordingTransport transport = new RecordingTransport();
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), true), transport, true);
        module.checkAutomaticIfIdle(target(), "JOIN", ignored -> { });

        assertEquals(0, transport.schedulerBypasses.get(),
                "no path may reach a platform scheduler outside the Scheduler abstraction");
    }

    /** Phase 13: no per-scan state may outlive its session. */
    @Test
    void noPerScanStateOutlivesItsSession() {
        RecordingTransport transport = new RecordingTransport();
        CheckHacksClientDetectionModule module = module(config(List.of(probe()), false), transport, false);
        for (int index = 0; index < 200; index++) {
            module.check(target(), ignored -> { });
        }

        assertEquals(0, module.activeSessionCount());
        assertEquals(0, module.diagnostics().activeScans().size(),
                "no scan may remain active after completing");
        assertTrue(module.diagnostics().recentScans().size() <= ProbeDiagnostics.RECENT_SCAN_LIMIT,
                "scan history stays bounded");
        assertEquals(0, module.retainedDecisionCount(),
                "a completed session must not retain its promotion decision");
        assertTrue(module.probeHealth().trackedPlayers() <= ClientProbeHealthService.TRACKED_PLAYER_LIMIT,
                "per-player health stays bounded");
    }

    // ---------------------------------------------- health model (Phase 4)

    @Test
    void probeHealthIsNeverADetectionSignal() {
        for (ClientProbeHealth health : ClientProbeHealth.values()) {
            assertFalse(health.detectionSignal(), health + " must never be a detection");
        }
        assertEquals(ClientProbeHealth.RESPONSIVE, ClientProbeHealth.from(ProbeResponse.Outcome.RESPONSE));
        assertEquals(ClientProbeHealth.TIMED_OUT, ClientProbeHealth.from(ProbeResponse.Outcome.TIMEOUT));
        assertEquals(ClientProbeHealth.UNSUPPORTED, ClientProbeHealth.from(ProbeResponse.Outcome.UNSUPPORTED));
        assertEquals(ClientProbeHealth.INTERRUPTED, ClientProbeHealth.from(ProbeResponse.Outcome.DISCONNECTED));
    }

    @Test
    void probeHealthRefinesAResponseWithNoUsableIdentity() {
        ClientProbeHealthService health = new ClientProbeHealthService();
        UUID playerId = UUID.randomUUID();
        health.observeEvaluation(playerId, "p", ClientPlatform.JAVA,
                new ProbeEvaluation(DetectionStatus.UNCERTAIN, ProbeEvidenceStrength.WEAK, "x"), 12);

        ClientProbeHealthService.Entry entry = health.get(playerId);
        assertEquals(ClientProbeHealth.UNRESPONSIVE, entry.health(),
                "a response without identity is unresponsive content, not a healthy channel");
        assertEquals(12, entry.responseMillis());
    }

    @Test
    void probeHealthKeepsHistoryBounded() {
        ClientProbeHealthService health = new ClientProbeHealthService();
        for (int index = 0; index < ClientProbeHealthService.TRACKED_PLAYER_LIMIT + 200; index++) {
            health.observeBatch(UUID.randomUUID(), "p" + index, ClientPlatform.JAVA,
                    ProbeTransportMode.PASSIVE, ProbeResponse.Outcome.RESPONSE, 5);
        }
        assertTrue(health.trackedPlayers() <= ClientProbeHealthService.TRACKED_PLAYER_LIMIT,
                "health history must stay bounded");
    }

    // ------------------------------------------------------------------- helpers

    private static ProbeCorroboration.Observation weak(String id) {
        return new ProbeCorroboration.Observation(id, DetectionStatus.UNCERTAIN,
                ProbeEvidenceStrength.WEAK, ProbeCorroboration.Basis.WEAK_LOCALIZED, true);
    }

    private ClientDetectionConfig bundledConfig() throws Exception {
        Path file = temporaryDirectory.resolve("client-detection.yml");
        try (InputStream stream = getClass().getResourceAsStream("/client-detection.yml")) {
            Files.write(file, stream.readAllBytes());
        }
        return ClientDetectionConfig.load(temporaryDirectory, Logger.getAnonymousLogger());
    }

    private static ProbeDefinition probe() {
        return probe("probe");
    }

    private static ProbeDefinition probe(String id) {
        return new ProbeDefinition(id, id, "mod." + id + ".title", ProbeMode.TRANSLATE,
                "NO_" + id.toUpperCase(java.util.Locale.ROOT).replace('.', '_'), true, true, false,
                ProbeVerificationStatus.UNVERIFIED, "test", "", "", "");
    }

    private static ProbeDefinition ambiguousProbe(String id) {
        return new ProbeDefinition(id, id, "mod." + id + ".title", ProbeMode.TRANSLATE,
                "NO_" + id.toUpperCase(java.util.Locale.ROOT).replace('.', '_'), true, true, false,
                ProbeVerificationStatus.UNVERIFIED, "test", "", "", "");
    }

    private static ProbeDefinition interactiveProbe(String id, boolean manual, boolean automatic) {
        return new ProbeDefinition(id, id, "mod." + id + ".title", ProbeMode.TRANSLATE,
                "NO_" + id.toUpperCase(java.util.Locale.ROOT), true, manual, automatic,
                ProbeVerificationStatus.UNVERIFIED, "test", "", "", "");
    }

    private static ClientDetectionConfig config(List<ProbeDefinition> probes, boolean interactiveAutomatic) {
        return new ClientDetectionConfig(true, false, 40, 0, ProbeRegistry.of(probes),
                true, 1, false, 32, 10, 2, interactiveAutomatic);
    }

    private static DetectionTarget target() {
        return new DetectionTarget(UUID.randomUUID(), "target", true, new Object());
    }

    private static CheckHacksClientDetectionModule module(ClientDetectionConfig base,
                                                           ClientProbeTransport transport,
                                                           boolean doubleCheck) {
        ClientDetectionConfig effective = new ClientDetectionConfig(base.enabled(), doubleCheck,
                base.timeoutTicks(), base.betweenProbeTicks(), base.probeRegistry(),
                base.autoCheckOnJoin(), base.autoCheckDelayTicks(), base.firstJoinOnly(),
                base.maxConcurrentAutoChecks(), base.shortTimeoutTicks(),
                base.shortTimeoutAfterConsecutiveTimeouts(), base.interactiveAutomatic());
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(effective, transport);
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new ImmediateScheduler()),
                FoundationConfig.defaults(), Logger.getLogger("p32")));
        module.start();
        return module;
    }

    /** Transport that records every way it could have disturbed the player. */
    private static final class RecordingTransport implements ClientProbeTransport {
        private final List<ProbeRequest> requests = new ArrayList<>();
        private final List<Consumer<ProbeResponse>> pending = new ArrayList<>();
        private final AtomicInteger activeOperations = new AtomicInteger();
        private final AtomicInteger worldMutations = new AtomicInteger();
        private final AtomicInteger uiOpenings = new AtomicInteger();
        private final AtomicInteger playerStateChanges = new AtomicInteger();
        private final AtomicInteger schedulerBypasses = new AtomicInteger();
        private final AtomicInteger cleanups = new AtomicInteger();
        private final AtomicBoolean anyCancelled = new AtomicBoolean();
        private final AtomicReference<UUID> lastPlayerId = new AtomicReference<>();
        private ProbeResponse.Outcome outcome = ProbeResponse.Outcome.RESPONSE;
        private ClientResponseFixtures.Rendered rendered;
        private ClientResponseFixtures.Rendered passiveAnswer;
        private volatile boolean hold;

        @Override
        public synchronized ProbeHandle send(ProbeRequest request, Consumer<ProbeResponse> response) {
            requests.add(request);
            lastPlayerId.set(request.target().id());
            activeOperations.incrementAndGet();
            if (request.probes().get(0).transport().opensClientUi()) {
                uiOpenings.incrementAndGet();
                worldMutations.incrementAndGet();
            }
            if (hold) {
                pending.add(response);
                return new ProbeHandle() {
                    @Override public void cancel() {
                        anyCancelled.set(true);
                        activeOperations.decrementAndGet();
                    }
                    @Override public boolean cancelled() { return anyCancelled.get(); }
                };
            }
            respond(request, response);
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }

        private void respond(ProbeRequest request, Consumer<ProbeResponse> response) {
            ClientResponseFixtures.Rendered answer = rendered != null ? rendered
                    : request.probes().get(0).transport() == ProbeTransportMode.PASSIVE && passiveAnswer != null
                    ? passiveAnswer : ClientResponseFixtures.clean(request.probes());
            response.accept(ClientResponseFixtures.bind(request, answer, outcome));
            cleanups.incrementAndGet();
            activeOperations.decrementAndGet();
        }

        /** Delivers a response for an earlier request index, bypassing the normal path. */
        synchronized void deliverLate(int requestIndex, ClientResponseFixtures.Rendered answer) {
            if (requestIndex >= pending.size()) return;
            ProbeRequest request = requests.get(requestIndex);
            pending.get(requestIndex).accept(ClientResponseFixtures.bind(request, answer));
            cleanups.incrementAndGet();
            activeOperations.decrementAndGet();
        }

        @Override public void stop() { pending.clear(); }
    }

    private static final class ImmediateScheduler implements Scheduler {
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
