package site.vackstudio.vanticheat.platform.lunar;

import com.lunarclient.apollo.Apollo;
import com.lunarclient.apollo.event.EventBus;
import com.lunarclient.apollo.event.player.ApolloRegisterPlayerEvent;
import com.lunarclient.apollo.event.player.ApolloUnregisterPlayerEvent;
import com.lunarclient.apollo.module.modsetting.ModSettingModule;
import com.lunarclient.apollo.mods.impl.ModMinimap;
import com.lunarclient.apollo.player.ApolloPlayer;
import site.vackstudio.vanticheat.lunar.LunarClientIntegration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Official Apollo API bridge for the Lunar Minimap policy.
 *
 * <p>Loaded only when the Apollo plugin is installed and functional (see
 * {@link #create}). All Apollo callbacks operate on UUIDs and Apollo data
 * only — no Bukkit player, location, or entity objects cross this boundary,
 * so no {@code BukkitApollo}/{@code FoliaApollo} helpers are required and the
 * handler is safe on both Paper and Folia. Apollo manages its own packet
 * threading; this bridge performs no world or region access.
 */
public final class ApolloLunarBridge implements LunarClientIntegration {
    private final Logger logger;
    private final BiConsumer<UUID, String> onRegister;
    private final Consumer<UUID> onUnregister;
    private volatile Consumer<ApolloRegisterPlayerEvent> registerHandler;
    private volatile Consumer<ApolloUnregisterPlayerEvent> unregisterHandler;
    private volatile boolean started;

    private ApolloLunarBridge(Logger logger, BiConsumer<UUID, String> onRegister,
                              Consumer<UUID> onUnregister) {
        this.logger = logger;
        this.onRegister = onRegister;
        this.onUnregister = onUnregister;
    }

    /**
     * Creates the bridge only when the Apollo API is present and its platform
     * is initialized. Never throws for a missing Apollo installation.
     */
    public static Optional<LunarClientIntegration> create(Logger logger,
            BiConsumer<UUID, String> onRegister, Consumer<UUID> onUnregister) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(onRegister, "onRegister");
        Objects.requireNonNull(onUnregister, "onUnregister");
        try {
            Class.forName("com.lunarclient.apollo.Apollo");
            if (Apollo.getPlayerManager() == null) return Optional.empty();
            return Optional.of(new ApolloLunarBridge(logger, onRegister, onUnregister));
        } catch (LinkageError | RuntimeException | ClassNotFoundException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Apollo API unavailable", exception);
            return Optional.empty();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return Apollo.getPlayerManager() != null;
        } catch (LinkageError | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public void start() {
        if (started) return;
        started = true;
        registerHandler = this::onApolloRegister;
        unregisterHandler = this::onApolloUnregister;
        EventBus.getBus().register(ApolloRegisterPlayerEvent.class, registerHandler);
        EventBus.getBus().register(ApolloUnregisterPlayerEvent.class, unregisterHandler);
    }

    @Override
    public void stop() {
        started = false;
        try {
            if (registerHandler != null) {
                EventBus.getBus().unregister(ApolloRegisterPlayerEvent.class, registerHandler);
            }
            if (unregisterHandler != null) {
                EventBus.getBus().unregister(ApolloUnregisterPlayerEvent.class, unregisterHandler);
            }
        } catch (LinkageError | RuntimeException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Apollo listener unregister failed", exception);
        } finally {
            registerHandler = null;
            unregisterHandler = null;
        }
    }

    @Override
    public boolean hasSupport(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            return Apollo.getPlayerManager().hasSupport(playerId);
        } catch (LinkageError | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public boolean disableMinimap(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            Optional<ApolloPlayer> player = Apollo.getPlayerManager().getPlayer(playerId);
            if (player.isEmpty()) return false;
            Apollo.getModuleManager().getModule(ModSettingModule.class)
                    .getOptions().set(player.get(), ModMinimap.ENABLED, false);
            return true;
        } catch (LinkageError | RuntimeException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Apollo minimap override failed", exception);
            return false;
        }
    }

    @Override
    public Optional<Boolean> minimapStatus(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            Optional<ApolloPlayer> player = Apollo.getPlayerManager().getPlayer(playerId);
            if (player.isEmpty()) return Optional.empty();
            return Optional.of(Apollo.getModuleManager().getModule(ModSettingModule.class)
                    .getStatus(player.get(), ModMinimap.ENABLED));
        } catch (LinkageError | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void onApolloRegister(ApolloRegisterPlayerEvent event) {
        try {
            ApolloPlayer player = event.getPlayer();
            onRegister.accept(player.getUniqueId(), player.getName());
        } catch (LinkageError | RuntimeException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Apollo register handling failed", exception);
        }
    }

    private void onApolloUnregister(ApolloUnregisterPlayerEvent event) {
        try {
            onUnregister.accept(event.getPlayer().getUniqueId());
        } catch (LinkageError | RuntimeException exception) {
            logger.log(Level.FINE, "[VAntiCheat] Apollo unregister handling failed", exception);
        }
    }
}
