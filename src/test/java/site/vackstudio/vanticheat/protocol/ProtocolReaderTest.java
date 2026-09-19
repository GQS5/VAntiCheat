package site.vackstudio.vanticheat.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolReaderTest {
    @Test void testReadPacketType() throws Exception {
        byte[] packet = ProtocolWriter.buildRequestPacket("abc123", "xyz");
        int type = ProtocolReader.readPacketType(packet);
        assert type == 0 : "Packet type should be REQUEST (0)";
    }

    @Test void testReadSessionId() throws Exception {
        byte[] packet = ProtocolWriter.buildRequestPacket("abc123", "xyz");
        String sid = ProtocolReader.readSessionId(packet);
        assertEquals("abc123", sid);
    }

    @Test void testReadChallenge() throws Exception {
        byte[] packet = ProtocolWriter.buildChallengePacket("abc123", "xyz");
        String ch = ProtocolReader.readChallenge(packet);
        assertEquals("xyz", ch);
    }

    @Test void testMissingSessionId() throws Exception {
        byte[] packet = new byte[2];
        packet[0] = 0; // REQUEST
        packet[1] = 1; // protocol version 1
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(packet));
    }

    @Test void testOversizedPayload() {
        byte[] payload = new byte[ProtocolConstants.MAX_PAYLOAD_BYTES + 1];
        assertThrows(ProtocolException.class, () -> ProtocolReader.validatePayloadSize(payload));
    }

    @Test void testDecodePayload() throws Exception {
        String data = "hello world";
        byte[] payload = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String decoded = ProtocolReader.decodePayload(payload);
        assertEquals(data, decoded);
    }

    @Test void testSafeString() {
        assertTrue(ProtocolReader.isSafeString("abc123"));
        assertFalse(ProtocolReader.isSafeString("test; DROP TABLE"));
    }

    @Test void testReadReason() throws Exception {
        byte[] packet = ProtocolWriter.buildTerminatePacket("session-1", "forbidden mod");
        String reason = ProtocolReader.readReason(packet);
        assertEquals("forbidden mod", reason);
    }

    @Test void testReadError() throws Exception {
        byte[] packet = ProtocolWriter.buildTerminatePacket("session-2", "protocol error");
        String error = ProtocolReader.readError(packet);
        assertEquals("protocol error", error);
    }

    @Test void testReadReport() throws Exception {
        byte[] report = "report-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] packet = ProtocolWriter.buildResponsePacket("session-1", "challenge-1", report);
        byte[] decodedReport = ProtocolReader.readReport(packet);
        assert decodedReport.length > 0 : "Report should have data";
    }

    @Test void testInvalidPacketType() throws Exception {
        byte[] packet = new byte[2];
        packet[0] = 10; // Invalid type
        packet[1] = 1; // protocol version 1
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(packet));
    }

    @Test void testUnsupportedVersion() throws Exception {
        byte[] packet = ProtocolWriter.buildRequestPacket("abc123", "xyz");
        packet[1] = 99; // Invalid version
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(packet));
    }

    @Test void testTruncatedPacket() throws Exception {
        byte[] packet = new byte[1]; // Too short
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(packet));
    }

    @Test void testReadTerminateReason() throws Exception {
        byte[] packet = ProtocolWriter.buildTerminatePacket("session-1", "terminated");
        String reason = ProtocolReader.readTerminateReason(packet);
        assertEquals("terminated", reason);
    }
}