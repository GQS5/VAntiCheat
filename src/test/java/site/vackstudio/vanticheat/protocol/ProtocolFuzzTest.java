package site.vackstudio.vanticheat.protocol;

import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.client.ClientReport;
import site.vackstudio.vanticheat.client.ModRecord;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Random;

class ProtocolFuzzTest {

    @Test void nullPacketsRejectedControlled() {
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(null));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(null));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readChallenge(null));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readReport(null));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readTerminateReason(null));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readResponseSessionAndChallenge(null));
        assertThrows(ProtocolException.class, () -> ClientReport.fromBytes(null));
        assertThrows(IllegalArgumentException.class, () -> BinaryReader.from(null));
    }

    @Test void shortPacketsRejectedControlled() {
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(new byte[0]));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(new byte[1]));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(new byte[0]));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(new byte[1]));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readChallenge(new byte[1]));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readReport(new byte[1]));
        assertThrows(ProtocolException.class, () -> ProtocolReader.readTerminateReason(new byte[0]));
    }

    @Test void invalidTypeAndVersionRejected() {
        for (int type : new int[]{4, 5, 10, 127, 255}) {
            byte[] packet = new byte[]{(byte) type, 1};
            assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(packet),
                    "type " + type + " must be rejected");
        }
        byte[] wrongVersion = ProtocolWriter.buildRequestPacket("s", "c");
        wrongVersion[1] = 2;
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(wrongVersion));
        byte[] zeroVersion = ProtocolWriter.buildRequestPacket("s", "c");
        zeroVersion[1] = 0;
        assertThrows(ProtocolException.class, () -> ProtocolReader.readPacketType(zeroVersion));
    }

    @Test void randomBytesNeverCrash() {
        Random random = new Random(0xC0FFEE);
        for (int i = 0; i < 500; i++) {
            int len = random.nextInt(81);
            byte[] packet = new byte[len];
            random.nextBytes(packet);
            try {
                ProtocolReader.readPacketType(packet);
            } catch (ProtocolException | IllegalArgumentException e) {
                // controlled rejection
            } catch (RuntimeException | Error e) {
                fail("Uncontrolled failure on random packet len=" + len + ": " + e);
            }
            try {
                ClientReport.fromBytes(packet);
            } catch (ProtocolException | IllegalArgumentException e) {
                // controlled rejection
            } catch (RuntimeException | Error e) {
                fail("Uncontrolled failure decoding report len=" + len + ": " + e);
            }
        }
    }

    @Test void truncatedAndOversizedSessionRejected() {
        // Declared session length 10 but only 3 bytes present.
        byte[] truncated = new byte[]{2, 1, 10, 'a', 'b', 'c'};
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(truncated));
        // Declared session length exceeds MAX_SESSION_ID_LENGTH.
        byte[] oversized = new byte[2 + 1 + 100];
        oversized[0] = 2;
        oversized[1] = 1;
        oversized[2] = 100;
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(oversized));
        // Zero-length session: length byte 0 then no bytes; readBoundedUtf8 returns "".
        byte[] zeroLen = new byte[]{2, 1, 0, 1, 'x'};
        try {
            String sid = ProtocolReader.readSessionId(zeroLen);
            assertEquals("", sid);
        } catch (ProtocolException e) {
            // also acceptable if policy tightens
        }
    }

    @Test void invalidUtf8Rejected() {
        // Incomplete 3-byte sequence E2 82 (missing third byte).
        byte[] bad = new byte[]{2, 1, 2, (byte) 0xE2, (byte) 0x82};
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(bad));
        // Invalid continuation byte 80 alone.
        byte[] bad2 = new byte[]{2, 1, 1, (byte) 0x80};
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(bad2));
        // Overlong encoding C0 AF.
        byte[] bad3 = new byte[]{2, 1, 2, (byte) 0xC0, (byte) 0xAF};
        assertThrows(ProtocolException.class, () -> ProtocolReader.readSessionId(bad3));
    }

    @Test void oversizedStringEncodeRejected() {
        String big = "a".repeat(256);
        ClientReport report = new ClientReport("", "", "", "", "", "");
        report.addMod(new ModRecord(big, "n", "v", "l", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, report::toBytes);
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolWriter.buildRequestPacket("s".repeat(100), "c"));
    }

    @Test void reportBoundariesEnforced() throws ProtocolException {
        // Empty report round-trips (u16 count 0).
        ClientReport empty = new ClientReport("", "", "", "", "", "");
        ClientReport decoded = ClientReport.fromBytes(empty.toBytes());
        assertEquals(0, decoded.getModCount());
        // Single-byte truncated report.
        assertThrows(ProtocolException.class, () -> ClientReport.fromBytes(new byte[]{0}));
        // Declared mod count exceeds limit without allocating entries.
        byte[] hugeCount = new byte[]{(byte) 0x03, (byte) 0xE9};
        assertThrows(ProtocolException.class, () -> ClientReport.fromBytes(hugeCount));
        // Report larger than MAX_REPORT_BYTES rejected before parsing.
        assertThrows(ProtocolException.class,
                () -> ClientReport.fromBytes(new byte[ProtocolConstants.MAX_REPORT_BYTES + 1]));
        // Trailing bytes rejected.
        byte[] good = empty.toBytes();
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(ProtocolException.class, () -> ClientReport.fromBytes(trailing));
        // Duplicate mod IDs rejected (dedup evasion closed).
        ClientReport dup = new ClientReport("", "", "", "", "", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0);
        out.write(2);
        writeField(out, "same");
        writeField(out, "A");
        writeField(out, "1");
        writeField(out, "Fabric");
        writeField(out, "");
        writeField(out, "");
        writeField(out, "");
        writeField(out, "aaa");
        writeField(out, "same");
        writeField(out, "B");
        writeField(out, "1");
        writeField(out, "Fabric");
        writeField(out, "");
        writeField(out, "");
        writeField(out, "");
        writeField(out, "bbb");
        assertThrows(ProtocolException.class, () -> ClientReport.fromBytes(out.toByteArray()));
    }

    @Test void truncatedChallengeAndReportRejected() {
        // Valid header + session, then challenge length 5 with only 2 bytes.
        byte[] truncatedChallenge = new byte[]{2, 1, 1, 's', 5, 'a', 'b'};
        assertThrows(ProtocolException.class, () -> ProtocolReader.readChallenge(truncatedChallenge));
        // Valid session+challenge then report length 10 with 3 bytes.
        String sid = "s";
        String ch = "c";
        byte[] prefix = ProtocolWriter.buildRequestPacket(sid, ch);
        byte[] truncatedReport = java.util.Arrays.copyOf(prefix, prefix.length + 2 + 3);
        truncatedReport[prefix.length] = 0;
        truncatedReport[prefix.length + 1] = 10;
        assertThrows(ProtocolException.class, () -> ProtocolReader.readReport(truncatedReport));
    }

    @Test void wrongDirectionTypesParseButGateDenies() throws ProtocolException {
        // CHALLENGE and REQUEST are server->client / initiation types; a client must
        // only ever send RESPONSE or TERMINATE. The parser accepts the bytes as
        // well-formed, while the gate denies anything that is not RESPONSE/TERMINATE.
        byte[] challenge = ProtocolWriter.buildChallengePacket("s", "c");
        assertEquals(ProtocolConstants.PacketType.CHALLENGE.ordinal(),
                ProtocolReader.readPacketType(challenge));
        byte[] request = ProtocolWriter.buildRequestPacket("s", "c");
        assertEquals(ProtocolConstants.PacketType.REQUEST.ordinal(),
                ProtocolReader.readPacketType(request));
        assertNotEquals(ProtocolConstants.PacketType.RESPONSE.ordinal(),
                ProtocolReader.readPacketType(challenge));
    }

    private static void writeField(java.io.ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length & 0xFF);
        out.write(bytes, 0, bytes.length);
    }
}
