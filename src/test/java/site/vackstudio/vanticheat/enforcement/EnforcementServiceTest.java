package site.vackstudio.vanticheat.enforcement;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.detection.DetectionResult;
import site.vackstudio.vanticheat.detection.DetectionStatus;
import site.vackstudio.vanticheat.detection.Evidence;
import site.vackstudio.vanticheat.detection.EvidenceType;
import site.vackstudio.vanticheat.trusted.TrustedPlayerService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcementServiceTest {
    private static final UUID ID = UUID.randomUUID();
    private final AtomicInteger kicks = new AtomicInteger();
    private final AtomicReference<String> kickMessage = new AtomicReference<>();
    private final EnforcementExecutor executor = (target, message) -> {
        kicks.incrementAndGet();
        kickMessage.set(message);
        return true;
    };
    private final EnforcementTarget target = new EnforcementTarget(ID, "tester", true, new Object());

    @Test
    void confirmedDetectionKicksOnceAndRecordsAction() {
        EnforcementService service = service(true);

        EnforcementOutcome first = service.enforce(target, confirmed(DetectionStatus.DETECTED));
        EnforcementOutcome duplicate = service.enforce(target, confirmed(DetectionStatus.DETECTED));

        assertEquals(EnforcementAction.KICK, first.decision().action());
        assertEquals(EnforcementAction.NONE, duplicate.decision().action());
        assertEquals(1, kicks.get());
        assertEquals("KICK", first.result().evidence().getLast().metadata().get("action"));
    }

    @Test
    void nonDetectedStatusesDoNotKick() {
        EnforcementService service = service(true);
        for (DetectionStatus status : new DetectionStatus[]{DetectionStatus.CLEAN, DetectionStatus.ERROR,
                DetectionStatus.PROTECTED, DetectionStatus.UNCERTAIN, DetectionStatus.TIMEOUT,
                DetectionStatus.UNSUPPORTED, DetectionStatus.SKIPPED}) {
            assertEquals(EnforcementAction.NONE, service.enforce(target, confirmed(status)).decision().action());
        }
        assertEquals(0, kicks.get());
    }

    @Test
    void initialDetectionWithoutConfirmationDoesNotKick() {
        EnforcementService service = service(true);
        DetectionResult initial = DetectionResult.of(DetectionStatus.DETECTED, "initial")
                .withEvidence(evidence("INITIAL", "DETECTED"));

        assertEquals(EnforcementAction.NONE, service.enforce(target, initial).decision().action());
        assertEquals(0, kicks.get());
    }

    @Test
    void disabledEnforcementDoesNotKickConfirmedDetection() {
        EnforcementService service = service(false);

        assertEquals(EnforcementAction.NONE,
                service.enforce(target, confirmed(DetectionStatus.DETECTED)).decision().action());
        assertEquals(0, kicks.get());
    }

    @Test
    void offlineTargetRetainsConfirmedResultWithoutKicking() {
        EnforcementService service = service(true);
        EnforcementTarget offline = new EnforcementTarget(ID, "tester", false, new Object());

        EnforcementOutcome outcome = service.enforce(offline, confirmed(DetectionStatus.DETECTED));

        assertEquals(EnforcementAction.NONE, outcome.decision().action());
        assertEquals(DetectionStatus.DETECTED, outcome.result().status());
        assertEquals(0, kicks.get());
    }

    @Test
    void aReconnectWithANewSessionCanBeEnforcedAgain() {
        EnforcementService service = service(true);

        service.enforce(target, confirmedSession("first"));
        EnforcementOutcome reconnect = service.enforce(target, confirmedSession("second"));

        assertEquals(EnforcementAction.KICK, reconnect.decision().action());
        assertEquals(2, kicks.get());
    }

    @Test
    void recentlyHandledKeysExpireWithoutAffectingConcurrentDuplicateProtection() {
        AtomicLong clock = new AtomicLong(10L);
        EnforcementService service = new EnforcementService(new DefaultEnforcementPolicy(true), executor,
                "Cheating detected.", Logger.getLogger("test-enforcement"), TrustedPlayerService.NONE,
                clock::get);
        DetectionResult result = confirmedSession("expiring-session");

        assertEquals(EnforcementAction.KICK, service.enforce(target, result).decision().action());
        assertEquals(EnforcementAction.NONE, service.enforce(target, result).decision().action());
        assertEquals(1, service.retainedEnforcementKeyCount());

        clock.addAndGet(EnforcementService.ENFORCEMENT_KEY_TTL_NANOS + 1);
        assertEquals(EnforcementAction.KICK, service.enforce(target, result).decision().action());
        assertEquals(2, kicks.get());
    }

    @Test
    void enforcementDeduplicationIsBoundedAndFailsClosedAtCapacity() {
        Logger quiet = Logger.getLogger("test-enforcement-bounded");
        quiet.setLevel(java.util.logging.Level.OFF);
        EnforcementService service = new EnforcementService(new DefaultEnforcementPolicy(true), executor,
                "Cheating detected.", quiet);
        for (int index = 0; index < EnforcementService.MAX_RETAINED_ENFORCEMENT_KEYS + 10; index++) {
            EnforcementOutcome outcome = service.enforce(target,
                    confirmedSession("bounded-session-" + index));
            if (index < EnforcementService.MAX_RETAINED_ENFORCEMENT_KEYS) {
                assertEquals(EnforcementAction.KICK, outcome.decision().action());
            } else {
                assertEquals(EnforcementAction.NONE, outcome.decision().action());
                assertEquals("enforcement deduplication capacity reached", outcome.decision().reason());
            }
        }
        assertEquals(EnforcementService.MAX_RETAINED_ENFORCEMENT_KEYS,
                service.retainedEnforcementKeyCount());
        assertEquals(0, service.inFlightEnforcementCount());
        assertEquals(EnforcementService.MAX_RETAINED_ENFORCEMENT_KEYS, kicks.get());
    }

    @Test
    void concurrentDuplicateEnforcementClaimsOnlyOnce() throws Exception {
        AtomicInteger concurrentKicks = new AtomicInteger();
        java.util.concurrent.CountDownLatch enteredExecutor = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseExecutor = new java.util.concurrent.CountDownLatch(1);
        EnforcementService service = new EnforcementService(new DefaultEnforcementPolicy(true),
                (candidate, message) -> {
                    concurrentKicks.incrementAndGet();
                    enteredExecutor.countDown();
                    try {
                        releaseExecutor.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return true;
                }, "kick", Logger.getLogger("test-enforcement"));
        DetectionResult result = confirmedSession("concurrent-session");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.enforce(target, result));
            org.junit.jupiter.api.Assertions.assertTrue(enteredExecutor.await(5,
                    java.util.concurrent.TimeUnit.SECONDS));
            var second = executor.submit(() -> service.enforce(target, result));
            assertEquals(EnforcementAction.NONE, second.get().decision().action());
            releaseExecutor.countDown();
            assertEquals(EnforcementAction.KICK, first.get().decision().action());
            assertEquals(1, concurrentKicks.get());
            assertEquals(0, service.inFlightEnforcementCount());
        } finally {
            releaseExecutor.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void throwingEnforcementActionReleasesInFlightReservationAndStaysDeduplicated() {
        EnforcementService service = new EnforcementService(new DefaultEnforcementPolicy(true),
                (candidate, message) -> { throw new IllegalStateException("kick failed"); },
                "kick", Logger.getLogger("test-enforcement"));
        DetectionResult result = confirmedSession("throwing-kick");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.enforce(target, result));
        assertEquals(0, service.inFlightEnforcementCount());
        assertEquals(1, service.retainedEnforcementKeyCount());
        assertEquals(EnforcementAction.NONE, service.enforce(target, result).decision().action());
    }

    @Test
    void confirmedXaeroJoinResultUsesCentralKickPolicy() {
        EnforcementService service = service(true);

        EnforcementOutcome outcome = service.enforce(target,
                confirmedProbe("xaeros-minimap", "join-session"));

        assertEquals(EnforcementAction.KICK, outcome.decision().action());
        assertEquals("xaeros-minimap", outcome.result().evidence().getLast().metadata().get("probe"));
        assertTrue(kickMessage.get().contains("xaeros-minimap"));
        assertTrue(kickMessage.get().contains("double-check complete"));
        assertEquals(1, kicks.get());
    }

    @Test
    void confirmedXaeroJoinResultStillHonorsTrustedPlayerBypass() {
        TrustedPlayerService trusted = new TrustedPlayerService() {
            @Override public boolean isTrusted(UUID id) { return ID.equals(id); }
            @Override public boolean add(UUID id, String name) { return false; }
            @Override public boolean remove(UUID id) { return false; }
            @Override public java.util.List<site.vackstudio.vanticheat.trusted.TrustedPlayer> list() {
                return java.util.List.of();
            }
            @Override public void load() { }
            @Override public void save() { }
        };
        EnforcementService service = new EnforcementService(new DefaultEnforcementPolicy(true), executor,
                "Cheating detected.", Logger.getLogger("test-enforcement"), trusted);

        assertEquals(EnforcementAction.NONE,
                service.enforce(target, confirmedProbe("xaeros-minimap", "trusted-session"))
                        .decision().action());
        assertEquals(0, kicks.get());
    }

    private EnforcementService service(boolean enabled) {
        return new EnforcementService(new DefaultEnforcementPolicy(enabled), executor,
                "Cheating detected.", Logger.getLogger("test-enforcement"));
    }

    private static DetectionResult confirmed(DetectionStatus status) {
        return DetectionResult.of(status, "result")
                .withEvidence(evidence("CONFIRMATION", status.name()));
    }

    private static Evidence evidence(String pass, String classification) {
        return new Evidence(EvidenceType.OBSERVATION, "test", Instant.EPOCH,
                Map.of("pass", pass, "classification", classification));
    }

    private static DetectionResult confirmedSession(String session) {
        return DetectionResult.of(DetectionStatus.DETECTED, "result")
                .withEvidence(new Evidence(EvidenceType.OBSERVATION, "test", Instant.EPOCH,
                        Map.of("pass", "CONFIRMATION", "classification", "DETECTED", "session", session,
                                "probe", "test-probe")));
    }

    private static DetectionResult confirmedProbe(String probe, String session) {
        return DetectionResult.of(DetectionStatus.DETECTED, "double-check complete")
                .withEvidence(new Evidence(EvidenceType.OBSERVATION, "test", Instant.EPOCH,
                        Map.of("pass", "CONFIRMATION", "classification", "DETECTED",
                                "session", session, "probe", probe, "trigger", "JOIN")));
    }
}
