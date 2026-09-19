package site.vackstudio.vanticheat.client;

import java.security.MessageDigest;

public class FingerprintTest {

    public static void main(String[] args) {
        testSha256();
        testFingerprintValidity();
        testFingerprintEquality();
        testInvalidFingerprint();
        System.out.println("All Fingerprint tests passed.");
    }

    static void testSha256() {
        String hash = Fingerprint.sha256("hello");
        assert hash.length() == 64 : "SHA-256 should produce 64 hex chars";
        String hash2 = Fingerprint.sha256("hello");
        assert hash.equals(hash2) : "Same input should produce same hash";
        String hash3 = Fingerprint.sha256("world");
        assert !hash.equals(hash3) : "Different input should produce different hash";
    }

    static void testFingerprintValidity() {
        Fingerprint fp = new Fingerprint(Fingerprint.sha256("test"));
        assert fp.isValid() : "Valid SHA-256 should be valid";
        assert fp.getAlgorithm().equals("SHA-256");
    }

    static void testFingerprintEquality() {
        String h = Fingerprint.sha256("test");
        Fingerprint fp1 = new Fingerprint("SHA-256", h);
        Fingerprint fp2 = new Fingerprint("SHA-256", h);
        assert fp1.equals(fp2);
        assert fp1.hashCode() == fp2.hashCode();
    }

    static void testInvalidFingerprint() {
        Fingerprint fp = new Fingerprint("SHA-256", "not-a-valid-hash");
        assert !fp.isValid() : "Invalid hash should not be valid";
        Fingerprint fp2 = new Fingerprint("SHA-256", "");
        assert !fp2.isValid();
    }
}