package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.config.PluginConfig;
import site.vackstudio.vanticheat.VLogger;

public class KickMessages {

    private final PluginConfig config;

    public KickMessages(PluginConfig config) {
        this.config = config;
    }

    public String verificationRequired() {
        return config.getVerificationRequired();
    }

    public String verificationFailed() {
        return config.getVerificationFailed();
    }

    public String forbiddenMod() {
        return config.getForbiddenMod();
    }

    public String verificationTimeout() {
        return config.getVerificationTimeout();
    }

    public String protocolError() {
        return config.getProtocolError();
    }
}