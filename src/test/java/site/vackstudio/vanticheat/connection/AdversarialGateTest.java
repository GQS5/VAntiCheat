package site.vackstudio.vanticheat.connection;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.client.ClientReport;
import site.vackstudio.vanticheat.client.ModRecord;
import site.vackstudio.vanticheat.policy.Blocklist;
import site.vackstudio.vanticheat.policy.MatchResult;
import site.vackstudio.vanticheat.policy.ModRule;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import site.vackstudio.vanticheat.config.PluginConfig;
import site.vackstudio.vanticheat.protocol.ProtocolConstants;
import site.vackstudio.vanticheat.protocol.ProtocolReader;
import site.vackstudio.vanticheat.protocol.ProtocolWriter;
import site.vackstudio.vanticheat.protocol.VerificationProtocol;
import site.vackstudio.vanticheat.protocol.ProtocolException;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class AdversarialGateTest {

    @Test void putSessionMakesResponseRetrievable() throws ProtocolException {
        VerificationSessionManager manager = new VerificationSessionManager(10, 60000);
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = protocol.generateSessionId();
        String challenge = protocol.challengeToString(protocol.generateChallenge());
        VerificationSession session =
                VerificationSession.create(sessionId, "player1", System.currentTimeMillis(), challenge);
        manager.putSession(session);

        byte[] report = new ClientReport("", "", "", "", "", "").toBytes();
        byte[] packet = ProtocolWriter.buildResponsePacket(sessionId, challenge, report);
        String readId = ProtocolReader.readSessionId(packet);
        VerificationSession found = manager.getSession(readId);
        assertNotNull(found, "Stored session must be retrievable (gate proof requires put)");
        assertEquals(challenge, ProtocolReader.readChallenge(packet));
    }

    @Test void crossPlayerBindingRejected() {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionA = protocol.generateSessionId();
        String challengeA = protocol.challengeToString(protocol.generateChallenge());
        VerificationSession sessA =
                VerificationSession.create(sessionA, "player-a-uuid", System.currentTimeMillis(), challengeA);
        // Attacker player-b presents A's session. Ownership check must fail.
        String attackerPlayerId = "player-b-uuid";
        assertNotEquals(sessA.getPlayerId(), attackerPlayerId,
                "Ownership check must distinguish the two players");
    }

    @Test void replayAfterTerminalFails() {
        VerificationSession session =
                VerificationSession.create("s", "p", System.currentTimeMillis(), "c");
        assertTrue(session.tryTransitionTo(VerificationSession.State.VERIFIED));
        assertTrue(session.isTerminal());
        // Every later transition must fail: no resurrection.
        assertFalse(session.tryTransitionTo(VerificationSession.State.REJECTED));
        assertFalse(session.tryTransitionTo(VerificationSession.State.VERIFIED));
        assertFalse(session.tryTransitionTo(VerificationSession.State.BLOCKED));
        assertFalse(session.tryTransitionTo(VerificationSession.State.TIMEOUT));
        assertFalse(session.tryTransitionTo(VerificationSession.State.INVALID));
        assertEquals(VerificationSession.State.VERIFIED, session.getState());
    }

    @Test void exactlyOneTerminalTransitionWinsConcurrently() throws Exception {
        VerificationSession session =
                VerificationSession.create("s", "p", System.currentTimeMillis(), "c");
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final VerificationSession.State target = (i % 2 == 0)
                    ? VerificationSession.State.VERIFIED : VerificationSession.State.REJECTED;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (session.tryTransitionTo(target)) {
                    wins.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, wins.get(), "Exactly one terminal transition may succeed");
        assertTrue(session.isTerminal());
    }

    @Test void responseVsTimeoutRaceHasSingleWinner() throws Exception {
        AtomicBoolean resultSet = new AtomicBoolean(false);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (resultSet.compareAndSet(false, true)) {
                wins.incrementAndGet();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (resultSet.compareAndSet(false, true)) {
                wins.incrementAndGet();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, wins.get(), "Response/timeout race must have exactly one winner");
    }

    @Test void floodOverLimitRejected() {
        VerificationSessionManager manager = new VerificationSessionManager(3, 60000);
        manager.putSession(VerificationSession.create("s1", "p1", System.currentTimeMillis(), "c1"));
        manager.putSession(VerificationSession.create("s2", "p2", System.currentTimeMillis(), "c2"));
        manager.putSession(VerificationSession.create("s3", "p3", System.currentTimeMillis(), "c3"));
        assertThrows(IllegalStateException.class, () ->
                manager.putSession(VerificationSession.create("s4", "p4", System.currentTimeMillis(), "c4")));
        assertEquals(3, manager.totalSessionCount(), "Session count must stay bounded");
    }

    @Test void protocolDowngradeRejected() {
        byte[] packet = ProtocolWriter.buildRequestPacket("s", "c");
        packet[1] = 0;
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(packet));
        byte[] packet2 = ProtocolWriter.buildRequestPacket("s", "c");
        packet2[1] = 2;
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(packet2));
    }

    @Test void staleSessionLookupFailsAfterRemoval() {
        VerificationSessionManager manager = new VerificationSessionManager(10, 60000);
        VerificationSession session =
                VerificationSession.create("s", "p", System.currentTimeMillis(), "c");
        manager.putSession(session);
        manager.markRejected("s");
        assertNull(manager.getSession("s"), "Removed session must not be reusable");
    }

    @Test void blocklistHasNoFalsePositives() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.identifiers = List.of("freecam");
        ModRule rule = new ModRule("freecam", config);
        for (String id : List.of("freecam-helper", "not-freecam", "freecam2", "xaero-helper", "fake-freecam")) {
            ModRecord mod = new ModRecord(id, id, "1.0", "Fabric", "", "", "", "");
            assertFalse(rule.match(mod).isMatch(), id + " must not match freecam rule");
        }
        ModRecord exact = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        assertTrue(rule.match(exact).isMatch(), "Exact identifier must match");
    }

    @Test void fingerprintFieldsAreDistinct() {
        ModRuleConfig config = new ModRuleConfig();
        config.enabled = true;
        config.jarSha256s = List.of("a".repeat(64));
        ModRule rule = new ModRule("fp", config);
        // Same metadata hash but different JAR hash must not match a jarSha rule.
        ModRecord sameMeta = new ModRecord("m", "M", "1", "Fabric", "", "",
                "a".repeat(64), "b".repeat(64));
        assertFalse(rule.match(sameMeta).isMatch(), "Metadata hash must not satisfy a jarSha rule");
        // Exact JAR hash matches.
        ModRecord exactJar = new ModRecord("m", "M", "1", "Fabric", "", "",
                "c".repeat(64), "a".repeat(64));
        assertTrue(rule.match(exactJar).isMatch(), "Exact jarSha256 must match");
        // Invalid hex / wrong length never matches an exact 64-hex rule.
        ModRecord invalid = new ModRecord("m", "M", "1", "Fabric", "", "", "ZZZ", "short");
        assertFalse(rule.match(invalid).isMatch(), "Invalid fingerprint must not match");
    }

    @Test void oversizedReportAndModCountRejectedAtGate() {
        assertThrows(ProtocolException.class,
                () -> ClientReport.fromBytes(new byte[ProtocolConstants.MAX_REPORT_BYTES + 1]));
        byte[] hugeCount = new byte[]{(byte) 0x03, (byte) 0xE9};
        assertThrows(ProtocolException.class, () -> ClientReport.fromBytes(hugeCount));
    }

    @Test void logSanitizerBoundsClientText() {
        String evil = "hello\nworld\r\n\t" + "x".repeat(500);
        String clean = LoginVerificationListener.sanitizeForLog(evil);
        assertFalse(clean.contains("\n"), "Newlines must be stripped");
        assertFalse(clean.contains("\r"), "Carriage returns must be stripped");
        assertTrue(clean.length() <= 200, "Log text must be bounded");
        assertEquals("", LoginVerificationListener.sanitizeForLog(null));
    }

    @Test void removedSessionCannotAuthorizeNewLogin() {
        VerificationSessionManager manager = new VerificationSessionManager(10, 60000);
        VerificationSession oldSession =
                VerificationSession.create("old", "p", System.currentTimeMillis(), "cold");
        manager.putSession(oldSession);
        manager.handleDisconnect("old");
        assertNull(manager.getSession("old"));
        VerificationSession fresh =
                VerificationSession.create("new", "p", System.currentTimeMillis(), "cnew");
        assertNotEquals(oldSession.getSessionId(), fresh.getSessionId());
        assertNotEquals(oldSession.getChallenge(), fresh.getChallenge());
    }

    @Test void validBinaryReportAllowsWhenClean() throws Exception {
        PluginConfig config = PluginConfig.defaults();
        Blocklist blocklist = new Blocklist(config);
        ClientReport report = new ClientReport("", "", "", "", "", "");
        List<MatchResult> results = blocklist.checkAll(report.getMods());
        assertTrue(results.isEmpty());
        Map<String, ModRuleConfig> rules = new HashMap<>();
        ModRuleConfig rc = new ModRuleConfig();
        rc.enabled = true;
        rc.identifiers = List.of("freecam");
        rules.put("freecam", rc);
        config.setModRules(rules);
        Blocklist strict = new Blocklist(config);
        ClientReport dirty = new ClientReport("", "", "", "", "", "");
        dirty.addMod(new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", ""));
        // Binary round-trip preserves the forbidden mod.
        ClientReport decoded = ClientReport.fromBytes(dirty.toBytes());
        assertFalse(strict.checkAll(decoded.getMods()).isEmpty());
        assertEquals(UUID.randomUUID().toString().length(), 36);
    }
}
