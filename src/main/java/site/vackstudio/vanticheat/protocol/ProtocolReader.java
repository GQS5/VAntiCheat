package site.vackstudio.vanticheat.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ProtocolReader {

    private static final int MAX_STRING_LENGTH = ProtocolConstants.MAX_STRING_LENGTH;
    private static final int MAX_SESSION_ID_LENGTH = ProtocolConstants.MAX_SESSION_ID_LENGTH;
    private static final int MAX_CHALLENGE_LENGTH = ProtocolConstants.MAX_CHALLENGE_LENGTH;
    private static final int MAX_REPORT_BYTES = ProtocolConstants.MAX_REPORT_BYTES;
    private static final int MAX_REASON_LENGTH = ProtocolConstants.MAX_REASON_LENGTH;
    private static final int MAX_ERROR_LENGTH = ProtocolConstants.MAX_ERROR_LENGTH;

    private ProtocolReader() {}

    private static String decodeStrictUtf8(byte[] bytes) throws ProtocolException {
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "Invalid UTF-8: " + e.getMessage());
        }
    }

    private static void readHeader(BinaryReader reader) throws ProtocolException {
        if (reader.remaining() < 2) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet too short");
        }
        try {
            reader.readUnsignedByte(); // type
            reader.readUnsignedByte(); // version
        } catch (IllegalStateException e) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet truncated");
        }
    }

    public static int readPacketType(byte[] packet) throws ProtocolException {
        if (packet == null) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet is null");
        }
        BinaryReader reader = BinaryReader.from(packet);
        if (reader.remaining() < 2) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet too short");
        }
        int type = reader.readUnsignedByte();
        if (type < ProtocolConstants.PacketType.REQUEST.ordinal()
                || type > ProtocolConstants.PacketType.TERMINATE.ordinal()) {
            throw new ProtocolException(ProtocolException.Type.PROTOCOL_MISMATCH,
                    "Invalid packet type: " + type);
        }
        int version = reader.readUnsignedByte();
        if (version != ProtocolConstants.CURRENT_PROTOCOL_VERSION) {
            throw new ProtocolException(ProtocolException.Type.PROTOCOL_MISMATCH,
                    "Unsupported protocol version: " + version);
        }
        return type;
    }

    public static String readSessionId(byte[] packet) throws ProtocolException {
        if (packet == null) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet is null");
        }
        BinaryReader reader = BinaryReader.from(packet);
        readHeader(reader);
        return reader.readBoundedUtf8(ProtocolConstants.MAX_SESSION_ID_LENGTH);
    }

    public static String readChallenge(byte[] packet) throws ProtocolException {
        if (packet == null) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet is null");
        }
        BinaryReader reader = BinaryReader.from(packet);
        readHeader(reader);
        reader.readBoundedUtf8(ProtocolConstants.MAX_SESSION_ID_LENGTH); // skip sessionId
        return reader.readBoundedUtf8(ProtocolConstants.MAX_CHALLENGE_LENGTH);
    }

    public static byte[] readReport(byte[] packet) throws ProtocolException {
        if (packet == null) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet is null");
        }
        BinaryReader reader = BinaryReader.from(packet);
        readHeader(reader);
        reader.readBoundedUtf8(ProtocolConstants.MAX_SESSION_ID_LENGTH); // skip sessionId
        reader.readBoundedUtf8(ProtocolConstants.MAX_CHALLENGE_LENGTH); // skip challenge
        if (reader.remaining() < 2) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Report missing");
        }
        int repLen;
        try {
            repLen = reader.readUnsignedShort();
        } catch (IllegalStateException e) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Report truncated");
        }
        if (repLen > MAX_REPORT_BYTES) {
            throw new ProtocolException(ProtocolException.Type.OVERSIZED,
                    "Report exceeds max bytes: " + repLen);
        }
        if (repLen > reader.remaining()) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "Report truncated");
        }
        byte[] reportData = java.util.Arrays.copyOfRange(reader.data, reader.position, reader.position + repLen);
        reader.position += repLen;
        return reportData;
    }

    public static String readReason(byte[] packet) throws ProtocolException {
        return readTerminateReason(packet);
    }

    public static String readError(byte[] packet) throws ProtocolException {
        return readTerminateReason(packet);
    }

    public static String readTerminateReason(byte[] packet) throws ProtocolException {
        if (packet == null) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet is null");
        }
        BinaryReader reader = BinaryReader.from(packet);
        readHeader(reader);
        reader.readBoundedUtf8(ProtocolConstants.MAX_SESSION_ID_LENGTH); // skip sessionId
        if (reader.remaining() < 2) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Terminate reason missing");
        }
        int len;
        try {
            len = reader.readUnsignedShort();
        } catch (IllegalStateException e) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Terminate reason truncated");
        }
        if (len > MAX_REASON_LENGTH) {
            throw new ProtocolException(ProtocolException.Type.OVERSIZED,
                    "Terminate reason exceeds max length: " + len);
        }
        if (len > reader.remaining()) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "Terminate reason truncated");
        }
        byte[] reasonBytes = java.util.Arrays.copyOfRange(reader.data, reader.position, reader.position + len);
        reader.position += len;
        return decodeStrictUtf8(reasonBytes);
    }

    public static boolean validatePayloadSize(byte[] payload) throws ProtocolException {
        if (payload.length > ProtocolConstants.MAX_PAYLOAD_BYTES) {
            throw new ProtocolException(ProtocolException.Type.OVERSIZED,
                    "Payload exceeds max size");
        }
        return true;
    }

    public static String decodePayload(byte[] data) throws ProtocolException {
        if (data == null) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "Payload is null");
        }
        if (data.length > ProtocolConstants.MAX_PAYLOAD_BYTES) {
            throw new ProtocolException(ProtocolException.Type.OVERSIZED,
                    "Payload exceeds max size");
        }
        try {
            String result = decodeStrictUtf8(data);
            if (!result.equals(new String(result.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))) {
                throw new ProtocolException(ProtocolException.Type.MALFORMED,
                        "Non-roundtrip-safe UTF-8 in payload");
            }
            return result;
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "Invalid UTF-8 in payload: " + e.getMessage());
        }
    }

    public static boolean isSafeString(String s) {
        if (s == null) return false;
        return s.length() <= MAX_STRING_LENGTH
                && s.matches("^[a-zA-Z0-9._:-]+$");
    }

    public static Map.Entry<String, String> readResponseSessionAndChallenge(
            byte[] packet) throws ProtocolException {
        if (packet == null) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED, "Packet is null");
        }
        BinaryReader reader = BinaryReader.from(packet);
        readHeader(reader);
        String sessionId = reader.readBoundedUtf8(ProtocolConstants.MAX_SESSION_ID_LENGTH);
        String challenge = reader.readBoundedUtf8(ProtocolConstants.MAX_CHALLENGE_LENGTH);
        return Map.entry(sessionId, challenge);
    }
}