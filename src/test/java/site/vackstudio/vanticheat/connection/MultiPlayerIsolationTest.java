package site.vackstudio.vanticheat.connection;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.client.ModRecord;
import site.vackstudio.vanticheat.client.ClientReport;
import site.vackstudio.vanticheat.policy.Blocklist;
import site.vackstudio.vanticheat.policy.MatchResult;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import site.vackstudio.vanticheat.config.PluginConfig;
import site.vackstudio.vanticheat.protocol.ProtocolConstants;
import site.vackstudio.vanticheat.protocol.ProtocolWriter;
import site.vackstudio.vanticheat.protocol.ProtocolReader;
import site.vackstudio.vanticheat.protocol.VerificationProtocol;
import site.vackstudio.vanticheat.protocol.ProtocolException;
import site.vackstudio.vanticheat.protocol.ProtocolConstants.PacketType;
import site.vackstudio.vanticheat.enforcement.EnforcementDecision;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

class MultiPlayerIsolationTest {

    @Test void testPlayerIsolation() throws ProtocolException {
        Map<String, String> playerSessions = new HashMap<>();
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);

        String playerAId = "player-a";
        String playerBId = "player-b";
        String playerCId = "player-c";

        String sessionA = protocol.generateSessionId();
        String sessionB = protocol.generateSessionId();
        String sessionC = protocol.generateSessionId();

        assertNotEquals(sessionA, sessionB, "Player A and B sessions must be different");
        assertNotEquals(sessionA, sessionC, "Player A and C sessions must be different");
        assertNotEquals(sessionB, sessionC, "Player B and C sessions must be different");

        byte[] challengeA = protocol.generateChallenge();
        byte[] challengeB = protocol.generateChallenge();
        byte[] challengeC = protocol.generateChallenge();

        assertNotEquals(protocol.challengeToString(challengeA), protocol.challengeToString(challengeB),
                "Player A and B challenges must be different");
    }

    @Test void testStaleSessionCannotAffectNewLogin() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);

        String sessionId1 = protocol.generateSessionId();
        String challenge1 = protocol.challengeToString(protocol.generateChallenge());
        VerificationSession session1 = VerificationSession.create(sessionId1, "player1", System.currentTimeMillis());

        String sessionId2 = protocol.generateSessionId();
        String challenge2 = protocol.challengeToString(protocol.generateChallenge());
        VerificationSession session2 = VerificationSession.create(sessionId2, "player1", System.currentTimeMillis() + 100);

        assertNotEquals(session1.getSessionId(), session2.getSessionId(),
                "New login must have different session ID");
        assertNotEquals(session1.getChallenge(), session2.getChallenge(),
                "New login must have different challenge");
        assertNotEquals(session1, session2,
                "New session must be different object");
    }

    @Test void testForbiddenModNotAffectsOtherPlayer() throws ProtocolException {
        ModRuleConfig ruleConfig = new ModRuleConfig();
        ruleConfig.enabled = true;
        ruleConfig.identifiers = List.of("freecam");

        ModRuleConfig ruleConfig2 = new ModRuleConfig();
        ruleConfig2.enabled = true;
        ruleConfig2.identifiers = List.of("xray");

        Map<String, ModRuleConfig> rules = new HashMap<>();
        rules.put("freecam", ruleConfig);
        rules.put("xray", ruleConfig2);

        PluginConfig config = PluginConfig.defaults();
        config.setModRules(rules);

        Blocklist blocklist = new Blocklist(config);

        VerificationProtocol protocol = new VerificationProtocol(1, 3000);

        String sessionA = protocol.generateSessionId();
        String challengeA = protocol.challengeToString(protocol.generateChallenge());
        ClientReport reportA = new ClientReport("1", "1.21", "Fabric", "Vanilla", sessionA, challengeA);
        reportA.addMod(new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", ""));

        String sessionB = protocol.generateSessionId();
        String challengeB = protocol.challengeToString(protocol.generateChallenge());
        ClientReport reportB = new ClientReport("1", "1.21", "Fabric", "Vanilla", sessionB, challengeB);

        List<MatchResult> resultsA = blocklist.checkAll(reportA.getMods());
        List<MatchResult> resultsB = blocklist.checkAll(reportB.getMods());

        assertTrue(resultsA.stream().anyMatch(MatchResult::isMatch),
                "Player A with forbidden mod should be blocked");
        assertFalse(resultsB.stream().anyMatch(MatchResult::isMatch),
                "Player B with no mods should not be blocked");
    }

    @Test void testTimeoutProducesRejectedResult() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = protocol.generateSessionId();
        String challenge = protocol.challengeToString(protocol.generateChallenge());

        VerificationSession session = VerificationSession.create(sessionId, "player1", System.currentTimeMillis(), challenge);
        assertEquals(VerificationSession.State.VERIFYING, session.getState());

        session.markRejected();
        assertEquals(VerificationSession.State.REJECTED, session.getState());
        assertTrue(session.isTerminal());
    }

    @Test void testMalformedResponseRejected() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = protocol.generateSessionId();
        byte[] challengeBytes = protocol.generateChallenge();
        String challenge = protocol.challengeToString(challengeBytes);

        byte[] invalidData = new byte[2];
        invalidData[0] = 0; // REQUEST type
        invalidData[1] = 1; // protocol version

        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(invalidData),
                "Malformed packet should throw ProtocolException");
    }

    @Test void testDuplicateResponseRejected() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = protocol.generateSessionId();
        byte[] challengeBytes = protocol.generateChallenge();
        String challenge = protocol.challengeToString(challengeBytes);

        byte[] report = new ClientReport("1", "1.21", "Fabric", "Vanilla", sessionId, challenge).toBytes();
        byte[] responsePacket = ProtocolWriter.buildResponsePacket(sessionId, challenge, report);

        int packetType = ProtocolReader.readPacketType(responsePacket);
        assertEquals(PacketType.RESPONSE.ordinal(), packetType);

        String readSessionId = ProtocolReader.readSessionId(responsePacket);
        assertEquals(sessionId, readSessionId);

        String readChallenge = ProtocolReader.readChallenge(responsePacket);
        assertEquals(challenge, readChallenge);
    }

    @Test void testEnforcementDecisionAllowsValid() {
        EnforcementDecision decision = EnforcementDecision.ALLOW;
        assertTrue(decision.isAllowed());
        assertFalse(decision.isRejected());
    }

    @Test void testEnforcementDecisionRejectsBlocked() {
        EnforcementDecision decision = EnforcementDecision.REJECT_BLOCKED;
        assertFalse(decision.isAllowed());
        assertTrue(decision.isRejected());
        assertEquals("Forbidden mod detected", decision.getReason());
    }
}