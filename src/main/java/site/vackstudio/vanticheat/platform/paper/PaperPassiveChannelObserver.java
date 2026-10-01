package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import site.vackstudio.vanticheat.detection.probe.ClientSignalCollector;
import site.vackstudio.vanticheat.platform.ClientPlatform;
import site.vackstudio.vanticheat.platform.ClientPlatformService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Paper-native passive client-signal observer.
 *
 * <p>This uses only the supported Bukkit/Paper plugin-messaging API. Paper's
 * {@code ServerCommonPacketListenerImpl#handleCustomPayload} dispatches every inbound
 * custom payload to {@code Messenger#dispatchIncomingMessage} after the payload has been
 * decoded as a {@code DiscardedPayload} (the fallback for channels the server has no codec
 * for), and {@code StandardMessenger#dispatchIncomingMessage} invokes every listener
 * registered for that exact channel. No NMS, no Netty, no packet rewriting, and no
 * additional dependency is involved.
 *
 * <p>This class is the only place that touches the plugin-messaging API. It contains no
 * detection logic: it observes a channel name and hands the observation to
 * {@link ClientSignalCollector}. Probes never inspect this class.
 */
public final class PaperPassiveChannelObserver {
    private final Plugin plugin;
    private final ClientSignalCollector collector;
    private final Logger logger;
    private final Map<String, String> channelToProbe;
    private final Function<UUID, ClientPlatformService.Classification> classification;
    private final Function<UUID, String> brand;
    private final Function<UUID, Long> generation;
    private volatile boolean active;

    public PaperPassiveChannelObserver(Plugin plugin, ClientSignalCollector collector, Logger logger,
                                       Map<String, String> channelToProbe,
                                       Function<UUID, ClientPlatformService.Classification> classification,
                                       Function<UUID, String> brand,
                                       Function<UUID, Long> generation) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.channelToProbe = Map.copyOf(new LinkedHashMap<>(channelToProbe));
        this.classification = Objects.requireNonNull(classification, "classification");
        this.brand = Objects.requireNonNull(brand, "brand");
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    /**
     * Registers an incoming listener per declared channel. Registration is the only server
     * interaction: it does not send anything to the client, so it cannot prompt the client
     * to do anything either.
     */
    public void register() {
        if (channelToProbe.isEmpty()) {
            collector.setState(ClientSignalCollector.State.DISABLED);
            return;
        }
        try {
            Messenger messenger = plugin.getServer().getMessenger();
            for (String channel : channelToProbe.keySet()) {
                messenger.registerIncomingPluginChannel(plugin, channel, new ChannelListener(channel));
            }
            active = true;
            collector.setState(ClientSignalCollector.State.READY);
            logger.info("passiveObserver=READY channels=" + channelToProbe.keySet()
                    + " (Paper plugin-messaging API; no UI, no packets sent, no dependency)");
        } catch (RuntimeException | LinkageError exception) {
            active = false;
            collector.setState(ClientSignalCollector.State.UNAVAILABLE);
            logger.log(Level.WARNING, "passiveObserver=UNAVAILABLE reason="
                    + exception.getClass().getSimpleName());
        }
    }

    public void unregister() {
        active = false;
        collector.setState(ClientSignalCollector.State.DISABLED);
        try {
            Messenger messenger = plugin.getServer().getMessenger();
            for (String channel : channelToProbe.keySet()) {
                messenger.unregisterIncomingPluginChannel(plugin, channel);
            }
        } catch (RuntimeException exception) {
            logger.log(Level.FINE, "passive observer unregister failed reason="
                    + exception.getClass().getSimpleName());
        }
    }

    public boolean active() { return active; }

    public java.util.Set<String> channels() { return channelToProbe.keySet(); }

    /** Test seam: routes an observation exactly as the API callback would. */
    public void deliver(UUID playerId, String channel, int bytes) {
        String probeId = channelToProbe.get(channel);
        if (probeId == null) return;
        ClientPlatformService.Classification resolved = classification.apply(playerId);
        // A payload is never treated as evidence unless the platform is authoritatively
        // probe-eligible. This is a defence in depth behind the P17 classification gate.
        if (resolved == null || !resolved.canProbe()) {
            collector.observe(playerId, generation.apply(playerId), channel, probeId, bytes,
                    resolved == null ? ClientPlatform.UNKNOWN : resolved.platform(), null);
            return;
        }
        collector.observe(playerId, generation.apply(playerId), channel, probeId, bytes,
                resolved.platform(), brand.apply(playerId));
    }

    private final class ChannelListener implements PluginMessageListener {
        private final String channel;

        private ChannelListener(String channel) {
            this.channel = channel;
        }

        @Override
        public void onPluginMessageReceived(String receivedChannel, Player player, byte[] message) {
            if (!active || player == null) return;
            if (!channel.equals(receivedChannel)) return;
            int bytes = message == null ? 0 : message.length;
            try {
                deliver(player.getUniqueId(), receivedChannel, bytes);
            } catch (RuntimeException exception) {
                collector.setState(ClientSignalCollector.State.ERROR);
                logger.log(Level.FINE, "passive signal handling failed channel=" + receivedChannel
                        + " reason=" + exception.getClass().getSimpleName());
            }
        }
    }
}
