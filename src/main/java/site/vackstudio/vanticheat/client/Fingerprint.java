package site.vackstudio.vanticheat.client;

public class Fingerprint {

    private final String algorithm;
    private final String hash;

    public Fingerprint(String algorithm, String hash) {
        this.algorithm = algorithm;
        this.hash = hash;
    }

    public Fingerprint(String hash) {
        this("SHA-256", hash);
    }

    public String getAlgorithm() { return algorithm; }
    public String getHash() { return hash; }

    public boolean isValid() {
        if (hash == null || hash.isEmpty()) return false;
        if (algorithm.equals("SHA-256")) {
            return hash.length() == 64;
        }
        return false;
    }

    public static String sha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String sha256(String data) {
        return sha256(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Fingerprint)) return false;
        Fingerprint that = (Fingerprint) o;
        return algorithm.equals(that.algorithm) && hash.equals(that.hash);
    }

    @Override
    public int hashCode() {
        return algorithm.hashCode() * 31 + hash.hashCode();
    }
}