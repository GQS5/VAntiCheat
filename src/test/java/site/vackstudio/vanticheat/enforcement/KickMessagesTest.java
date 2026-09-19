package site.vackstudio.vanticheat.enforcement;

import site.vackstudio.vanticheat.config.PluginConfig;

public class KickMessagesTest {
    public static void main(String[] args) {
        testAllMessages();
        System.out.println("All KickMessages tests passed.");
    }

    static void testAllMessages() {
        PluginConfig config = PluginConfig.defaults();
        KickMessages messages = new KickMessages(config);
        assert messages.verificationRequired().equals(config.getVerificationRequired());
        assert messages.verificationFailed().equals(config.getVerificationFailed());
        assert messages.forbiddenMod().equals(config.getForbiddenMod());
        assert messages.verificationTimeout().equals(config.getVerificationTimeout());
        assert messages.protocolError().equals(config.getProtocolError());
    }
}
