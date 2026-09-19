package site.vackstudio.vanticheat.protocol;

public final class BinaryReader {

    byte[] data;
    int position;

    public BinaryReader(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Data is null");
        }
        this.data = data;
        this.position = 0;
    }

    public int position() {
        return position;
    }

    public int remaining() {
        return data.length - position;
    }

    public boolean requireFullyConsumed() {
        return position == data.length;
    }

    public byte readUnsignedByte() {
        if (position >= data.length) {
            throw new IllegalStateException("Cannot read beyond data end");
        }
        return data[position++];
    }

    public int readUnsignedShort() {
        if (position + 2 > data.length) {
            throw new IllegalStateException("Cannot read 2 bytes beyond data end");
        }
        int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        position += 2;
        return value;
    }

    public String readBoundedUtf8(int maxLength) throws ProtocolException {
        if (position >= data.length) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "Unexpected end of data");
        }
        int start = position;
        // Read length byte
        int len = data[position] & 0xFF;
        position++;
        if (len > maxLength) {
            throw new ProtocolException(ProtocolException.Type.OVERSIZED,
                    "String length exceeds max: " + len);
        }
        if (position + len > data.length) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "String truncated");
        }
        byte[] bytes = java.util.Arrays.copyOfRange(data, position, position + len);
        position += len;
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String s = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            // Round-trip check retained as defense-in-depth.
            if (!s.equals(new String(s.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8))) {
                throw new ProtocolException(ProtocolException.Type.MALFORMED,
                        "Non-roundtrip-safe UTF-8");
            }
            return s;
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException(ProtocolException.Type.MALFORMED,
                    "Invalid UTF-8: " + e.getMessage());
        }
    }

    public static BinaryReader from(byte[] data) {
        return new BinaryReader(data);
    }
}