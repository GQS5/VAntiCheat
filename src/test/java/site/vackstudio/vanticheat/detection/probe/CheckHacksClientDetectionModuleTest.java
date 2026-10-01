package site.vackstudio.vanticheat.detection.probe;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionSession;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckHacksClientDetectionModuleTest {
    @Test
    void timeoutIsProtectedAndProducesEvidenceForEveryProbe() {
        List<ProbeDefinition> probes = probes(4);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.TIMEOUT);
        CheckHacksClientDetectionModule module = module(probes, transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.TIMEOUT, result.get().status());
        assertEquals(4, result.get().evidence().size());
        assertTrue(result.get().evidence().get(0).metadata().containsKey("session"));
        assertEquals("TIMEOUT", result.get().evidence().get(0).metadata().get("classification"));
    }

    @Test
    void disconnectStopsBatchProgressionAndCannotBecomeClean() {
        List<ProbeDefinition> probes = probes(4);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.DISCONNECTED);
        CheckHacksClientDetectionModule module = module(probes, transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.ERROR, result.get().status());
        assertEquals(1, transport.requests.size());
        assertEquals(4, result.get().evidence().size());
    }

    @Test
    void detectedProbeOnlyIsConfirmed() {
        List<ProbeDefinition> probes = probes(4);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        transport.confirmFirstProbe = true;
        CheckHacksClientDetectionModule module = module(probes, transport, true);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.CLEAN, result.get().status());
        assertEquals(3, transport.requests.size());
        assertEquals(5, result.get().evidence().size());
        assertEquals(1, transport.requests.get(2).probes().size());
        assertEquals("INITIAL", result.get().evidence().get(0).metadata().get("pass"));
        assertEquals("CONFIRMATION", result.get().evidence().get(4).metadata().get("pass"));
        assertEquals("probe-0", transport.requests.get(2).probes().get(0).id());
    }

    @Test
    void automaticTriggerIsRecordedAndCannotCompeteWithManualSession() {
        List<ProbeDefinition> probes = probes(1);
        BlockingTransport transport = new BlockingTransport();
        CheckHacksClientDetectionModule module = module(probes, transport, false);
        DetectionTarget target = target();
        AtomicReference<DetectionResult> manual = new AtomicReference<>();

        module.check(target, manual::set);
        assertTrue(module.isActive(target.id()));
        assertNull(module.checkIfIdle(target, probes, "JOIN", ignored -> { }));

        transport.respond();
        assertEquals(DetectionStatus.CLEAN, manual.get().status());
        assertEquals("MANUAL", manual.get().evidence().get(0).metadata().get("trigger"));
        assertTrue(!module.isActive(target.id()));
    }

    @Test
    void disconnectCancelsActiveSessionAndProbeHandle() {
        BlockingTransport transport = new BlockingTransport();
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        DetectionTarget target = target();

        module.check(target, ignored -> { });
        assertTrue(module.isActive(target.id()));

        module.disconnect(target.id());

        assertTrue(!module.isActive(target.id()));
        assertTrue(transport.cancelled);
    }

    @Test
    void shutdownTerminalizesActiveSessionAndNotifiesExactlyOnce() {
        BlockingTransport transport = new BlockingTransport();
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        AtomicInteger callbacks = new AtomicInteger();
        DetectionSession session = module.check(target(), ignored -> callbacks.incrementAndGet());

        module.stop();
        module.stop();

        assertTrue(session.state().terminal());
        assertEquals(DetectionStatus.SKIPPED, session.result().status());
        assertEquals(1, callbacks.get());
        assertEquals(0, module.activeSessionCount());
        assertTrue(transport.cancelled);
    }

    @Test
    void nextBatchSchedulerFailureCompletesAsErrorWithoutLeakingSession() {
        ClientProbeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        Scheduler scheduler = new ImmediateScheduler() {
            @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
                throw new IllegalStateException("scheduler unavailable");
            }
        };
        ClientDetectionConfig config = new ClientDetectionConfig(true, false, 20, 1, probes(4));
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(new PlatformContext(Platform.PAPER, scheduler),
                FoundationConfig.defaults(), Logger.getLogger("test")));
        module.start();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();

        DetectionSession session = module.check(target(), value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });

        assertEquals(DetectionStatus.ERROR, result.get().status());
        assertEquals(1, callbacks.get());
        assertTrue(session.state().terminal());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void confirmationNextBatchSchedulingFailureCompletesOnceAsError() {
        java.util.concurrent.atomic.AtomicInteger delayedSchedules = new java.util.concurrent.atomic.AtomicInteger();
        Scheduler scheduler = new ImmediateScheduler() {
            @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
                if (delayedSchedules.incrementAndGet() == 1) {
                    task.run(); // finish the initial pass's second batch
                    return new TaskHandle() {
                        @Override public void cancel() { }
                        @Override public boolean cancelled() { return false; }
                    };
                }
                throw new IllegalStateException("confirmation batch scheduler unavailable");
            }
        };
        ClientProbeTransport transport = new ClientProbeTransport() {
            @Override public ProbeHandle send(ProbeRequest request,
                    java.util.function.Consumer<ProbeResponse> response) {
                List<String> lines = new ArrayList<>(request.probes().stream()
                        .map(ProbeDefinition::key).toList());
                while (lines.size() < 3) lines.add("");
                lines.add("not-a-keybind");
                response.accept(new ProbeResponse(request.sessionId(), request.target().id(), lines,
                        ProbeResponse.Outcome.RESPONSE));
                return new DelayedHandle();
            }
            @Override public void stop() { }
        };
        ClientDetectionConfig config = new ClientDetectionConfig(true, true, 20, 1, probes(4));
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(new PlatformContext(Platform.PAPER, scheduler),
                FoundationConfig.defaults(), Logger.getLogger("test-confirm-scheduler")));
        module.start();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        DetectionSession session = module.check(target(), value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });

        assertEquals(DetectionStatus.ERROR, result.get().status());
        assertEquals(1, callbacks.get());
        assertTrue(session.state().terminal());
        assertEquals(0, module.activeSessionCount());
        assertEquals(1, module.diagnostics().errors());
    }

    @Test
    void manualAndAutomaticPoliciesAreEnforcedByTheEngine() {
        ProbeDefinition autoOnly = new ProbeDefinition("auto-only", "Auto Only", "key.auto-only",
                ProbeMode.TRANSLATE, "fallback.auto-only", true, false, true,
                ProbeVerificationStatus.UNVERIFIED);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        CheckHacksClientDetectionModule module = module(List.of(autoOnly), transport, false);
        AtomicReference<DetectionResult> manual = new AtomicReference<>();

        module.check(target(), manual::set);

        assertEquals(DetectionStatus.SKIPPED, manual.get().status());
        assertEquals(0, transport.requests.size());

        AtomicReference<DetectionResult> automatic = new AtomicReference<>();
        module.checkIfIdle(target(), List.of(autoOnly), "JOIN", automatic::set);

        assertEquals(DetectionStatus.CLEAN, automatic.get().status());
        assertEquals(1, transport.requests.size());
    }

    @Test
    void configurationReplacementUsesNewRegistryWithoutChangingRunningSession() {
        ProbeDefinition oldProbe = new ProbeDefinition("old-probe", "Old Probe", "key.old",
                ProbeMode.TRANSLATE, "fallback.old", true, true, true, ProbeVerificationStatus.VERIFIED);
        ProbeDefinition newProbe = new ProbeDefinition("new-probe", "New Probe", "key.new",
                ProbeMode.TRANSLATE, "fallback.new", true, true, true, ProbeVerificationStatus.VERIFIED);
        BlockingTransport transport = new BlockingTransport();
        CheckHacksClientDetectionModule module = module(List.of(oldProbe), transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        DetectionTarget target = target();

        module.check(target, result::set);
        module.replaceConfiguration(new ClientDetectionConfig(true, false, 20, 0, List.of(newProbe)));

        assertEquals(newProbe, module.registry().find("new-probe"));
        transport.respond();
        assertEquals("old-probe", result.get().evidence().get(0).metadata().get("probe"));
    }

    @Test
    void duplicateTransportCallbacksProduceOneTerminalResult() {
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        transport.callbackTwice = true;
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();

        module.check(target(), ignored -> callbacks.incrementAndGet());

        assertEquals(1, callbacks.get());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void duplicateCallbacksDuringConfirmationProduceOneTerminalResult() {
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        transport.confirmFirstProbe = true;
        transport.callbackTwice = true;
        CheckHacksClientDetectionModule module = module(probes(1), transport, true);
        AtomicInteger callbacks = new AtomicInteger();

        module.check(target(), ignored -> callbacks.incrementAndGet());

        assertEquals(2, transport.requests.size());
        assertEquals(1, callbacks.get());
        assertEquals(0, module.activeSessionCount());
        assertEquals(1, module.diagnostics().completedScans());
    }

    @Test
    void disconnectDuringConfirmationTerminalizesAndIgnoresLateConfirmationResponse() {
        ProbeDefinition probe = probes(1).getFirst();
        DelayedTransport transport = new DelayedTransport();
        CheckHacksClientDetectionModule module = module(List.of(probe), transport, true);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        DetectionTarget target = target();
        module.check(target, value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });
        transport.respondLine(0, probe.key());
        assertEquals(2, transport.requests.size(), "flagged result starts the confirmation pass");

        module.disconnect(target.id());
        transport.respondLine(1, probe.key());

        assertEquals(1, callbacks.get());
        assertEquals(DetectionStatus.SKIPPED, result.get().status());
        assertEquals(0, module.activeSessionCount());
        assertEquals(0, module.diagnostics().activeScans().size());
        assertEquals(1, module.diagnostics().completedScans());
    }

    @Test
    void shutdownDuringConfirmationTerminalizesAndIgnoresLateResponse() {
        ProbeDefinition probe = probes(1).getFirst();
        DelayedTransport transport = new DelayedTransport();
        CheckHacksClientDetectionModule module = module(List.of(probe), transport, true);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        DetectionSession session = module.check(target(), value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });
        transport.respondLine(0, probe.key());
        assertEquals(2, transport.requests.size());

        module.stop();
        transport.respondLine(1, probe.key());

        assertTrue(session.state().terminal());
        assertEquals(DetectionStatus.SKIPPED, result.get().status());
        assertEquals(1, callbacks.get());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void staleConfirmationFromPriorConnectionCannotCompleteSameUuidReconnect() {
        ProbeDefinition probe = probes(1).getFirst();
        DelayedTransport transport = new DelayedTransport();
        CheckHacksClientDetectionModule module = module(List.of(probe), transport, true);
        DetectionTarget oldConnection = target();
        AtomicInteger oldCallbacks = new AtomicInteger();
        module.check(oldConnection, ignored -> oldCallbacks.incrementAndGet());
        transport.respondLine(0, probe.key());
        assertEquals(2, transport.requests.size());

        module.disconnect(oldConnection.id());
        AtomicReference<DetectionResult> newResult = new AtomicReference<>();
        DetectionTarget newConnection = new DetectionTarget(oldConnection.id(), "reconnected", true, new Object());
        DetectionSession newSession = module.check(newConnection, newResult::set);
        transport.respondLine(1, probe.key()); // late confirmation from A

        assertTrue(module.isActive(newConnection.id()));
        assertEquals(DetectionStatus.RUNNING, newSession.result().status());
        assertEquals(1, oldCallbacks.get());
        transport.respondLine(2, "");
        assertEquals(DetectionStatus.CLEAN, newResult.get().status());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void timeoutThenLateResponseCannotChangeTerminalResultOrDiagnostics() {
        DelayedTransport transport = new DelayedTransport();
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        DetectionTarget target = target();
        DetectionSession session = module.check(target, value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });

        transport.respondOutcome(0, ProbeResponse.Outcome.TIMEOUT);
        transport.respond(0);

        assertTrue(session.state().terminal());
        assertEquals(DetectionStatus.TIMEOUT, result.get().status());
        assertEquals(1, callbacks.get());
        assertEquals(0, module.activeSessionCount());
        assertEquals(0, module.diagnostics().activeScans().size());
        assertEquals(1, module.diagnostics().timeouts());
        assertEquals(1, module.diagnostics().completedScans());
    }

    @Test
    void responseThenTimeoutAtBoundaryKeepsTheFirstTerminalCallback() {
        DelayedTransport transport = new DelayedTransport();
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        DetectionSession session = module.check(target(), value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });

        transport.respondLine(0, ""); // deterministic dispatch immediately before timeout
        transport.respondOutcome(0, ProbeResponse.Outcome.TIMEOUT); // deterministic dispatch immediately after

        assertTrue(session.state().terminal());
        assertEquals(DetectionStatus.CLEAN, result.get().status());
        assertEquals(1, callbacks.get());
        assertEquals(1, module.diagnostics().cleans());
        assertEquals(0, module.diagnostics().timeouts());
    }

    @Test
    void timeoutAndResponseReleasedAtOneBarrierHaveOneWinner() throws Exception {
        DelayedTransport transport = new DelayedTransport();
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        DetectionSession session = module.check(target(), value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });
        java.util.concurrent.CyclicBarrier release = new java.util.concurrent.CyclicBarrier(3);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var timeout = executor.submit(() -> {
                release.await();
                transport.respondOutcome(0, ProbeResponse.Outcome.TIMEOUT);
                return null;
            });
            var response = executor.submit(() -> {
                release.await();
                transport.respondLine(0, "");
                return null;
            });
            release.await();
            timeout.get();
            response.get();
        }

        assertTrue(session.state().terminal());
        assertTrue(result.get().status() == DetectionStatus.TIMEOUT
                || result.get().status() == DetectionStatus.CLEAN);
        assertEquals(1, callbacks.get());
        assertEquals(1, module.diagnostics().completedScans());
        assertEquals(1, module.diagnostics().timeouts() + module.diagnostics().cleans());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void timeoutRacingDisconnectOrShutdownHasOneTerminalOwner() throws Exception {
        for (boolean shutdown : List.of(false, true)) {
            DelayedTransport transport = new DelayedTransport();
            CheckHacksClientDetectionModule module = module(probes(1), transport, false);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<DetectionResult> result = new AtomicReference<>();
            DetectionTarget target = target();
            DetectionSession session = module.check(target, value -> {
                callbacks.incrementAndGet();
                result.set(value);
            });
            java.util.concurrent.CyclicBarrier release = new java.util.concurrent.CyclicBarrier(3);
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var timeout = executor.submit(() -> {
                    release.await();
                    transport.respondOutcome(0, ProbeResponse.Outcome.TIMEOUT);
                    return null;
                });
                var teardown = executor.submit(() -> {
                    release.await();
                    if (shutdown) module.stop();
                    else module.disconnect(target.id());
                    return null;
                });
                release.await();
                timeout.get();
                teardown.get();
            }
            assertTrue(session.state().terminal());
            assertTrue(result.get().status() == DetectionStatus.TIMEOUT
                    || result.get().status() == DetectionStatus.SKIPPED);
            assertEquals(1, callbacks.get());
            assertEquals(0, module.activeSessionCount());
        }
    }

    @Test
    void unsupportedTransportResponseIsNotCleanOrDetection() {
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.UNSUPPORTED);
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.UNSUPPORTED, result.get().status());
        assertEquals("UNSUPPORTED", result.get().evidence().getFirst().metadata().get("classification"));
    }

    @Test
    void transportSendExceptionCompletesSessionAsErrorAndReleasesPlayer() {
        ClientProbeTransport transport = new ClientProbeTransport() {
            @Override public ProbeHandle send(ProbeRequest request,
                    java.util.function.Consumer<ProbeResponse> response) {
                throw new IllegalStateException("test transport failure");
            }
            @Override public void stop() { }
        };
        CheckHacksClientDetectionModule module = module(probes(2), transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        DetectionTarget target = target();

        module.check(target, result::set);

        assertEquals(DetectionStatus.ERROR, result.get().status());
        assertEquals(2, result.get().evidence().size());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void manualAndAutomaticTriggersUseSameEvaluationForSameResponse() {
        ProbeDefinition probe = new ProbeDefinition("shared-probe", "Shared Probe", "key.shared",
                ProbeMode.TRANSLATE, "fallback.shared", true, true, true,
                ProbeVerificationStatus.UNVERIFIED, "test", "", "", "Resolved Shared Probe");
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        transport.fixedLines = List.of("Resolved Shared Probe", "", "");
        CheckHacksClientDetectionModule module = module(List.of(probe), transport, false);
        AtomicReference<DetectionResult> manual = new AtomicReference<>();
        AtomicReference<DetectionResult> automatic = new AtomicReference<>();

        module.check(target(), List.of(probe), manual::set);
        module.checkIfIdle(target(), List.of(probe), "JOIN", automatic::set);

        assertEquals(DetectionStatus.DETECTED, manual.get().status());
        assertEquals(manual.get().status(), automatic.get().status());
        assertEquals("DETECTED", manual.get().evidence().getFirst().metadata().get("classification"));
        assertEquals("DETECTED", automatic.get().evidence().getFirst().metadata().get("classification"));
    }

    @Test
    void manualAndAutomaticShareAllTerminalResultSemantics() {
        for (var scenario : List.of(
                new ParityCase(ProbeMode.TRANSLATE, ProbeResponse.Outcome.RESPONSE, false,
                        DetectionStatus.CLEAN),
                new ParityCase(ProbeMode.METEOR, ProbeResponse.Outcome.RESPONSE, true,
                        DetectionStatus.DETECTED),
                new ParityCase(ProbeMode.KEYBIND, ProbeResponse.Outcome.RESPONSE, true,
                        DetectionStatus.PROTECTED),
                new ParityCase(ProbeMode.TRANSLATE, ProbeResponse.Outcome.TIMEOUT, false,
                        DetectionStatus.TIMEOUT),
                new ParityCase(ProbeMode.TRANSLATE, ProbeResponse.Outcome.ERROR, false,
                        DetectionStatus.ERROR),
                new ParityCase(ProbeMode.TRANSLATE, ProbeResponse.Outcome.UNSUPPORTED, false,
                        DetectionStatus.UNSUPPORTED),
                new ParityCase(ProbeMode.TRANSLATE, ProbeResponse.Outcome.SKIPPED, false,
                        DetectionStatus.SKIPPED))) {
            ProbeDefinition probe = new ProbeDefinition("parity", "Parity", "key.parity", scenario.mode(),
                    "fallback.parity", true, true, true, ProbeVerificationStatus.UNVERIFIED);
            FakeTransport manualTransport = new FakeTransport(scenario.outcome());
            manualTransport.confirmFirstProbe = scenario.flaggedResponse();
            manualTransport.exploitProtection = scenario.expected() == DetectionStatus.PROTECTED;
            CheckHacksClientDetectionModule manualModule = module(List.of(probe), manualTransport, false);
            AtomicReference<DetectionResult> manual = new AtomicReference<>();
            manualModule.check(target(), List.of(probe), manual::set);

            FakeTransport automaticTransport = new FakeTransport(scenario.outcome());
            automaticTransport.confirmFirstProbe = scenario.flaggedResponse();
            automaticTransport.exploitProtection = scenario.expected() == DetectionStatus.PROTECTED;
            CheckHacksClientDetectionModule automaticModule = module(List.of(probe), automaticTransport, false);
            AtomicReference<DetectionResult> automatic = new AtomicReference<>();
            automaticModule.checkIfIdle(target(), List.of(probe), "JOIN", automatic::set);

            assertEquals(scenario.expected(), manual.get().status(), "manual " + scenario);
            assertEquals(manual.get().status(), automatic.get().status(), "trigger parity " + scenario);
            assertEquals(scenario.expected(), automatic.get().status(), "automatic " + scenario);
        }
    }

    @Test
    void lateCallbackFromDisconnectedScanCannotRemoveReconnectHandle() {
        ProbeDefinition probe = new ProbeDefinition("stable", "Stable", "key.stable", ProbeMode.TRANSLATE,
                "fallback.stable", true, ProbeVerificationStatus.UNVERIFIED);
        DelayedTransport transport = new DelayedTransport();
        CheckHacksClientDetectionModule module = module(List.of(probe), transport, false);
        DetectionTarget player = target();

        module.check(player, ignored -> { });
        ProbeHandle oldHandle = transport.handles.get(0);
        module.disconnect(player.id());
        assertTrue(oldHandle.cancelled());

        module.check(player, ignored -> { });
        ProbeHandle reconnectHandle = transport.handles.get(1);
        transport.respond(0);
        assertTrue(!reconnectHandle.cancelled());
        assertTrue(module.isActive(player.id()));

        module.disconnect(player.id());
        assertTrue(reconnectHandle.cancelled());
    }

    @Test
    void bedrockPlayerNeverReachesProbeTransport() {
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        module.setProbeEligibility(ignored -> false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        DetectionSession session = module.check(target(), result::set);

        assertNull(session);
        assertEquals(DetectionStatus.SKIPPED, result.get().status());
        assertEquals(0, transport.requests.size());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void bedrockAutomaticCheckNeverReachesProbeTransport() {
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        CheckHacksClientDetectionModule module = module(probes(2), transport, false);
        module.setProbeEligibility(ignored -> false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        DetectionSession session = module.checkIfIdle(target(), module.automaticProbes(), "JOIN", result::set);

        assertNull(session);
        assertEquals(DetectionStatus.SKIPPED, result.get().status());
        assertEquals(0, transport.requests.size());
    }

    @Test
    void platformClassificationFailureDoesNotCreateOrProbeASession() {
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);
        module.setProbeEligibility(ignored -> { throw new IllegalStateException("provider failed"); });
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        DetectionSession session = module.check(target(), result::set);

        assertNull(session);
        assertEquals(DetectionStatus.ERROR, result.get().status());
        assertEquals(0, transport.requests.size());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void throwingTerminalConsumerCannotResurrectOrLeakCompletedSession() {
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        CheckHacksClientDetectionModule module = module(probes(1), transport, false);

        DetectionSession session = module.check(target(), ignored -> {
            throw new IllegalStateException("consumer failed");
        });

        assertTrue(session.state().terminal());
        assertEquals(DetectionStatus.CLEAN, session.result().status());
        assertEquals(0, module.activeSessionCount());
    }

    @Test
    void consecutiveTimeoutsShortenLaterBatchDeadlinesWithoutChangingSemantics() {
        List<ProbeDefinition> probes = probes(7);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.TIMEOUT);
        CheckHacksClientDetectionModule module = adaptiveModule(probes, transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(DetectionStatus.TIMEOUT, result.get().status());
        assertEquals(3, transport.requests.size());
        assertEquals(40, transport.requests.get(0).timeoutTicks());
        assertEquals(40, transport.requests.get(1).timeoutTicks());
        assertEquals(10, transport.requests.get(2).timeoutTicks());
        assertEquals(7, result.get().evidence().size());
        assertTrue(result.get().evidence().stream()
                .allMatch(evidence -> "TIMEOUT".equals(evidence.metadata().get("classification"))));
    }

    @Test
    void anyResponseResetsSilenceStreakBackToFullDeadline() {
        List<ProbeDefinition> probes = probes(10);
        DelayedTransport transport = new DelayedTransport();
        CheckHacksClientDetectionModule module = adaptiveModule(probes, transport, false);
        AtomicReference<DetectionResult> result = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();

        module.check(target(), value -> {
            callbacks.incrementAndGet();
            result.set(value);
        });
        transport.respondOutcome(0, ProbeResponse.Outcome.TIMEOUT);
        transport.respondOutcome(1, ProbeResponse.Outcome.TIMEOUT);
        transport.respond(2);
        transport.respond(3);

        assertEquals(4, transport.requests.size());
        assertEquals(40, transport.requests.get(0).timeoutTicks());
        assertEquals(40, transport.requests.get(1).timeoutTicks());
        assertEquals(10, transport.requests.get(2).timeoutTicks());
        assertEquals(40, transport.requests.get(3).timeoutTicks());
        assertEquals(1, callbacks.get());
        assertEquals(DetectionStatus.TIMEOUT, result.get().status());
        assertEquals(10, result.get().evidence().size());
    }

    @Test
    void confirmationPassAlwaysUsesFullDeadline() {
        List<ProbeDefinition> probes = probes(4);
        FakeTransport transport = new FakeTransport(ProbeResponse.Outcome.RESPONSE);
        transport.confirmFirstProbe = true;
        CheckHacksClientDetectionModule module = adaptiveModule(probes, transport, true);
        AtomicReference<DetectionResult> result = new AtomicReference<>();

        module.check(target(), result::set);

        assertEquals(3, transport.requests.size());
        assertEquals(1, transport.requests.get(2).probes().size());
        assertEquals(40, transport.requests.get(2).timeoutTicks());
    }

    private static CheckHacksClientDetectionModule adaptiveModule(List<ProbeDefinition> probes,
            ClientProbeTransport transport, boolean doubleCheck) {
        ClientDetectionConfig config = new ClientDetectionConfig(true, doubleCheck, 40, 0,
                ProbeRegistry.of(probes), false, 1, false, 32, 10, 2, true);
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new ImmediateScheduler()),
                FoundationConfig.defaults(), Logger.getLogger("test-adaptive")));
        module.start();
        return module;
    }

    /**
     * Enables INTERACTIVE automatic probes so these engine-parity tests exercise the shared
     * manual/automatic path. The shipped default leaves the opt-in off; the gameplay-safety
     * gate itself is asserted in TransportCapabilityPolicyTest.
     */
    private static CheckHacksClientDetectionModule module(List<ProbeDefinition> probes,
                                                           ClientProbeTransport transport, boolean doubleCheck) {
        ClientDetectionConfig config = new ClientDetectionConfig(true, doubleCheck, 20, 0,
                ProbeRegistry.of(probes), true, 1, false, 32, 20, 2, true);
        CheckHacksClientDetectionModule module = new CheckHacksClientDetectionModule(config, transport);
        module.initialize(new DetectionModuleContext(
                new PlatformContext(Platform.PAPER, new ImmediateScheduler()),
                FoundationConfig.defaults(), Logger.getLogger("test")));
        module.start();
        return module;
    }

    private static DetectionTarget target() {
        return new DetectionTarget(UUID.randomUUID(), "test", true, new Object());
    }

    private static List<ProbeDefinition> probes(int count) {
        List<ProbeDefinition> probes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            probes.add(new ProbeDefinition("probe-" + i, "Probe " + i, "key.probe." + i,
                    ProbeMode.METEOR, "", true, ProbeVerificationStatus.UNVERIFIED));
        }
        return probes;
    }

    private static final class FakeTransport implements ClientProbeTransport {
        private final ProbeResponse.Outcome outcome;
        private final List<ProbeRequest> requests = new ArrayList<>();
        private boolean confirmFirstProbe;
        private boolean callbackTwice;
        private boolean exploitProtection = true;
        private List<String> fixedLines;

        private FakeTransport(ProbeResponse.Outcome outcome) { this.outcome = outcome; }

        @Override
        public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            requests.add(request);
            if (outcome != ProbeResponse.Outcome.RESPONSE) {
                callback.accept(new ProbeResponse(request.sessionId(), request.target().id(), List.of(), outcome));
            } else {
                List<String> lines = fixedLines == null ? new ArrayList<>(request.probes().stream()
                        .map(probe -> confirmFirstProbe && requests.size() == 1
                                && probe == request.probes().get(0)
                                ? probe.key() : probe.fallback())
                        .toList()) : new ArrayList<>(fixedLines);
                while (lines.size() < 3) lines.add("");
                lines.add(exploitProtection ? CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY : "not-a-keybind");
                ProbeResponse response = new ProbeResponse(request.sessionId(), request.target().id(), lines, outcome);
                callback.accept(response);
                if (callbackTwice) callback.accept(response);
            }
            return new ProbeHandle() {
                @Override public void cancel() { }
                @Override public boolean cancelled() { return false; }
            };
        }

        @Override public void stop() { }
    }

    private record ParityCase(ProbeMode mode, ProbeResponse.Outcome outcome,
                              boolean flaggedResponse, DetectionStatus expected) { }

    private static final class BlockingTransport implements ClientProbeTransport {
        private ProbeRequest request;
        private java.util.function.Consumer<ProbeResponse> callback;
        private boolean cancelled;

        @Override
        public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            this.request = request;
            this.callback = callback;
            return new ProbeHandle() {
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }

        void respond() {
            callback.accept(new ProbeResponse(request.sessionId(), request.target().id(),
                    List.of("", "", "", CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY),
                    ProbeResponse.Outcome.RESPONSE));
        }

        @Override public void stop() { }
    }

    private static final class DelayedTransport implements ClientProbeTransport {
        private final List<ProbeRequest> requests = new ArrayList<>();
        private final List<java.util.function.Consumer<ProbeResponse>> callbacks = new ArrayList<>();
        private final List<DelayedHandle> handles = new ArrayList<>();

        @Override
        public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            requests.add(request);
            callbacks.add(callback);
            DelayedHandle handle = new DelayedHandle();
            handles.add(handle);
            return handle;
        }

        private void respond(int index) {
            respondLine(index, "");
        }

        private void respondLine(int index, String line) {
            ProbeRequest request = requests.get(index);
            List<String> lines = new ArrayList<>(request.probes().stream()
                    .map(ignored -> "").toList());
            if (!lines.isEmpty()) lines.set(0, line);
            while (lines.size() < 3) lines.add("");
            lines.add("key.forward");
            callbacks.get(index).accept(new ProbeResponse(request.sessionId(), request.target().id(),
                    lines, ProbeResponse.Outcome.RESPONSE));
        }

        private void respondOutcome(int index, ProbeResponse.Outcome outcome) {
            ProbeRequest request = requests.get(index);
            callbacks.get(index).accept(new ProbeResponse(request.sessionId(), request.target().id(),
                    List.of(), outcome));
        }

        @Override public void stop() { }
    }

    private static final class DelayedHandle implements ProbeHandle {
        private volatile boolean cancelled;
        @Override public void cancel() { cancelled = true; }
        @Override public boolean cancelled() { return cancelled; }
    }

    private static class ImmediateScheduler implements Scheduler {
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
