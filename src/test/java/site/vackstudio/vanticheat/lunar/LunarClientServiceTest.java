package site.vackstudio.vanticheat.lunar;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lunar policy is diagnostic + Apollo override only: Lunar players are never
 * kicked and never classified as cheats by this service.
 */
class LunarClientServiceTest {
    private final UUID lunarPlayer = UUID.randomUUID();
    private final UUID vanillaPlayer = UUID.randomUUID();

    @Test
    void lunarPlayerGetsMinimapDisabledAndStaysConnected() {
        FakeIntegration integration = new FakeIntegration(Set.of(lunarPlayer));
        LunarClientService service = service(new LunarPolicyConfig(true, true), integration);

        assertEquals(LunarClientService.RegistrationOutcome.POLICY_APPLIED,
                service.handleRegistration(lunarPlayer, "lunar"));
        assertTrue(integration.disabled.contains(lunarPlayer));
        assertEquals(LunarPlayerState.MINIMAP_DISABLED,
                service.snapshot(lunarPlayer).state());
        assertTrue(service.isLunar(lunarPlayer));
    }

    @Test
    void nonLunarPlayerTriggersNoApolloAction() {
        FakeIntegration integration = new FakeIntegration(Set.of(lunarPlayer));
        LunarClientService service = service(new LunarPolicyConfig(true, true), integration);

        assertEquals(LunarClientService.RegistrationOutcome.IGNORED_UNSUPPORTED,
                service.handleRegistration(vanillaPlayer, "vanilla"));
        assertTrue(integration.disabled.isEmpty());
        assertEquals(0, service.trackedPlayers());
        assertFalse(service.isLunar(vanillaPlayer));
    }

    @Test
    void unavailableIntegrationRecordsNothing() {
        LunarClientService service = service(new LunarPolicyConfig(true, true), null);

        assertEquals(LunarClientService.RegistrationOutcome.IGNORED_UNAVAILABLE,
                service.handleRegistration(lunarPlayer, "lunar"));
        assertEquals(0, service.trackedPlayers());
        assertFalse(service.isLunar(lunarPlayer));
    }

    @Test
    void disabledConfigIgnoresRegistrations() {
        FakeIntegration integration = new FakeIntegration(Set.of(lunarPlayer));
        LunarClientService service = service(new LunarPolicyConfig(false, false), integration);

        assertEquals(LunarClientService.RegistrationOutcome.IGNORED_DISABLED,
                service.handleRegistration(lunarPlayer, "lunar"));
        assertTrue(integration.disabled.isEmpty());
    }

    @Test
    void minimapPolicyOffRegistersWithoutOverride() {
        FakeIntegration integration = new FakeIntegration(Set.of(lunarPlayer));
        LunarClientService service = service(new LunarPolicyConfig(true, false), integration);

        assertEquals(LunarClientService.RegistrationOutcome.REGISTERED,
                service.handleRegistration(lunarPlayer, "lunar"));
        assertTrue(integration.disabled.isEmpty());
        assertEquals(LunarPlayerState.REGISTERED, service.snapshot(lunarPlayer).state());
    }

    @Test
    void disconnectCleanupAllowsReconnectToReapply() {
        FakeIntegration integration = new FakeIntegration(Set.of(lunarPlayer));
        LunarClientService service = service(new LunarPolicyConfig(true, true), integration);

        service.handleRegistration(lunarPlayer, "lunar");
        service.handleQuit(lunarPlayer);
        assertEquals(0, service.trackedPlayers());
        assertEquals(LunarPlayerState.UNKNOWN, service.snapshot(lunarPlayer).state());

        assertEquals(LunarClientService.RegistrationOutcome.POLICY_APPLIED,
                service.handleRegistration(lunarPlayer, "lunar"));
        assertEquals(LunarPlayerState.MINIMAP_DISABLED, service.snapshot(lunarPlayer).state());
    }

    @Test
    void stopClearsStateAndDisconnectsIntegration() {
        FakeIntegration integration = new FakeIntegration(Set.of(lunarPlayer));
        LunarClientService service = service(new LunarPolicyConfig(true, true), integration);

        service.handleRegistration(lunarPlayer, "lunar");
        service.stop();

        assertEquals(0, service.trackedPlayers());
        assertTrue(integration.stopped);
        assertFalse(service.isLunar(lunarPlayer));
    }

    private static LunarClientService service(LunarPolicyConfig config, FakeIntegration integration) {
        LunarClientService service = new LunarClientService(Logger.getAnonymousLogger());
        service.start(config, integration);
        return service;
    }

    private static final class FakeIntegration implements LunarClientIntegration {
        private final Set<UUID> lunar;
        private final Set<UUID> disabled = new HashSet<>();
        private boolean stopped;

        private FakeIntegration(Set<UUID> lunar) {
            this.lunar = lunar;
        }

        @Override public boolean isAvailable() { return true; }
        @Override public boolean hasSupport(UUID playerId) { return lunar.contains(playerId); }

        @Override public boolean disableMinimap(UUID playerId) {
            if (!lunar.contains(playerId)) return false;
            disabled.add(playerId);
            return true;
        }

        @Override public Optional<Boolean> minimapStatus(UUID playerId) {
            return lunar.contains(playerId) ? Optional.of(!disabled.contains(playerId)) : Optional.empty();
        }

        @Override public void start() { }
        @Override public void stop() { stopped = true; }
    }
}
