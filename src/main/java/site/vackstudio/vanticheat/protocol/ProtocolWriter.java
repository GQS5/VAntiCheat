package site.vackstudio.vanticheat.protocol;

public final class ProtocolWriter {

    private ProtocolWriter() {}

    private static final byte PACKET_TYPE_REQUEST = 0;
    private static final byte PACKET_TYPE_CHALLENGE = 1;
    private static final byte PACKET_TYPE_RESPONSE = 2;
    private static final byte PACKET_TYPE_TERMINATE = 3;

    public static byte[] buildRequestPacket(String sessionId, String challenge) {
        return buildPacket(PACKET_TYPE_REQUEST, 1, sessionId, challenge, null);
    }

    public static byte[] buildChallengePacket(String sessionId, String challenge) {
        return buildPacket(PACKET_TYPE_CHALLENGE, 1, sessionId, challenge, null);
    }

    public static byte[] buildResponsePacket(String sessionId, String challenge, byte[] report) {
        return buildPacket(PACKET_TYPE_RESPONSE, 1, sessionId, challenge, report);
    }

    public static byte[] buildTerminatePacket(String sessionId, String reason) {
        byte[] reasonBytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return buildPacket(PACKET_TYPE_TERMINATE, 1, sessionId, null, reasonBytes);
    }

    public static byte[] buildAckPacket(String sessionId) {
        return buildPacket(PACKET_TYPE_RESPONSE, 1, sessionId, null, null);
    }

    private static byte[] buildPacket(byte type, int protocolVersion,
                                       String sessionId, String challenge, byte[] report) {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        byte[] sidBytes = (sessionId != null && !sessionId.isEmpty()) ? sessionId.getBytes(utf8) : null;
        byte[] chBytes = (challenge != null && !challenge.isEmpty()) ? challenge.getBytes(utf8) : null;
        boolean hasSid = sidBytes != null;
        boolean hasCha = chBytes != null;
        boolean hasRep = report != null && report.length > 0;

        if (hasSid && (sidBytes.length > ProtocolConstants.MAX_SESSION_ID_LENGTH || sidBytes.length > 255)) {
            throw new IllegalArgumentException("Session ID exceeds max bytes");
        }
        if (hasCha && (chBytes.length > ProtocolConstants.MAX_CHALLENGE_LENGTH || chBytes.length > 255)) {
            throw new IllegalArgumentException("Challenge exceeds max bytes");
        }
        if (hasRep && report.length > ProtocolConstants.MAX_REPORT_BYTES) {
            throw new IllegalArgumentException("Report exceeds max bytes");
        }

        int totalLen = 2;
        if (hasSid) totalLen += 1 + sidBytes.length;
        if (hasCha) totalLen += 1 + chBytes.length;
        if (hasRep) totalLen += 2 + report.length;

        if (totalLen > ProtocolConstants.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Packet exceeds max payload");
        }

        byte[] packet = new byte[totalLen];
        int off = 0;

        packet[off++] = type;
        packet[off++] = (byte) protocolVersion;

        if (hasSid) {
            packet[off++] = (byte) sidBytes.length;
            System.arraycopy(sidBytes, 0, packet, off, sidBytes.length);
            off += sidBytes.length;
        }

        if (hasCha) {
            packet[off++] = (byte) chBytes.length;
            System.arraycopy(chBytes, 0, packet, off, chBytes.length);
            off += chBytes.length;
        }

        if (hasRep) {
            packet[off++] = (byte) (report.length >>> 8);
            packet[off++] = (byte) (report.length & 0xFF);
            System.arraycopy(report, 0, packet, off, report.length);
            off += report.length;
        }

        return packet;
    }
}