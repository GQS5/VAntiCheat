package site.vackstudio.vanticheat.client;

import site.vackstudio.vanticheat.protocol.ProtocolConstants;
import site.vackstudio.vanticheat.protocol.ProtocolException;

public class ClientReportTest {
    public static void main(String[] args) throws Exception {
        testEmptyReport();
        testAddMod();
        testDuplicateRemoval();
        testBoundedReportSize();
        testBinaryRoundTrip();
        testBinaryEmptyRoundTrip();
        testBinaryRejectsTrailingBytes();
        System.out.println("All ClientReport tests passed.");
    }

    static void testEmptyReport() {
        ClientReport report = new ClientReport("1", "1.21.11", "Fabric", "Vanilla", "s1", "c1");
        assert report.getModCount() == 0 : "Empty report should have 0 mods";
    }

    static void testAddMod() {
        ClientReport report = new ClientReport("1", "1.21.11", "Fabric", "Vanilla", "s1", "c1");
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        report.addMod(mod);
        assert report.getModCount() == 1 : "Should have 1 mod";
    }

    static void testDuplicateRemoval() {
        ClientReport report = new ClientReport("1", "1.21.11", "Fabric", "Vanilla", "s1", "c1");
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        report.addMod(mod);
        report.addMod(mod);
        assert report.getModCount() == 1 : "Duplicate mod should only appear once";
    }

    static void testBoundedReportSize() {
        ClientReport report = new ClientReport("1", "1.21.11", "Fabric", "Vanilla", "s1", "c1");
        for (int i = 0; i < 1500; i++) {
            ModRecord mod = new ModRecord("mod" + i, "Mod " + i, "1.0", "Fabric", "", "", "", "");
            report.addMod(mod);
        }
        assert report.getModCount() <= ProtocolConstants.MAX_MODS_PER_REPORT : "Report should be bounded";
    }

    static void testBinaryRoundTrip() throws ProtocolException {
        ClientReport report = new ClientReport("1", "1.21.11", "Fabric", "Vanilla", "s1", "c1");
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "abcdef");
        report.addMod(mod);
        byte[] bytes = report.toBytes();
        assert bytes.length > 0 : "Binary report should have bytes";
        ClientReport decoded = ClientReport.fromBytes(bytes);
        assert decoded.getModCount() == 1 : "Decoded report should have 1 mod";
        assert decoded.getMods().get(0).getModId().equals("freecam") : "Decoded mod ID should match";
        assert decoded.getMods().get(0).getJarSha256().equals("abcdef") : "Decoded hash should match";
    }

    static void testBinaryEmptyRoundTrip() throws ProtocolException {
        ClientReport report = new ClientReport("1", "1.21.11", "Fabric", "Vanilla", "s1", "c1");
        byte[] bytes = report.toBytes();
        ClientReport decoded = ClientReport.fromBytes(bytes);
        assert decoded.getModCount() == 0 : "Decoded empty report should have 0 mods";
    }

    static void testBinaryRejectsTrailingBytes() throws ProtocolException {
        ClientReport report = new ClientReport("1", "1.21.11", "Fabric", "Vanilla", "s1", "c1");
        byte[] bytes = report.toBytes();
        byte[] trailing = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        trailing[bytes.length] = 0x00;
        boolean rejected = false;
        try {
            ClientReport.fromBytes(trailing);
        } catch (ProtocolException e) {
            rejected = true;
        }
        assert rejected : "Trailing bytes must be rejected";
    }
}
