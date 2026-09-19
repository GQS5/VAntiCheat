package site.vackstudio.vanticheat.client;

public class ModRecordTest {

    public static void main(String[] args) {
        testModRecordCreation();
        testModRecordEquality();
        testEmptyFields();
        testDifferentLoaders();
        System.out.println("All ModRecord tests passed.");
    }

    static void testModRecordCreation() {
        ModRecord mod = new ModRecord("freecam", "Freecam", "1.0.0", "Fabric", "", "", "abc123", "");
        assert mod.getModId().equals("freecam");
        assert mod.getName().equals("Freecam");
        assert mod.getVersion().equals("1.0.0");
        assert mod.getLoader().equals("Fabric");
        assert mod.getJarSha256().equals("abc123");
    }

    static void testModRecordEquality() {
        ModRecord mod1 = new ModRecord("freecam", "Freecam", "1.0.0", "Fabric", "", "", "", "");
        ModRecord mod2 = new ModRecord("freecam", "Freecam2", "2.0.0", "Forge", "", "", "", "");
        assert mod1.equals(mod2) : "Equal by mod ID";
        assert mod1.hashCode() == mod2.hashCode() : "Hash codes should match";
    }

    static void testEmptyFields() {
        ModRecord mod = new ModRecord(null, null, null, null, null, null, null, null);
        assert mod.getModId().equals("") : "Mod ID should be empty string for null";
        assert mod.getLoader().equals("Unknown") : "Loader should be Unknown for null";
    }

    static void testDifferentLoaders() {
        ModRecord fabric = new ModRecord("freecam", "Freecam", "1.0", "Fabric", "", "", "", "");
        ModRecord forge = new ModRecord("freecam", "Freecam", "1.0", "Forge", "", "", "", "");
        assert !fabric.equals(forge) : "Different loaders should not be equal";
    }
}