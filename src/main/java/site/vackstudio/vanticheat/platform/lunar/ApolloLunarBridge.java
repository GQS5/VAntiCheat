package site.vackstudio.vanticheat.platform.lunar;

import com.lunarclient.apollo.Apollo;
import com.lunarclient.apollo.event.EventBus;
import com.lunarclient.apollo.event.player.ApolloRegisterPlayerEvent;
import com.lunarclient.apollo.event.player.ApolloUnregisterPlayerEvent;
import com.lunarclient.apollo.module.modsetting.ModSettingModule;
import com.lunarclient.apollo.mods.impl.ModMinimap;
import com.lunarclient.apollo.player.ApolloPlayer;
import com.lunarclient.apollo.player.ApolloPlayerManager;
import com.lunarclient.apollo.option.Options;
import site.vackstudio.vanticheat.lunar.LunarClientIntegration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Official Apollo API bridge for the Lunar Minimap policy.
 *
 * <p>Loaded only when the optional Apollo API is present. Registration events
 * provide an opaque platform entity handle; the service routes player-specific
 * Apollo reads and mutations through that entity's Paper/Folia scheduler.
 */
public final class ApolloLunarBridge implements LunarClientIntegration {
    private final Logger logger;
    private final Consumer<LunarClientIntegration.Registration> onRegister;
    private final Consumer<LunarClientIntegration.Registration> onUnregister;
    private volatile Consumer<ApolloRegisterPlayerEvent> registerHandler;
    private volatile Consumer<ApolloUnregisterPlayerEvent> unregisterHandler;
    private volatile boolean started;
    private volatile boolean failed;
    private volatile ApolloPlayerManager playerManager;
    private volatile ModSettingModule modSettingModule;
    private volatile Options settingOptions;

    private ApolloLunarBridge(Logger logger,
                              Consumer<LunarClientIntegration.Registration> onRegister,
                              Consumer<LunarClientIntegration.Registration> onUnregister) {
        this.logger = logger;
        this.onRegister = onRegister;
        this.onUnregister = onUnregister;
    }

    /**
     * Creates the bridge when the optional API classes are present. Apollo's
     * runtime may still be initializing; that is reported separately as NOT_READY.
     */
    public static Optional<LunarClientIntegration> create(Logger logger,
            Consumer<LunarClientIntegration.Registration> onRegister,
            Consumer<LunarClientIntegration.Registration> onUnregister) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(onRegister, "onRegister");
        Objects.requireNonNull(onUnregister, "onUnregister");
        try {
            Class.forName("com.lunarclient.apollo.Apollo", false,
                    ApolloLunarBridge.class.getClassLoader());
            return Optional.of(new ApolloLunarBridge(logger, onRegister, onUnregister));
        } catch (LinkageError | RuntimeException | ClassNotFoundException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Apollo API unavailable", exception);
            return Optional.empty();
        }
    }

    @Override
    public Availability availability() {
        if (!started) return Availability.NOT_READY;
        if (failed) return Availability.FAILED;
        if (playerManager != null && modSettingModule != null) return Availability.AVAILABLE;
        try {
            ApolloPlayerManager players = Apollo.getPlayerManager();
            var modules = Apollo.getModuleManager();
            if (!modules.isEnabled(ModSettingModule.class)) return Availability.NOT_READY;
            ModSettingModule settings = modules.getModule(ModSettingModule.class);
            Options options = settings == null ? null : settings.getOptions();
            if (settings == null || !settings.isEnabled() || options == null) {
                return Availability.NOT_READY;
            }
            playerManager = players;
            modSettingModule = settings;
            settingOptions = options;
            return Availability.AVAILABLE;
        } catch (UnsupportedOperationException notReady) {
            return Availability.NOT_READY;
        } catch (LinkageError | RuntimeException exception) {
            failed = true;
            logger.log(Level.FINE, "[VAntiCheat] Apollo readiness check failed", exception);
            return Availability.FAILED;
        }
    }

    @Override
    public void start() {
        if (started || failed) return;
        started = true;
        registerHandler = this::onApolloRegister;
        unregisterHandler = this::onApolloUnregister;
        try {
            EventBus.getBus().register(ApolloRegisterPlayerEvent.class, registerHandler);
            EventBus.getBus().register(ApolloUnregisterPlayerEvent.class, unregisterHandler);
        } catch (LinkageError | RuntimeException exception) {
            failed = true;
            started = false;
            logger.log(Level.FINE, "[VAntiCheat] Apollo event registration failed", exception);
        }
    }

    @Override
    public void stop() {
        started = false;
        if (registerHandler != null) {
            try {
                EventBus.getBus().unregister(ApolloRegisterPlayerEvent.class, registerHandler);
            } catch (LinkageError | RuntimeException exception) {
                logger.log(Level.FINE, "[VAntiCheat] Apollo register listener unregister failed", exception);
            }
        }
        if (unregisterHandler != null) {
            try {
                EventBus.getBus().unregister(ApolloUnregisterPlayerEvent.class, unregisterHandler);
            } catch (LinkageError | RuntimeException exception) {
                logger.log(Level.FINE, "[VAntiCheat] Apollo unregister listener unregister failed", exception);
            }
        }
        registerHandler = null;
        unregisterHandler = null;
        playerManager = null;
        modSettingModule = null;
        settingOptions = null;
        failed = false;
    }

    @Override
    public boolean hasSupport(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            return requirePlayerManager().hasSupport(playerId);
        } catch (LinkageError | RuntimeException exception) {
            fail(exception);
            throw new IllegalStateException("Apollo support lookup failed", exception);
        }
    }

    @Override
    public MinimapAction disableMinimap(UUID playerId, Object playerHandle) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerHandle, "playerHandle");
        try {
            Optional<ApolloPlayer> player = requirePlayerManager().getPlayer(playerId);
            if (player.isEmpty() || player.get().getPlayer() != playerHandle) return MinimapAction.FAILED;
            ModSettingModule settings = requireModSettingModule();
            Boolean enabled = settings.getStatus(player.get(), ModMinimap.ENABLED);
            if (enabled == null) return MinimapAction.FAILED;
            if (!enabled) {
                return MinimapAction.ALREADY_APPLIED;
            }
            settingOptions.set(player.get(), ModMinimap.ENABLED, false);
            return MinimapAction.APPLIED;
        } catch (LinkageError | RuntimeException exception) {
            fail(exception);
            return MinimapAction.FAILED;
        }
    }

    @Override
    public Optional<Boolean> minimapStatus(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            Optional<ApolloPlayer> player = requirePlayerManager().getPlayer(playerId);
            if (player.isEmpty()) return Optional.empty();
            return Optional.of(requireModSettingModule().getStatus(player.get(), ModMinimap.ENABLED));
        } catch (LinkageError | RuntimeException exception) {
            fail(exception);
            return Optional.empty();
        }
    }

    private void onApolloRegister(ApolloRegisterPlayerEvent event) {
        try {
            ApolloPlayer player = event.getPlayer();
            onRegister.accept(registration(player));
        } catch (LinkageError | RuntimeException exception) {
            fail(exception);
        }
    }

    private void onApolloUnregister(ApolloUnregisterPlayerEvent event) {
        try {
            onUnregister.accept(registration(event.getPlayer()));
        } catch (LinkageError | RuntimeException exception) {
            fail(exception);
        }
    }

    private LunarClientIntegration.Registration registration(ApolloPlayer player) {
        return new LunarClientIntegration.Registration(
                player.getUniqueId(), player.getName(), player.getPlayer());
    }

    private ApolloPlayerManager requirePlayerManager() {
        if (availability() != Availability.AVAILABLE) {
            throw new IllegalStateException("Apollo is not ready");
        }
        return playerManager;
    }

    private ModSettingModule requireModSettingModule() {
        if (availability() != Availability.AVAILABLE) {
            throw new IllegalStateException("Apollo is not ready");
        }
        return modSettingModule;
    }

    private void fail(Throwable exception) {
        failed = true;
        playerManager = null;
        modSettingModule = null;
        settingOptions = null;
        logger.log(Level.FINE, "[VAntiCheat] Apollo operation failed", exception);
    }
}
