package site.vackstudio.vanticheat.protocol;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VerificationProtocolTest {
    @Test void testGenerateSessionId() {
        VerificationProtocol proto = new VerificationProtocol(1, 3000);
        String id1 = proto.generateSessionId();
        String id2 = proto.generateSessionId();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        assertTrue(id1.contains("-"));
    }

    @Test void testGenerateChallenge() {
        VerificationProtocol proto = new VerificationProtocol(1, 3000);
        byte[] c1 = proto.generateChallenge();
        byte[] c2 = proto.generateChallenge();
        assertNotNull(c1);
        assertNotEquals(c1, c2);
        assertEquals(32, c1.length);
        assertEquals(32, c2.length);
    }

    @Test void testChallengeConversion() {
        VerificationProtocol proto = new VerificationProtocol(1, 3000);
        byte[] challengeBytes = proto.generateChallenge();
        String challengeStr = proto.challengeToString(challengeBytes);
        byte[] challengeBytes2 = proto.challengeFromString(challengeStr);
        assertArrayEquals(challengeBytes, challengeBytes2);
    }

    @Test void testProtocolVersionValidation() {
        VerificationProtocol proto = new VerificationProtocol(1, 3000);
        assertTrue(proto.validateProtocolVersion(1));
        assertFalse(proto.validateProtocolVersion(0));
        assertFalse(proto.validateProtocolVersion(2));
    }
}