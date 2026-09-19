package site.vackstudio.vanticheat.protocol;

import java.security.SecureRandom;
import java.util.UUID;

public final class VerificationProtocol {

    private final int protocolVersion;
    private final int timeoutMs;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public VerificationProtocol(int protocolVersion, int timeoutMs) {
        this.protocolVersion = protocolVersion;
        this.timeoutMs = timeoutMs;
    }

    public VerificationProtocol(com.velocitypowered.api.proxy.ProxyServer server,
                                com.velocitypowered.api.plugin.PluginContainer plugin) {
        this.protocolVersion = ProtocolConstants.CURRENT_PROTOCOL_VERSION;
        this.timeoutMs = 3000;
    }

    public String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    public byte[] generateChallenge() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    public String challengeToString(byte[] challenge) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
    }

    public byte[] challengeFromString(String challenge) {
        return java.util.Base64.getUrlDecoder().decode(challenge);
    }

    public int getProtocolVersion() { return protocolVersion; }
    public int getTimeoutMs() { return timeoutMs; }

    public boolean validateProtocolVersion(int clientVersion) {
        return clientVersion >= ProtocolConstants.MIN_PROTOCOL_VERSION
                && clientVersion <= ProtocolConstants.MAX_PROTOCOL_VERSION;
    }
}