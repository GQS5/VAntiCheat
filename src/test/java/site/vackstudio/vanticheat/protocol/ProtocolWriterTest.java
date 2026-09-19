package site.vackstudio.vanticheat.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolWriterTest {
    @Test void testRequestPacket() {
        byte[] packet = ProtocolWriter.buildRequestPacket("session-1", "challenge-1");
        assert packet.length > 0 : "Packet should have data";
        assert packet[0] == 0 : "Packet type should be REQUEST (0)";
        assert packet[1] == 1 : "Protocol version should be 1";
    }

    @Test void testChallengePacket() {
        byte[] packet = ProtocolWriter.buildChallengePacket("session-1", "challenge-1");
        assert packet.length > 0 : "Packet should have data";
        assert packet[0] == 1 : "Packet type should be CHALLENGE (1)";
    }

    @Test void testResponsePacket() {
        byte[] report = "report-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] packet = ProtocolWriter.buildResponsePacket("session-1", "challenge-1", report);
        assert packet.length > 0 : "Packet should have data";
        assert packet[0] == 2 : "Packet type should be RESPONSE (2)";
    }

    @Test void testTerminatePacket() throws ProtocolException {
        byte[] packet = ProtocolWriter.buildTerminatePacket("session-2", "forbidden mod");
        assert packet.length > 0 : "Packet should have data";
        assert packet[0] == 3 : "Packet type should be TERMINATE (3)";
        String reason = ProtocolReader.readTerminateReason(packet);
        assertEquals("forbidden mod", reason);
    }

    @Test void testPacketPayloadSizeLimit() {
        byte[] largeReport = new byte[ProtocolConstants.MAX_PAYLOAD_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () ->
            ProtocolWriter.buildResponsePacket("s", "c", largeReport));
    }
}