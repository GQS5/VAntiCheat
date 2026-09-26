package site.vackstudio.vanticheat.platform.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import site.vackstudio.vanticheat.platform.EntityTarget;
import site.vackstudio.vanticheat.platform.RegionTarget;
import site.vackstudio.vanticheat.platform.Scheduler;
import site.vackstudio.vanticheat.platform.TaskHandle;
import site.vackstudio.vanticheat.detection.probe.ClientProbeTransport;
import site.vackstudio.vanticheat.detection.probe.CheckHacksResponseEvaluator;
import site.vackstudio.vanticheat.detection.probe.ProbeDefinition;
import site.vackstudio.vanticheat.detection.probe.ProbeHandle;
import site.vackstudio.vanticheat.detection.probe.ProbeRequest;
import site.vackstudio.vanticheat.detection.probe.ProbeResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Paper API transport. NMS packet shortcuts can be added behind this boundary later. */
public final class PaperSignProbeTransport implements ClientProbeTransport, Listener {
    private final Plugin plugin;
    private final Scheduler scheduler;
    private final long timeoutTicks;
    private final boolean debug;
    private final Map<UUID, Operation> operations = new ConcurrentHashMap<>();

    public PaperSignProbeTransport(Plugin plugin, Scheduler scheduler) {
        this(plugin, scheduler, 200);
    }

    public PaperSignProbeTransport(Plugin plugin, Scheduler scheduler, long timeoutTicks) {
        this(plugin, scheduler, timeoutTicks, false);
    }

    public PaperSignProbeTransport(Plugin plugin, Scheduler scheduler, long timeoutTicks, boolean debug) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timeoutTicks = Math.max(1L, timeoutTicks);
        this.debug = debug;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public ProbeHandle send(ProbeRequest request, Consumer<ProbeResponse> response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        if (!(request.target().platformHandle() instanceof Player player)) {
            response.accept(ProbeResponse.error(request));
            return new EmptyHandle();
        }
        if (!player.isOnline()) {
            response.accept(ProbeResponse.disconnected(request));
            return new EmptyHandle();
        }
        Operation operation = new Operation(request, player, response);
        operation.cancelAction = () -> cancel(operation);
        if (operations.putIfAbsent(player.getUniqueId(), operation) != null) {
            response.accept(ProbeResponse.error(request));
            return new EmptyHandle();
        }
        debug("send session=" + request.sessionId() + " player=" + player.getName()
                + " probes=" + request.probes().size());
        scheduler.runAtEntity(new EntityTarget(player), () -> placeNext(operation, 0));
        return operation;
    }

    private void placeNext(Operation operation, int index) {
        if (!active(operation)) return;
        if (index >= candidates(operation.player).size()) {
            finish(operation, ProbeResponse.error(operation.request));
            return;
        }
        Location location = candidates(operation.player).get(index);
        scheduler.runAtLocation(regionTarget(location), () -> {
            if (!active(operation)) return;
            Block block = location.getBlock();
            if (!block.getType().isAir()) {
                placeNext(operation, index + 1);
                return;
            }
            operation.location = location;
            operation.original = block.getState();
            Location below = location.clone().subtract(0, 1, 0);
            operation.barrierLocation = below;
            operation.barrierPlaced = below.getBlock().getType().isAir();
            if (operation.barrierPlaced) below.getBlock().setType(Material.BARRIER, false);
            block.setType(Material.OAK_SIGN, false);
            Sign sign = (Sign) block.getState();
            for (int i = 0; i < operation.request.probes().size() && i < 3; i++) {
                ProbeDefinition probe = operation.request.probes().get(i);
                sign.getSide(Side.FRONT).line(i, component(probe));
            }
            sign.getSide(Side.FRONT).line(3, Component.keybind(CheckHacksResponseEvaluator.EXPLOIT_PREVENTER_KEY));
            sign.setAllowedEditorUniqueId(operation.player.getUniqueId());
            sign.update(true, false);
            operation.sign = sign;
            debug("placed session=" + operation.request.sessionId() + " location=" + location);
            scheduler.runAtEntity(new EntityTarget(operation.player), () -> {
                if (!active(operation)) return;
                if (!NmsSignPackets.sendBlockEntityPacket(operation.player, operation.location, plugin)) {
                    finish(operation, ProbeResponse.error(operation.request));
                    return;
                }
                debug("sent block-entity session=" + operation.request.sessionId());
                scheduler.runGlobalLater(() -> scheduler.runAtEntity(new EntityTarget(operation.player), () -> {
                    if (!active(operation)) return;
                    if (!NmsSignPackets.sendOpenSignPacket(operation.player, operation.location, plugin)) {
                        finish(operation, ProbeResponse.error(operation.request));
                        return;
                    }
                    debug("sent open-editor session=" + operation.request.sessionId());
                    operation.player.sendBlockChange(operation.location, Material.AIR.createBlockData());
                }), 1);
            });
            operation.timeout = scheduler.runGlobalLater(
                    () -> finish(operation, ProbeResponse.timeout(operation.request)), timeoutTicks);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Operation operation = operations.get(event.getPlayer().getUniqueId());
        if (operation == null || operation.sign == null || !event.getBlock().equals(operation.sign.getBlock())) return;
        event.setCancelled(true);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Component line = event.line(i);
            lines.add(line == null ? "" : PlainTextComponentSerializer.plainText().serialize(line));
        }
        finish(operation, new ProbeResponse(operation.request.sessionId(), operation.player.getUniqueId(),
                lines, ProbeResponse.Outcome.RESPONSE));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Operation operation = operations.get(event.getPlayer().getUniqueId());
        if (operation != null) finish(operation, ProbeResponse.disconnected(operation.request));
    }

    private void finish(Operation operation, ProbeResponse response) {
        if (!operations.remove(operation.player.getUniqueId(), operation)) return;
        debug("finish session=" + operation.request.sessionId() + " outcome=" + response.outcome());
        if (operation.timeout != null) operation.timeout.cancel();
        if (operation.location == null) {
            operation.response.accept(response);
            return;
        }
        Location location = operation.location;
        scheduler.runAtLocation(regionTarget(location), () -> {
            Block block = location.getBlock();
            try {
                if (operation.original != null) operation.original.update(true, false);
                else if (block.getType() == Material.OAK_SIGN) block.setType(Material.AIR, false);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Client probe sign restoration failed: " + exception.getMessage());
            } finally {
                if (operation.barrierPlaced && operation.barrierLocation != null) {
                    operation.barrierLocation.getBlock().setType(Material.AIR, false);
                }
            }
            operation.response.accept(response);
        });
    }

    private void cancel(Operation operation) {
        if (!operations.remove(operation.player.getUniqueId(), operation)) return;
        operation.cancelled = true;
        if (operation.timeout != null) operation.timeout.cancel();
        restore(operation);
    }

    private void restore(Operation operation) {
        if (operation.location == null) return;
        Location location = operation.location;
        scheduler.runAtLocation(regionTarget(location), () -> {
            Block block = location.getBlock();
            try {
                if (operation.original != null) operation.original.update(true, false);
                else if (block.getType() == Material.OAK_SIGN) block.setType(Material.AIR, false);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Client probe sign restoration failed: " + exception.getMessage());
            } finally {
                if (operation.barrierPlaced && operation.barrierLocation != null) {
                    operation.barrierLocation.getBlock().setType(Material.AIR, false);
                }
            }
        });
    }

    private static Component component(ProbeDefinition probe) {
        return switch (probe.mode()) {
            case METEOR, TRANSLATE -> Component.translatable(probe.key(), probe.fallback());
            case KEYBIND -> Component.keybind(probe.key());
        };
    }

    private static RegionTarget regionTarget(Location location) {
        return new RegionTarget(location.getWorld(), chunkCoordinate(location.getBlockX()),
                chunkCoordinate(location.getBlockZ()));
    }

    static int chunkCoordinate(int blockCoordinate) {
        return blockCoordinate >> 4;
    }

    private boolean active(Operation operation) {
        return operations.get(operation.player.getUniqueId()) == operation
                && !operation.cancelled && operation.player.isOnline();
    }

    private void debug(String message) {
        if (debug) plugin.getLogger().info("[ClientProbe] " + message);
    }

    private static List<Location> candidates(Player player) {
        return candidates(player.getLocation().getBlock().getLocation());
    }

    static List<Location> candidates(Location base) {
        List<Location> result = new ArrayList<>();
        // Ground-level players need an air block above their feet; y - 1 is
        // normally solid terrain and made every vanilla join fail placement.
        result.add(base.clone().add(0, 3, 0));
        result.add(base.clone().add(0, 1, 2));
        result.add(base.clone().add(0, 1, -2));
        result.add(base.clone().add(2, 1, 0));
        result.add(base.clone().add(-2, 1, 0));
        result.add(base.clone().add(0, 2, 2));
        result.add(base.clone().add(0, 2, -2));
        result.add(base.clone().add(2, 2, 0));
        result.add(base.clone().add(-2, 2, 0));
        return result;
    }

    @Override
    public void stop() {
        for (Operation operation : List.copyOf(operations.values())) {
            finish(operation, ProbeResponse.disconnected(operation.request));
        }
    }

    private static final class Operation implements ProbeHandle {
        private final ProbeRequest request;
        private final Player player;
        private final Consumer<ProbeResponse> response;
        private volatile Location location;
        private volatile BlockState original;
        private volatile Sign sign;
        private volatile TaskHandle timeout;
        private volatile Runnable cancelAction;
        private volatile Location barrierLocation;
        private volatile boolean barrierPlaced;
        private volatile boolean cancelled;

        private Operation(ProbeRequest request, Player player, Consumer<ProbeResponse> response) {
            this.request = request;
            this.player = player;
            this.response = response;
        }

        @Override public void cancel() { if (cancelAction != null) cancelAction.run(); else cancelled = true; }
        @Override public boolean cancelled() { return cancelled; }
    }

    private static final class EmptyHandle implements ProbeHandle {
        @Override public void cancel() { }
        @Override public boolean cancelled() { return false; }
    }
}
