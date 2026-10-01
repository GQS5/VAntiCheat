package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.config.ClientDetectionConfig;
import site.vackstudio.vanticheat.config.FoundationConfig;
import site.vackstudio.vanticheat.detection.DetectionModuleContext;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.DetectionTarget;
import site.vackstudio.vanticheat.detection.probe.CheckHacksClientDetectionModule;
import site.vackstudio.vanticheat.detection.probe.ClientProbeTransport;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeHandle;
import site.vackstudio.vanticheat.detection.probe.ProbeMode;
import site.vackstudio.vanticheat.detection.probe.ProbeRequest;
import site.vackstudio.vanticheat.detection.probe.ProbeResponse;
import site.vackstudio.vanticheat.detection.probe.ProbeVerificationStatus;
import site.vackstudio.vanticheat.enforcement.EnforcementAction;
import site.vackstudio.vanticheat.enforcement.EnforcementDecision;
import site.vackstudio.vanticheat.enforcement.EnforcementExecutor;
import site.vackstudio.vanticheat.enforcement.EnforcementPolicy;
import site.vackstudio.vanticheat.enforcement.EnforcementService;
import site.vackstudio.vanticheat.lunar.LunarClientService;
import site.vackstudio.vanticheat.lunar.LunarClientIntegration;
import site.vackstudio.vanticheat.lunar.LunarPolicyConfig;
import site.vackstudio.vanticheat.platform.AutomaticCheckCoordinator;
import site.vackstudio.vanticheat.platform.ClientPlatformService;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.Platform;
import site.vackstudio.vanticheat.platform.PlatformContext;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;
import site.vackstudio.vanticheat.trusted.TrustedPlayer;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticClientDetectionListenerTest {
    @Test
    void bedrockJoinNeverCreatesEngineSessionOrTransportOperation() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(true, true));

        fixture.listener.onJoinPlayer(player(UUID.randomUUID(), "bedrock", true));

        assertEquals(0, fixture.transport.requests.size());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.enforcementDecisions.get());
    }

    @Test
    void unknownProviderGetsBoundedRetriesWithoutProbeOrSession() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(true, null, null, null, null));

        fixture.listener.onJoinPlayer(player(UUID.randomUUID(), "pending", true));

        assertEquals(0, fixture.transport.requests.size());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.enforcementDecisions.get());
    }

    @Test
    void noProviderAllowsJavaAndTemporaryUnknownRetriesToJava() {
        Fixture noProvider = new Fixture(2, false, new ScriptedProvider(false));
        noProvider.listener.onJoinPlayer(player(UUID.randomUUID(), "java-no-provider", true));
        assertEquals(1, noProvider.transport.requests.size());
        assertEquals(0, noProvider.module.activeSessionCount());

        Fixture becomesJava = new Fixture(2, false,
                new ScriptedProvider(true, null, false, false));
        becomesJava.listener.onJoinPlayer(player(UUID.randomUUID(), "java-after-provider-ready", true));
        assertEquals(1, becomesJava.transport.requests.size());
        assertEquals(0, becomesJava.listener.activeChecks());
    }

    @Test
    void trustedAndDisabledAutomaticPolicyNeverSendProbes() {
        Fixture trusted = new Fixture(2, false, new ScriptedProvider(false));
        trusted.trusted.set(true);
        trusted.listener.onJoinPlayer(player(UUID.randomUUID(), "trusted", true));
        assertEquals(0, trusted.transport.requests.size());

        Fixture disabled = new Fixture(2, false, new ScriptedProvider(false), false);
        disabled.listener.onJoinPlayer(player(UUID.randomUUID(), "disabled", true));
        assertEquals(0, disabled.transport.requests.size());
    }

    @Test
    void emptyAutomaticRegistryCreatesNoCoordinatorEntryOrSession() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        ProbeDefinition manualOnly = new ProbeDefinition("manual-only", "Manual Only", "key.manual",
                ProbeMode.TRANSLATE, "fallback.manual", true, true, false,
                ProbeVerificationStatus.UNVERIFIED);
        ClientDetectionConfig replacement = config(List.of(manualOnly), false, 2);
        fixture.module.replaceConfiguration(replacement);
        fixture.listener.reloadConfiguration(replacement);

        fixture.listener.onJoinPlayer(player(UUID.randomUUID(), "empty-auto", true));

        assertEquals(0, fixture.transport.requests.size());
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
    }

    @Test
    void duplicateJoinEventDoesNotStartAnotherScanAndReconnectIsFresh() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        UUID id = UUID.randomUUID();
        Player firstConnection = player(id, "player", true);

        fixture.listener.onJoinPlayer(firstConnection);
        fixture.listener.onJoinPlayer(firstConnection);
        assertEquals(1, fixture.transport.requests.size());

        fixture.listener.onQuitPlayer(id, "player");
        fixture.listener.onJoinPlayer(player(id, "player", true));
        assertEquals(2, fixture.transport.requests.size());
        assertEquals(0, fixture.listener.activeChecks());
    }

    @Test
    void detectedAutomaticResultUsesEnforcementExactlyOnce() {
        Fixture fixture = new Fixture(2, true, new ScriptedProvider(false));
        fixture.transport.fixedResponse = "known response";

        fixture.listener.onJoinPlayer(player(UUID.randomUUID(), "detected", true));

        assertEquals(2, fixture.transport.requests.size(), "only the flagged probe is confirmed");
        assertEquals(1, fixture.enforcementDecisions.get());
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
    }

    @Test
    void platformChangeToBedrockDuringActiveScanSuppressesEnforcement() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(true, false));
        fixture.transport.autoRespond = false;
        Player player = player(UUID.randomUUID(), "platform-switch", true);
        fixture.listener.onJoinPlayer(player);
        assertEquals(1, fixture.transport.requests.size());

        fixture.provider.forceAnswer(true);
        fixture.transport.respond(0, "known response");

        assertEquals(0, fixture.enforcementDecisions.get());
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
    }

    @Test
    void disconnectCancelsScanAndLateCallbackCannotEnforceOrAffectReconnect() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        UUID id = UUID.randomUUID();
        Player first = player(id, "reconnect", true);

        fixture.listener.onJoinPlayer(first);
        assertEquals(1, fixture.listener.activeChecks());
        fixture.listener.onQuitPlayer(id, "reconnect");
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());

        fixture.listener.onJoinPlayer(player(id, "reconnect", true));
        assertEquals(2, fixture.transport.requests.size());
        fixture.transport.respond(0, "known response"); // stale callback for prior session
        assertEquals(0, fixture.enforcementDecisions.get());
        assertEquals(1, fixture.listener.activeChecks());
        fixture.transport.respond(1, "fallback.probe");
        assertEquals(0, fixture.listener.activeChecks());
    }

    @Test
    void staleQuitFromPriorConnectionCannotCancelSameUuidReconnect() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        UUID id = UUID.randomUUID();
        Player oldConnection = player(id, "reconnect", true);
        Player newConnection = player(id, "reconnect", true);

        fixture.listener.onJoinPlayer(oldConnection);
        fixture.listener.onJoinPlayer(newConnection);
        assertEquals(1, fixture.listener.activeChecks());
        assertEquals(2, fixture.transport.requests.size());

        fixture.listener.onQuitPlayer(id, "reconnect", oldConnection);
        assertEquals(1, fixture.listener.activeChecks());
        fixture.transport.respond(0, "late old response");
        assertEquals(1, fixture.listener.activeChecks());
        fixture.transport.respond(1, "fallback.probe");
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(1, fixture.enforcementDecisions.get(), "only the reconnect result reaches enforcement");
    }

    @Test
    void concurrencyLimitReleasesCapacityAndDoesNotBlockLaterJoins() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        List<Player> players = List.of(
                player(UUID.randomUUID(), "p1", true), player(UUID.randomUUID(), "p2", true),
                player(UUID.randomUUID(), "p3", true), player(UUID.randomUUID(), "p4", true));

        fixture.listener.onJoinPlayer(players.get(0));
        fixture.listener.onJoinPlayer(players.get(1));
        fixture.listener.onJoinPlayer(players.get(2));
        assertEquals(2, fixture.transport.requests.size());
        assertEquals(2, fixture.listener.activeChecks());

        fixture.transport.respond(0, "clean");
        assertEquals(1, fixture.listener.activeChecks());
        fixture.listener.onJoinPlayer(players.get(3));
        assertEquals(3, fixture.transport.requests.size());
        assertEquals(2, fixture.listener.activeChecks());
    }

    @Test
    void timedOutPlayerDoesNotHoldCoordinatorCapacityOrStarveAnotherJoin() {
        Fixture fixture = new Fixture(2, true, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        Player timedOut = player(UUID.randomUUID(), "slow-client", true);
        Player other = player(UUID.randomUUID(), "responsive-client", true);

        fixture.listener.onJoinPlayer(timedOut);
        fixture.listener.onJoinPlayer(other);
        assertEquals(2, fixture.listener.activeChecks());

        fixture.transport.respondOutcome(0, ProbeResponse.Outcome.TIMEOUT);

        assertEquals(1, fixture.listener.activeChecks());
        Player later = player(UUID.randomUUID(), "later-client", true);
        fixture.listener.onJoinPlayer(later);
        assertEquals(3, fixture.transport.requests.size());
        assertEquals(2, fixture.listener.activeChecks());
        assertEquals(2, fixture.module.activeSessionCount());
    }

    @Test
    void reloadDuringScanKeepsExistingRequestSnapshotAndNewScanUsesNewRegistry() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        Player player = player(UUID.randomUUID(), "reload", true);
        fixture.listener.onJoinPlayer(player);
        ProbeDefinition original = fixture.transport.requests.getFirst().probes().getFirst();
        ProbeDefinition replacement = new ProbeDefinition("replacement", "Replacement", "key.replacement",
                ProbeMode.TRANSLATE, "fallback.replacement", true, true, true,
                ProbeVerificationStatus.UNVERIFIED);
        ClientDetectionConfig reloaded = config(List.of(replacement), true, 2);

        fixture.module.replaceConfiguration(reloaded);
        fixture.listener.reloadConfiguration(reloaded);
        fixture.transport.respond(0, "fallback.probe");
        fixture.listener.onQuitPlayer(player.getUniqueId(), player.getName());
        fixture.listener.onJoinPlayer(player(player.getUniqueId(), "reload", true));
        fixture.transport.respond(1, "fallback.replacement");

        assertEquals("probe", original.id());
        assertEquals("replacement", fixture.transport.requests.get(1).probes().getFirst().id());
        assertEquals(0, fixture.module.activeSessionCount());
    }

    @Test
    void shutdownCancelsAutomaticScanAndIgnoresLateResult() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        Player player = player(UUID.randomUUID(), "shutdown", true);
        fixture.listener.onJoinPlayer(player);
        assertEquals(1, fixture.listener.activeChecks());
        assertEquals(1, fixture.module.activeSessionCount());

        fixture.listener.stop();
        fixture.module.stop();
        fixture.transport.respond(0, "known response");

        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.enforcementDecisions.get());
    }

    @Test
    void deterministicThirtyTwoPlayerMixedTerminalStormReleasesEveryOwner() {
        Fixture fixture = new Fixture(32, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Player player = player(UUID.randomUUID(), "storm-" + i, true);
            players.add(player);
            fixture.listener.onJoinPlayer(player);
        }
        assertEquals(32, fixture.listener.activeChecks());
        assertEquals(32, fixture.module.activeSessionCount());
        assertEquals(32, fixture.transport.requests.size());

        for (int i = 0; i < players.size(); i++) {
            if (i % 8 == 0) {
                fixture.listener.onQuitPlayer(players.get(i).getUniqueId(), players.get(i).getName());
                fixture.transport.respond(i, "late response after disconnect");
            } else if (i % 8 == 1) {
                fixture.transport.respond(i, "known response");
            } else if (i % 8 == 2) {
                fixture.transport.respondOutcome(i, ProbeResponse.Outcome.TIMEOUT);
            } else if (i % 8 == 3) {
                fixture.transport.respondOutcome(i, ProbeResponse.Outcome.ERROR);
            } else if (i % 8 == 4) {
                fixture.transport.respondOutcome(i, ProbeResponse.Outcome.DISCONNECTED);
            } else {
                fixture.transport.respond(i, "fallback.probe");
            }
        }

        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.module.diagnostics().activeScans().size());
        assertTrue(fixture.enforcementDecisions.get() <= 28,
                "disconnected scans cannot reach enforcement and every remaining scan enforces at most once");
        assertTrue(fixture.module.diagnostics().recentScans().size() <= 100);
    }

    @Test
    void thirtyTwoPlayerStormMixesPlatformTrustSchedulingTransportAndReconnectFailures() {
        java.util.Map<UUID, Boolean> answers = new java.util.HashMap<>();
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            UUID id = UUID.randomUUID();
            if (i == 0) answers.put(id, true);       // Bedrock
            else if (i == 1) answers.put(id, null);  // UNKNOWN
            else answers.put(id, false);            // Java
            players.add(player(id, "matrix-" + i, true));
        }
        ClientPlatformService.Provider provider = new ClientPlatformService.Provider() {
            @Override public String name() { return "MATRIX"; }
            @Override public boolean installed() { return true; }
            @Override public Boolean isBedrock(UUID id) { return answers.get(id); }
        };
        Fixture fixture = new Fixture(32, false, provider);
        fixture.trustedIds.add(players.get(2).getUniqueId());
        fixture.scheduler.failEntityNames.add("matrix-3");
        fixture.transport.autoRespond = false;
        fixture.transport.failSendIndices.add(0); // transport send failure

        players.forEach(fixture.listener::onJoinPlayer);
        assertEquals(28, fixture.transport.requests.size());
        assertTrue(fixture.transport.requests.stream().noneMatch(request ->
                        request.target().id().equals(players.get(0).getUniqueId())
                                || request.target().id().equals(players.get(1).getUniqueId())
                                || request.target().id().equals(players.get(2).getUniqueId())
                                || request.target().id().equals(players.get(3).getUniqueId())),
                "Bedrock, UNKNOWN, trusted, and rejected-scheduler players issue no transport calls");
        assertEquals(27, fixture.module.activeSessionCount(),
                "the injected send failure terminalizes one session inline; other eligible players stay active");

        // Disconnect and reconnect one in-flight UUID; its old callback is stale.
        var oldRequest = fixture.transport.requests.get(1);
        Player oldConnection = players.stream()
                .filter(candidate -> candidate.getUniqueId().equals(oldRequest.target().id()))
                .findFirst().orElseThrow();
        fixture.listener.onQuitPlayer(oldConnection.getUniqueId(), oldConnection.getName(), oldConnection);
        Player replacement = player(oldConnection.getUniqueId(), "matrix-reconnected", true);
        fixture.listener.onJoinPlayer(replacement);
        int replacementRequest = fixture.transport.requests.size() - 1;
        fixture.transport.respondOutcome(1, ProbeResponse.Outcome.ERROR);

        Player lunarFailurePlayer = players.get(5);
        // Deterministic Apollo subset inside the mixed storm: absent, not ready,
        // action failure, scheduler failure, and retirement/disappearance.
        LunarClientService lunarAbsent = new LunarClientService(Logger.getLogger("matrix-apollo-absent"),
                fixture.scheduler);
        lunarAbsent.start(LunarPolicyConfig.defaults(), null);
        assertEquals(LunarClientService.RegistrationOutcome.IGNORED_UNAVAILABLE,
                lunarAbsent.handleRegistration(new LunarClientIntegration.Registration(
                        lunarFailurePlayer.getUniqueId(), lunarFailurePlayer.getName(), lunarFailurePlayer)));
        LunarClientService lunarNotReady = new LunarClientService(Logger.getLogger("matrix-apollo-notready"),
                fixture.scheduler);
        lunarNotReady.start(LunarPolicyConfig.defaults(), new MatrixApollo(LunarClientIntegration.Availability.NOT_READY, false));
        assertEquals(LunarClientService.RegistrationOutcome.SCHEDULED,
                lunarNotReady.handleRegistration(new LunarClientIntegration.Registration(
                        lunarFailurePlayer.getUniqueId(), lunarFailurePlayer.getName(), lunarFailurePlayer)));
        LunarClientService lunar = new LunarClientService(Logger.getLogger("matrix-apollo"), fixture.scheduler);
        lunar.start(LunarPolicyConfig.defaults(), new MatrixApollo(LunarClientIntegration.Availability.AVAILABLE, true));
        assertEquals(LunarClientService.RegistrationOutcome.SCHEDULED,
                lunar.handleRegistration(new LunarClientIntegration.Registration(
                        lunarFailurePlayer.getUniqueId(), lunarFailurePlayer.getName(), lunarFailurePlayer)));
        assertEquals(site.vackstudio.vanticheat.lunar.LunarPlayerState.FAILED,
                lunar.snapshot(lunarFailurePlayer.getUniqueId()).state());
        LunarClientService lunarSchedulerFailure = new LunarClientService(
                Logger.getLogger("matrix-apollo-scheduler"), new MatrixFailingScheduler());
        lunarSchedulerFailure.start(LunarPolicyConfig.defaults(), new MatrixApollo(LunarClientIntegration.Availability.AVAILABLE, false));
        assertEquals(LunarClientService.RegistrationOutcome.FAILED,
                lunarSchedulerFailure.handleRegistration(new LunarClientIntegration.Registration(
                        lunarFailurePlayer.getUniqueId(), lunarFailurePlayer.getName(), lunarFailurePlayer)));
        LunarClientService lunarRetired = new LunarClientService(Logger.getLogger("matrix-apollo-retired"),
                fixture.scheduler);
        lunarRetired.start(LunarPolicyConfig.defaults(), new MatrixApollo(LunarClientIntegration.Availability.AVAILABLE, false));
        Player retiredApolloPlayer = players.get(6);
        assertEquals(LunarClientService.RegistrationOutcome.SCHEDULED,
                lunarRetired.handleRegistration(new LunarClientIntegration.Registration(
                        retiredApolloPlayer.getUniqueId(), retiredApolloPlayer.getName(), retiredApolloPlayer)));
        lunarRetired.handleQuit(retiredApolloPlayer.getUniqueId(), retiredApolloPlayer);

        // Mix normal results, timeouts, errors, disconnects, and enforced-path exceptions.
        fixture.enforcementThrows.set(true);
        for (int i = 0; i < fixture.transport.requests.size(); i++) {
            if (i == 1) continue;
            if (i % 6 == 0) fixture.transport.respondOutcome(i, ProbeResponse.Outcome.TIMEOUT);
            else if (i % 6 == 1) fixture.transport.respondOutcome(i, ProbeResponse.Outcome.ERROR);
            else if (i % 6 == 2) fixture.transport.respond(i, "known response");
            else fixture.transport.respond(i, "fallback.probe");
        }
        fixture.transport.respond(replacementRequest, "fallback.probe");
        assertTrue(fixture.module.diagnostics().recentScans().stream().anyMatch(scan ->
                scan.playerId().equals(lunarFailurePlayer.getUniqueId())
                        && scan.result() == DetectionStatus.CLEAN),
                "Apollo action failure must not change the core scan result");

        fixture.listener.stop();
        fixture.module.stop();
        lunar.stop();
        lunarAbsent.stop();
        lunarNotReady.stop();
        lunarSchedulerFailure.stop();
        lunarRetired.stop();
        assertEquals(0, lunar.trackedPlayers());
        assertEquals(0, lunarAbsent.trackedPlayers());
        assertEquals(0, lunarNotReady.trackedPlayers());
        assertEquals(0, lunarSchedulerFailure.trackedPlayers());
        assertEquals(0, lunarRetired.trackedPlayers());
        // FakeTransport delivers every response inline, so no transport operation
        // can outlive its session; the production-transport equivalent is proven
        // in PaperSignProbeTransportTest.
        ReliabilityInvariants.assertTerminalState("matrix storm",
                fixture.module, 0, fixture.listener.activeChecks(),
                lunar.trackedPlayers() + lunarAbsent.trackedPlayers()
                        + lunarNotReady.trackedPlayers() + lunarSchedulerFailure.trackedPlayers()
                        + lunarRetired.trackedPlayers(),
                0);
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.module.diagnostics().activeScans().size());
        assertTrue(fixture.enforcementDecisions.get() <= 28);
        assertTrue(fixture.module.diagnostics().recentScans().size() <= 100);
        fixture.platforms.clear(); // mirrors the plugin's platform-service shutdown cleanup
        assertEquals(0, fixture.platforms.size(), "quit/shutdown release UUID classification state");
    }

    @Test
    void entityRetirementAfterResultReleasesTicketWithoutEnforcement() {
        Fixture fixture = new Fixture(1, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        Player player = player(UUID.randomUUID(), "retired-entity", true);
        fixture.listener.onJoinPlayer(player);
        assertEquals(1, fixture.listener.activeChecks());

        fixture.scheduler.retireEntityScheduling = true;
        fixture.transport.respond(0, "fallback.probe");

        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.enforcementDecisions.get());
    }

    @Test
    void pendingAdmissionUsesReloadedSnapshotAndDisabledReloadCancelsIt() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        fixture.scheduler.holdDelayedTasks = true;
        Player player = player(UUID.randomUUID(), "reload-pending", true);
        fixture.listener.onJoinPlayer(player);
        assertEquals(1, fixture.listener.activeChecks());
        assertEquals(0, fixture.transport.requests.size());

        ProbeDefinition replacement = new ProbeDefinition("reloaded", "Reloaded", "key.reloaded",
                ProbeMode.TRANSLATE, "fallback.reloaded", true, true, true,
                ProbeVerificationStatus.UNVERIFIED);
        ClientDetectionConfig next = config(List.of(replacement), false, 2);
        fixture.module.replaceConfiguration(next);
        fixture.listener.reloadConfiguration(next);
        fixture.scheduler.runDelayed();

        assertEquals(1, fixture.transport.requests.size());
        assertEquals("reloaded", fixture.transport.requests.getFirst().probes().getFirst().id());
        fixture.transport.respond(0, "fallback.reloaded");
        fixture.listener.onQuitPlayer(player.getUniqueId(), player.getName());

        fixture.scheduler.holdDelayedTasks = true;
        Player pending = player(UUID.randomUUID(), "reload-disable", true);
        fixture.listener.onJoinPlayer(pending);
        assertEquals(1, fixture.listener.activeChecks());
        ClientDetectionConfig disabled = new ClientDetectionConfig(true, false, 20, 0,
                List.of(replacement), false, 0, false, List.of(), 2);
        fixture.module.replaceConfiguration(disabled);
        fixture.listener.reloadConfiguration(disabled);
        fixture.scheduler.runDelayed();

        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(1, fixture.transport.requests.size(), "disabled pending ticket cannot start a scan");
    }

    @Test
    void pendingUnknownPlatformRetryCannotSurviveReloadShutdown() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(true, null, null, null));
        fixture.scheduler.holdDelayedTasks = true;
        fixture.listener.onJoinPlayer(player(UUID.randomUUID(), "retry", true));
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.transport.requests.size());

        fixture.listener.stop();
        fixture.scheduler.runDelayed();

        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.transport.requests.size());
    }

    @Test
    void enforcementCallbackExceptionKeepsResultAndReleasesCoordinatorCapacity() {
        Fixture fixture = new Fixture(1, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        Player player = player(UUID.randomUUID(), "enforcement-failure", true);
        fixture.listener.onJoinPlayer(player);
        fixture.enforcementThrows.set(true);

        fixture.transport.respond(0, "known response");

        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(1, fixture.module.diagnostics().detections());
        assertEquals(1, fixture.enforcementDecisions.get());
    }

    @Test
    void playerDisappearanceBeforeEnforcementSkipsActionAndReleasesCapacity() {
        Fixture fixture = new Fixture(1, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        AtomicBoolean online = new AtomicBoolean(true);
        UUID id = UUID.randomUUID();
        Player player = player(id, "vanishing", true, online);
        fixture.listener.onJoinPlayer(player);
        online.set(false);

        fixture.transport.respond(0, "known response");

        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
        assertEquals(0, fixture.enforcementDecisions.get());
        assertEquals(1, fixture.module.diagnostics().detections());
    }

    @Test
    void reentrantDisconnectAndReconnectDuringEnforcementCannotReleaseNewTicket() {
        Fixture fixture = new Fixture(2, false, new ScriptedProvider(false));
        fixture.transport.autoRespond = false;
        UUID id = UUID.randomUUID();
        Player connectionA = player(id, "reentrant", true);
        fixture.listener.onJoinPlayer(connectionA);
        fixture.enforcementHook.set(() -> {
            fixture.listener.onQuitPlayer(id, "reentrant", connectionA);
            fixture.listener.onJoinPlayer(player(id, "reentrant", true));
        });

        fixture.transport.respond(0, "fallback.probe");

        assertEquals(2, fixture.transport.requests.size());
        assertEquals(1, fixture.listener.activeChecks());
        assertEquals(1, fixture.module.activeSessionCount());
        fixture.transport.respond(1, "fallback.probe");
        assertEquals(0, fixture.listener.activeChecks());
        assertEquals(0, fixture.module.activeSessionCount());
    }

    private static Player player(UUID id, String name, boolean firstJoin) {
        return player(id, name, firstJoin, new AtomicBoolean(true));
    }

    private static Player player(UUID id, String name, boolean firstJoin, AtomicBoolean online) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "isOnline" -> online.get();
                    case "hasPlayedBefore" -> !firstJoin;
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ClientDetectionConfig config(List<ProbeDefinition> probes, boolean doubleCheck, int max) {
        return new ClientDetectionConfig(true, doubleCheck, 20, 0, probes,
                true, 0, false, List.of(), max);
    }

    private static ProbeDefinition probe() {
        return new ProbeDefinition("probe", "Probe", "key.probe", ProbeMode.TRANSLATE,
                "fallback.probe", true, true, true, ProbeVerificationStatus.UNVERIFIED,
                "test", "", "", "known response");
    }

    private static final class Fixture {
        private final FakeScheduler scheduler = new FakeScheduler();
        private final FakeTransport transport;
        private final CheckHacksClientDetectionModule module;
        private final AutomaticClientDetectionListener listener;
        private final AtomicInteger enforcementDecisions = new AtomicInteger();
        private final AtomicBoolean trusted = new AtomicBoolean();
        private final AtomicBoolean enforcementThrows = new AtomicBoolean();
        private final AtomicReference<Runnable> enforcementHook = new AtomicReference<>();
        private final ScriptedProvider provider;
        private final ClientPlatformService.Provider platformProvider;
        private final ClientPlatformService platforms;
        private final java.util.Set<UUID> trustedIds = ConcurrentHashMap.newKeySet();

        private Fixture(int maxConcurrent, boolean doubleCheck, ScriptedProvider provider) {
            this(maxConcurrent, doubleCheck, provider, true);
        }

        private Fixture(int maxConcurrent, boolean doubleCheck, ScriptedProvider provider, boolean autoEnabled) {
            this(maxConcurrent, doubleCheck, (ClientPlatformService.Provider) provider, autoEnabled);
        }

        private Fixture(int maxConcurrent, boolean doubleCheck, ClientPlatformService.Provider provider) {
            this(maxConcurrent, doubleCheck, provider, true);
        }

        private Fixture(int maxConcurrent, boolean doubleCheck, ClientPlatformService.Provider provider,
                        boolean autoEnabled) {
            this.provider = provider instanceof ScriptedProvider scripted ? scripted : null;
            this.platformProvider = provider;
            transport = new FakeTransport();
            ClientDetectionConfig config = config(List.of(probe()), doubleCheck, maxConcurrent);
            if (!autoEnabled) {
                config = new ClientDetectionConfig(true, doubleCheck, 20, 0, List.of(probe()),
                        false, 0, false, List.of(), maxConcurrent);
            }
            module = new CheckHacksClientDetectionModule(config, transport);
            module.initialize(new DetectionModuleContext(new PlatformContext(Platform.PAPER, scheduler),
                    FoundationConfig.defaults(), Logger.getLogger("auto-test")));
            module.start();
            platforms = new ClientPlatformService(platformProvider,
                    new ScriptedProvider(false));
            module.setProbeEligibility(id -> platforms.refresh(id).canProbe());
            EnforcementService enforcement = new EnforcementService(result -> {
                enforcementDecisions.incrementAndGet();
                if (enforcementThrows.get()) throw new IllegalStateException("injected enforcement failure");
                Runnable hook = enforcementHook.getAndSet(null);
                if (hook != null) hook.run();
                return new EnforcementDecision(EnforcementAction.NONE, "test policy");
            }, (target, message) -> false, "kick", Logger.getLogger("auto-test"),
                    new TestTrusted(trusted, trustedIds));
            listener = new AutomaticClientDetectionListener(scheduler, module, config,
                    enforcement, Logger.getLogger("auto-test"), platforms);
            platforms.addListener((uuid, classification) -> {
                if (classification.state() == ClientPlatformService.State.BEDROCK) {
                    listener.platformBecameBedrock(uuid);
                }
            });
        }
    }

    private static final class TestTrusted implements TrustedPlayerService {
        private final AtomicBoolean trusted;
        private final java.util.Set<UUID> trustedIds;
        private TestTrusted(AtomicBoolean trusted, java.util.Set<UUID> trustedIds) {
            this.trusted = trusted;
            this.trustedIds = trustedIds;
        }
        @Override public boolean isTrusted(UUID id) { return trusted.get() || trustedIds.contains(id); }
        @Override public boolean add(UUID id, String name) { return false; }
        @Override public boolean remove(UUID id) { return false; }
        @Override public List<TrustedPlayer> list() { return List.of(); }
        @Override public void load() { }
        @Override public void save() { }
    }

    private static final class ScriptedProvider implements ClientPlatformService.Provider {
        private final boolean installed;
        private final List<Boolean> answers;
        private final AtomicInteger answerIndex = new AtomicInteger();
        private volatile Boolean last;
        private volatile Boolean override;
        private ScriptedProvider(boolean installed, Boolean... answers) {
            this.installed = installed;
            this.answers = java.util.Arrays.asList(answers);
            if (!this.answers.isEmpty()) last = this.answers.getFirst();
        }
        @Override public String name() { return "TEST"; }
        @Override public boolean installed() { return installed; }
        @Override public Boolean isBedrock(UUID id) {
            if (override != null) return override;
            int index = answerIndex.getAndIncrement();
            if (index < answers.size()) last = answers.get(index);
            return last;
        }
        private void forceAnswer(boolean answer) { override = answer; }
    }

    private static final class FakeTransport implements ClientProbeTransport {
        private final List<ProbeRequest> requests = new ArrayList<>();
        private final List<java.util.function.Consumer<ProbeResponse>> callbacks = new ArrayList<>();
        private final java.util.Set<Integer> failSendIndices = ConcurrentHashMap.newKeySet();
        private String fixedResponse;
        private boolean autoRespond = true;

        @Override public ProbeHandle send(ProbeRequest request, java.util.function.Consumer<ProbeResponse> callback) {
            requests.add(request);
            callbacks.add(callback);
            if (failSendIndices.contains(requests.size() - 1)) throw new IllegalStateException("injected send failure");
            if (autoRespond) {
                if (fixedResponse != null) respond(requests.size() - 1, fixedResponse);
                else respond(requests.size() - 1, request.probes().getFirst().fallback());
            }
            return new ProbeHandle() {
                private volatile boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }

        private void respond(int index, String line) {
            respondOutcome(index, line, ProbeResponse.Outcome.RESPONSE);
        }

        private void respondOutcome(int index, ProbeResponse.Outcome outcome) {
            respondOutcome(index, "", outcome);
        }

        private void respondOutcome(int index, String line, ProbeResponse.Outcome outcome) {
            ProbeRequest request = requests.get(index);
            if (outcome != ProbeResponse.Outcome.RESPONSE) {
                callbacks.get(index).accept(new ProbeResponse(request.sessionId(), request.target().id(),
                        List.of(), outcome));
                return;
            }
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < request.probes().size(); i++) lines.add(line);
            while (lines.size() < 3) lines.add("");
            lines.add("key.forward");
            callbacks.get(index).accept(new ProbeResponse(request.sessionId(), request.target().id(),
                    lines, outcome));
        }

        @Override public void stop() { }
    }

    private static final class MatrixApollo implements LunarClientIntegration {
        private final LunarClientIntegration.Availability readiness;
        private final boolean failMutation;
        private MatrixApollo(LunarClientIntegration.Availability readiness, boolean failMutation) {
            this.readiness = readiness;
            this.failMutation = failMutation;
        }
        @Override public LunarClientIntegration.Availability availability() { return readiness; }
        @Override public boolean hasSupport(UUID id) { return true; }
        @Override public LunarClientIntegration.MinimapAction disableMinimap(UUID id, Object playerHandle) {
            if (failMutation) throw new IllegalStateException("injected Apollo API failure");
            return LunarClientIntegration.MinimapAction.APPLIED;
        }
        @Override public java.util.Optional<Boolean> minimapStatus(UUID id) {
            return java.util.Optional.empty();
        }
        @Override public void start() { }
        @Override public void stop() { }
    }

    private static final class MatrixFailingScheduler implements Scheduler {
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) {
            throw new IllegalStateException("injected Apollo scheduler failure");
        }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) {
            task.run();
            return handle();
        }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) { task.run(); return handle(); }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }
        private static TaskHandle handle() {
            return new TaskHandle() {
                private boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }
    }

    private static final class FakeScheduler implements Scheduler {
        private volatile boolean retireEntityScheduling;
        private final java.util.Set<String> failEntityNames = ConcurrentHashMap.newKeySet();
        private final java.util.Queue<Runnable> delayed = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private volatile boolean holdDelayedTasks;
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task) {
            if (target.nativeEntity() instanceof Player player && failEntityNames.contains(player.getName())) {
                throw new IllegalStateException("entity scheduler failure");
            }
            task.run();
            return handle();
        }
        @Override public TaskHandle runAtEntity(EntityTarget target, Runnable task, Runnable retired) {
            if (retireEntityScheduling) {
                retired.run();
                return handle();
            }
            return runAtEntity(target, task);
        }
        @Override public TaskHandle runAtLocation(RegionTarget target, Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobal(Runnable task) { task.run(); return handle(); }
        @Override public TaskHandle runGlobalLater(Runnable task, long delayTicks) {
            if (!holdDelayedTasks) task.run();
            else delayed.add(task);
            return handle();
        }
        private void runDelayed() {
            Runnable task;
            while ((task = delayed.poll()) != null) task.run();
        }
        @Override public TaskHandle runAsync(Runnable task) { task.run(); return handle(); }
        @Override public void shutdown() { }
        private static TaskHandle handle() {
            return new TaskHandle() {
                private boolean cancelled;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }
    }
}
