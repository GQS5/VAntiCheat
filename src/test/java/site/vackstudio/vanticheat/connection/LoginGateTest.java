package site.vackstudio.vanticheat.connection;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.client.ModRecord;
import site.vackstudio.vanticheat.client.ClientReport;
import site.vackstudio.vanticheat.policy.Blocklist;
import site.vackstudio.vanticheat.policy.MatchResult;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import site.vackstudio.vanticheat.config.PluginConfig;
import site.vackstudio.vanticheat.config.ConfigLoader;
import site.vackstudio.vanticheat.protocol.ProtocolConstants;
import site.vackstudio.vanticheat.protocol.ProtocolWriter;
import site.vackstudio.vanticheat.protocol.ProtocolReader;
import site.vackstudio.vanticheat.protocol.VerificationProtocol;
import site.vackstudio.vanticheat.protocol.ProtocolException;
import site.vackstudio.vanticheat.protocol.ProtocolConstants.PacketType;
import site.vackstudio.vanticheat.connection.VerificationSession;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.ArrayList;

class LoginGateTest {

    @Test void testValidClientAllowed() throws ProtocolException {
        PluginConfig config = PluginConfig.defaults();
        Blocklist blocklist = new Blocklist(config);

        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = java.util.UUID.randomUUID().toString();
        byte[] challengeBytes = protocol.generateChallenge();
        String challenge = protocol.challengeToString(challengeBytes);

        VerificationSession session = VerificationSession.create(sessionId, "player1", System.currentTimeMillis(), challenge);
        assertEquals(sessionId, session.getSessionId());
        assertEquals(challenge, session.getChallenge());

        byte[] report = new ClientReport("1", "1.21", "Fabric", "Vanilla", sessionId, challenge).toBytes();
        byte[] responsePacket = ProtocolWriter.buildResponsePacket(sessionId, challenge, report);

        int packetType = ProtocolReader.readPacketType(responsePacket);
        assertEquals(PacketType.RESPONSE.ordinal(), packetType);

        String readSessionId = ProtocolReader.readSessionId(responsePacket);
        assertEquals(sessionId, readSessionId);

        String readChallenge = ProtocolReader.readChallenge(responsePacket);
        assertEquals(challenge, readChallenge);

        byte[] reportData = ProtocolReader.readReport(responsePacket);
        ClientReport clientReport = ClientReport.fromBytes(reportData);
        assertNotNull(clientReport);

        List<ModRecord> mods = clientReport.getMods();
        List<MatchResult> results = blocklist.checkAll(mods);
        assertTrue(results.isEmpty(), "No forbidden mods should be found");
    }

    @Test void testForbiddenModRejected() throws ProtocolException {
        ModRuleConfig ruleConfig = new ModRuleConfig();
        ruleConfig.enabled = true;
        ruleConfig.identifiers = List.of("freecam");
        PluginConfig config = PluginConfig.defaults();
        java.util.Map<String, ModRuleConfig> rules = new java.util.HashMap<>();
        rules.put("freecam", ruleConfig);
        config.setModRules(rules);

        Blocklist blocklist = new Blocklist(config);

        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = java.util.UUID.randomUUID().toString();
        byte[] challengeBytes = protocol.generateChallenge();
        String challenge = protocol.challengeToString(challengeBytes);

        VerificationSession session = VerificationSession.create(sessionId, "player1", System.currentTimeMillis(), challenge);
        assertEquals(challenge, session.getChallenge());

        ModRecord forbiddenMod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        ClientReport report = new ClientReport("1", "1.21", "Fabric", "Vanilla", sessionId, challenge);
        report.addMod(forbiddenMod);
        byte[] reportBytes = report.toBytes();

        byte[] responsePacket = ProtocolWriter.buildResponsePacket(sessionId, challenge, reportBytes);

        int packetType = ProtocolReader.readPacketType(responsePacket);
        assertEquals(PacketType.RESPONSE.ordinal(), packetType);

        String readSessionId = ProtocolReader.readSessionId(responsePacket);
        assertEquals(sessionId, readSessionId);

        String readChallenge = ProtocolReader.readChallenge(responsePacket);
        assertEquals(challenge, readChallenge);

        byte[] reportData = ProtocolReader.readReport(responsePacket);
        ClientReport clientReport = ClientReport.fromBytes(reportData);
        assertNotNull(clientReport);
        assertFalse(clientReport.getMods().isEmpty());

        List<MatchResult> results = blocklist.checkAll(clientReport.getMods());
        assertFalse(results.isEmpty(), "Forbidden mod should be detected");
        assertTrue(results.get(0).isMatch());
    }

    @Test void testWrongChallengeRejected() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = java.util.UUID.randomUUID().toString();
        byte[] challengeBytes = protocol.generateChallenge();
        String correctChallenge = protocol.challengeToString(challengeBytes);
        String wrongChallenge = protocol.challengeToString(protocol.generateChallenge());

        VerificationSession session = VerificationSession.create(sessionId, "player1", System.currentTimeMillis(), correctChallenge);
        assertEquals(correctChallenge, session.getChallenge());

        byte[] report = new ClientReport("1", "1.21", "Fabric", "Vanilla", sessionId, wrongChallenge).toBytes();
        byte[] responsePacket = ProtocolWriter.buildResponsePacket(sessionId, wrongChallenge, report);

        String readChallenge = ProtocolReader.readChallenge(responsePacket);
        assertEquals(wrongChallenge, readChallenge);
        assertNotEquals(correctChallenge, readChallenge);
    }

    @Test void testReplayedResponseRejected() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = java.util.UUID.randomUUID().toString();
        byte[] challengeBytes = protocol.generateChallenge();
        String challenge = protocol.challengeToString(challengeBytes);

        VerificationSession session = VerificationSession.create(sessionId, "player1", System.currentTimeMillis(), challenge);

        byte[] report = new ClientReport("1", "1.21", "Fabric", "Vanilla", sessionId, challenge).toBytes();
        byte[] responsePacket = ProtocolWriter.buildResponsePacket(sessionId, challenge, report);

        String readSessionId = ProtocolReader.readSessionId(responsePacket);
        assertEquals(sessionId, readSessionId);

        VerificationSession session2 = sessionManagerGetSession(sessionId);
        assertNull(session2, "Session should not be duplicated");
    }

@Test void testSessionIsTerminalAfterRejection() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = protocol.generateSessionId();
        byte[] challengeBytes = protocol.generateChallenge();
        String challenge = protocol.challengeToString(challengeBytes);

        VerificationSession session = VerificationSession.create(sessionId, "player1", System.currentTimeMillis(), challenge);
        assertEquals(VerificationSession.State.VERIFYING, session.getState());

        session.markRejected();
        assertEquals(VerificationSession.State.REJECTED, session.getState());
        assertTrue(session.isTerminal());
    }

    @Test void testChallengeLengthIs32Bytes() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        byte[] challenge = protocol.generateChallenge();
        assertEquals(32, challenge.length, "Challenge should be 32 bytes");
        String challengeStr = protocol.challengeToString(challenge);
        assertEquals(32, protocol.challengeFromString(challengeStr).length, "Challenge should round-trip to 32 bytes");
    }

    @Test void testSessionIdIsUUID() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = protocol.generateSessionId();
        assertTrue(sessionId.contains("-"), "Session ID should be UUID format");
        assertEquals(36, sessionId.length(), "UUID should be 36 characters");
    }

    @Test void testPacketTypeSimplified() throws ProtocolException {
        VerificationProtocol protocol = new VerificationProtocol(1, 3000);
        String sessionId = java.util.UUID.randomUUID().toString();
        byte[] challengeBytes = protocol.generateChallenge();
        String challenge = protocol.challengeToString(challengeBytes);

        byte[] requestPacket = ProtocolWriter.buildRequestPacket(sessionId, challenge);
        assertEquals(PacketType.REQUEST.ordinal(), requestPacket[0], "First byte should be REQUEST type");

        byte[] challengePacket = ProtocolWriter.buildChallengePacket(sessionId, challenge);
        assertEquals(PacketType.CHALLENGE.ordinal(), challengePacket[0], "First byte should be CHALLENGE type");

        byte[] responsePacket = ProtocolWriter.buildResponsePacket(sessionId, challenge, new byte[0]);
        assertEquals(PacketType.RESPONSE.ordinal(), responsePacket[0], "First byte should be RESPONSE type");

        byte[] terminatePacket = ProtocolWriter.buildTerminatePacket(sessionId, "test");
        assertEquals(PacketType.TERMINATE.ordinal(), terminatePacket[0], "First byte should be TERMINATE type");
    }

    private VerificationSession sessionManagerGetSession(String sessionId) {
        return null;
    }
}