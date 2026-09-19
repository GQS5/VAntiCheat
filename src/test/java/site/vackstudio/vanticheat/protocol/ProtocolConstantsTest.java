package site.vackstudio.vanticheat.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolConstantsTest {
    @Test void testProtocolVersion() {
        assertEquals(1, ProtocolConstants.CURRENT_PROTOCOL_VERSION);
        assertEquals(1, ProtocolConstants.MIN_PROTOCOL_VERSION);
        assertEquals(1, ProtocolConstants.MAX_PROTOCOL_VERSION);
    }
    @Test void testMaxPayload() {
        assertEquals(65535, ProtocolConstants.MAX_PAYLOAD_BYTES);
    }
    @Test void testMaxStringLength() {
        assertEquals(256, ProtocolConstants.MAX_STRING_LENGTH);
    }
    @Test void testMaxSessionId() {
        assertEquals(36, ProtocolConstants.MAX_SESSION_ID_LENGTH);
    }
    @Test void testMaxChallenge() {
        assertEquals(44, ProtocolConstants.MAX_CHALLENGE_LENGTH);
    }
}
