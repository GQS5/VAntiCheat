package site.vackstudio.vanticheat.connection;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.network.ProtocolState;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;
import site.vackstudio.vanticheat.VAntiCheatPlugin;
import site.vackstudio.vanticheat.client.ClientReport;
import site.vackstudio.vanticheat.config.ModRuleConfig;
import site.vackstudio.vanticheat.config.PluginConfig;
import site.vackstudio.vanticheat.policy.Blocklist;
import site.vackstudio.vanticheat.protocol.ProtocolReader;
import site.vackstudio.vanticheat.protocol.ProtocolWriter;
import site.vackstudio.vanticheat.protocol.VerificationProtocol;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the PreLogin -&gt; Login identity binding (Phase 8.2,
 * section 8/9). Live operation showed the PreLogin UUID is replaced before
 * LoginEvent in offline mode, and the previous username-keyed association let
 * concurrent same-username logins resolve each other's sessions. Sessions are
 * now bound to the login's TCP connection (remote IP + port); these tests
 * prove same-username, cross-connection, and reconnect-race isolation through
 * the real listener with fake Velocity connections.
 */
class IdentityBindingTest {

    static class FakePlugin extends VAntiCheatPlugin {
        final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "identity-test-scheduler");
                    t.setDaemon(true);
                    return t;
                });
        final VerificationSessionManager manager = new VerificationSessionManager(1000, 60000);
        // Long timeout so the scheduler never interferes with assertions.
        final VerificationProtocol protocol = new VerificationProtocol(1, 300000);
        final Blocklist blocklist;

        FakePlugin() {
            super(null, null, null);
            PluginConfig config = PluginConfig.defaults();
            ModRuleConfig banned = new ModRuleConfig();
            banned.enabled = true;
            banned.identifiers = List.of("vac-test-banned");
            Map<String, ModRuleConfig> rules = new HashMap<>();
            rules.put("vac-test-banned", banned);
            config.setModRules(rules);
            blocklist = new Blocklist(config);
        }

        @Override public ScheduledExecutorService getScheduler() { return scheduler; }
        @Override public VerificationSessionManager getSessionManager() { return manager; }
        @Override public VerificationProtocol getProtocol() { return protocol; }
        @Override public Blocklist getBlocklist() { return blocklist; }
        @Override public boolean isEnabled() { return true; }
    }

    static class Captured {
        byte[] bytes;
        LoginPhaseConnection.MessageConsumer consumer;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        return 0;
    }

    static LoginPhaseConnection fakeLpc(InetSocketAddress addr, Captured cap) {
        return (LoginPhaseConnection) Proxy.newProxyInstance(
                IdentityBindingTest.class.getClassLoader(),
                new Class<?>[]{LoginPhaseConnection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRemoteAddress": return addr;
                        case "getProtocolState": return ProtocolState.LOGIN;
                        case "isActive": return true;
                        case "sendLoginPluginMessage":
                            cap.bytes = (byte[]) args[1];
                            cap.consumer = (LoginPhaseConnection.MessageConsumer) args[2];
                            return null;
                        default: return defaultValue(method.getReturnType());
                    }
                });
    }

    static Player fakePlayer(String username, UUID uuid, InetSocketAddress addr) {
        return (Player) Proxy.newProxyInstance(
                IdentityBindingTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUsername": return username;
                        case "getUniqueId": return uuid;
                        case "getRemoteAddress": return addr;
                        case "getProtocolState": return ProtocolState.LOGIN;
                        case "isActive": return true;
                        default: return defaultValue(method.getReturnType());
                    }
                });
    }

    static void preLogin(LoginVerificationListener listener, LoginPhaseConnection lpc,
                         String username, UUID preUuid) {
        listener.onPreLogin(new PreLoginEvent((InboundConnection) lpc, username, preUuid));
    }

    static LoginEvent login(LoginVerificationListener listener, Player player) {
        LoginEvent event = new LoginEvent(player);
        listener.onLogin(event);
        return event;
    }

    static byte[] cleanResponse(String sessionId, String challenge) throws Exception {
        byte[] report = new ClientReport("1", "1.21", "Fabric", "Vanilla",
                sessionId, challenge).toBytes();
        return ProtocolWriter.buildResponsePacket(sessionId, challenge, report);
    }

    @Test void addrKeyIsPerConnection() {
        assertEquals("127.0.0.1:50001",
                LoginVerificationListener.addrKey(new InetSocketAddress("127.0.0.1", 50001)));
        assertNotEquals(
                LoginVerificationListener.addrKey(new InetSocketAddress("127.0.0.1", 50001)),
                LoginVerificationListener.addrKey(new InetSocketAddress("127.0.0.1", 50002)));
        assertNull(LoginVerificationListener.addrKey(null));
    }

    @Test void sameUsernameConcurrent_noCrossContamination() throws Exception {
        FakePlugin plugin = new FakePlugin();
        LoginVerificationListener listener = new LoginVerificationListener(plugin);

        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", 41001);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", 41002);
        Captured capA = new Captured();
        Captured capB = new Captured();
        // Different case on purpose: the old lower-cased name key collided here.
        preLogin(listener, fakeLpc(addrA, capA), "BindDup", UUID.randomUUID());
        preLogin(listener, fakeLpc(addrB, capB), "binddup", UUID.randomUUID());
        assertNotNull(capA.consumer, "A must receive a challenge");
        assertNotNull(capB.consumer, "B must receive a challenge");

        String sessionB = ProtocolReader.readSessionId(capB.bytes);
        String challengeB = ProtocolReader.readChallenge(capB.bytes);
        capB.consumer.onMessageResponse(cleanResponse(sessionB, challengeB));

        // Offline-mode UUID skew: LoginEvent UUIDs differ from PreLogin UUIDs,
        // and both logins derive the same offline UUID from the username.
        UUID offlineUuid = UUID.nameUUIDFromBytes("OfflinePlayer:binddup".getBytes());
        LoginEvent eventA = login(listener,
                fakePlayer("BindDup", offlineUuid, addrA));
        assertFalse(eventA.getResult().isAllowed(),
                "A must NOT be admitted on B's verification result");

        LoginEvent eventB = login(listener,
                fakePlayer("binddup", offlineUuid, addrB));
        assertTrue(eventB.getResult().isAllowed(), "B verified clean and must be allowed");
    }

    @Test void crossConnectionResponseRejected() throws Exception {
        FakePlugin plugin = new FakePlugin();
        LoginVerificationListener listener = new LoginVerificationListener(plugin);

        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", 41101);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", 41102);
        Captured capA = new Captured();
        Captured capB = new Captured();
        preLogin(listener, fakeLpc(addrA, capA), "CrossA", UUID.randomUUID());
        preLogin(listener, fakeLpc(addrB, capB), "CrossB", UUID.randomUUID());

        String sessionB = ProtocolReader.readSessionId(capB.bytes);
        String challengeB = ProtocolReader.readChallenge(capB.bytes);
        // Response B delivered on connection A's channel must kill A's session.
        capA.consumer.onMessageResponse(cleanResponse(sessionB, challengeB));

        LoginEvent eventA = login(listener,
                fakePlayer("CrossA", UUID.randomUUID(), addrA));
        assertFalse(eventA.getResult().isAllowed(), "A must be denied after a foreign response");

        // B's own session is untouched and still verifies.
        capB.consumer.onMessageResponse(cleanResponse(sessionB, challengeB));
        LoginEvent eventB = login(listener,
                fakePlayer("CrossB", UUID.randomUUID(), addrB));
        assertTrue(eventB.getResult().isAllowed(), "B must still verify on its own response");
    }

    @Test void reconnectRace_oldResponseCannotConsumeNew() throws Exception {
        FakePlugin plugin = new FakePlugin();
        LoginVerificationListener listener = new LoginVerificationListener(plugin);

        InetSocketAddress addrOld = new InetSocketAddress("127.0.0.1", 41201);
        InetSocketAddress addrNew = new InetSocketAddress("127.0.0.1", 41202);
        Captured capOld = new Captured();
        Captured capNew = new Captured();
        UUID preOld = UUID.randomUUID();
        preLogin(listener, fakeLpc(addrOld, capOld), "RaceUser", preOld);
        String oldSession = ProtocolReader.readSessionId(capOld.bytes);
        String oldChallenge = ProtocolReader.readChallenge(capOld.bytes);

        // Old connection drops before answering.
        Player oldPlayer = fakePlayer("RaceUser", UUID.randomUUID(), addrOld);
        listener.onDisconnect(new DisconnectEvent(oldPlayer,
                DisconnectEvent.LoginStatus.CANCELLED_BY_USER));
        assertNull(plugin.getSessionManager().getSession(oldSession),
                "Disconnect must invalidate the old session");

        // Same user reconnects immediately on a new socket.
        preLogin(listener, fakeLpc(addrNew, capNew), "RaceUser", UUID.randomUUID());
        String newSession = ProtocolReader.readSessionId(capNew.bytes);
        assertNotEquals(oldSession, newSession, "Reconnect must mint a fresh session");

        // Stale response for the dead session arrives late: must be ignored.
        capOld.consumer.onMessageResponse(cleanResponse(oldSession, oldChallenge));

        // New session answers correctly and is admitted on its own merit.
        String newChallenge = ProtocolReader.readChallenge(capNew.bytes);
        capNew.consumer.onMessageResponse(cleanResponse(newSession, newChallenge));
        LoginEvent eventNew = login(listener,
                fakePlayer("RaceUser", UUID.randomUUID(), addrNew));
        assertTrue(eventNew.getResult().isAllowed(), "Fresh reconnect session must verify");
    }

    @Test void onlineModeUuidMatch_sameUsernameIsolated() throws Exception {
        FakePlugin plugin = new FakePlugin();
        LoginVerificationListener listener = new LoginVerificationListener(plugin);

        // Online mode: PreLogin and Login UUIDs agree, but two in-flight
        // same-user logins still share that UUID; only the address separates them.
        UUID sharedUuid = UUID.randomUUID();
        InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", 41301);
        InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", 41302);
        Captured capA = new Captured();
        Captured capB = new Captured();
        preLogin(listener, fakeLpc(addrA, capA), "OnlineDup", sharedUuid);
        preLogin(listener, fakeLpc(addrB, capB), "OnlineDup", sharedUuid);

        String sessionA = ProtocolReader.readSessionId(capA.bytes);
        String challengeA = ProtocolReader.readChallenge(capA.bytes);
        capA.consumer.onMessageResponse(cleanResponse(sessionA, challengeA));

        LoginEvent eventA = login(listener,
                fakePlayer("OnlineDup", sharedUuid, addrA));
        assertTrue(eventA.getResult().isAllowed(), "A verified and must be allowed");

        LoginEvent eventB = login(listener,
                fakePlayer("OnlineDup", sharedUuid, addrB));
        assertFalse(eventB.getResult().isAllowed(),
                "B must NOT inherit A's verification result");
    }
}
