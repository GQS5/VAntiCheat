package site.vackstudio.vanticheat.platform.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
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
import site.vackstudio.vanticheat.detection.probe.ProbeHandle;
import site.vackstudio.vanticheat.detection.probe.ProbeRequest;
import site.vackstudio.vanticheat.detection.probe.ProbeResponse;
import site.vackstudio.vanticheat.detection.probe.ProbeTimeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Consumer;

/** Paper API transport. NMS packet shortcuts can be added behind this boundary later. */
public final class PaperSignProbeTransport implements ClientProbeTransport, Listener {
    private static final int CANDIDATE_COUNT = 9;
    private final Plugin plugin;
    private final Scheduler scheduler;
    private final long timeoutTicks;
    private final boolean debug;
    private final ProbeTimeline timeline;
    private final SignProbeWorldAccess world;
    private final SignProbePacketAccess packets;
    private volatile Predicate<UUID> probeEligibility = ignored -> true;
    private final Map<UUID, Operation> operations = new ConcurrentHashMap<>();

    public PaperSignProbeTransport(Plugin plugin, Scheduler scheduler) {
        this(plugin, scheduler, 200);
    }

    public PaperSignProbeTransport(Plugin plugin, Scheduler scheduler, long timeoutTicks) {
        this(plugin, scheduler, timeoutTicks, false);
    }

    public PaperSignProbeTransport(Plugin plugin, Scheduler scheduler, long timeoutTicks, boolean debug) {
        this(plugin, scheduler, timeoutTicks, debug, SignProbeWorldAccess.PAPER, SignProbePacketAccess.PAPER);
    }

    PaperSignProbeTransport(Plugin plugin, Scheduler scheduler, long timeoutTicks, boolean debug,
                            SignProbeWorldAccess world, SignProbePacketAccess packets) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timeoutTicks = Math.max(1L, timeoutTicks);
        this.debug = debug;
        this.world = Objects.requireNonNull(world, "world");
        this.packets = Objects.requireNonNull(packets, "packets");
        this.timeline = new ProbeTimeline(plugin.getLogger(), debug);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void setProbeEligibility(Predicate<UUID> probeEligibility) {
        this.probeEligibility = Objects.requireNonNull(probeEligibility, "probeEligibility");
    }

    @Override
    public ProbeHandle send(ProbeRequest request, Consumer<ProbeResponse> response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        boolean eligible;
        try {
            eligible = probeEligibility.test(request.target().id());
        } catch (RuntimeException exception) {
            response.accept(ProbeResponse.error(request));
            return new EmptyHandle();
        }
        if (!eligible) {
            response.accept(ProbeResponse.skipped(request));
            return new EmptyHandle();
        }
        if (!(request.target().platformHandle() instanceof Player player)) {
            response.accept(ProbeResponse.error(request));
            return new EmptyHandle();
        }
        Operation operation = new Operation(request, player, response);
        operation.cancelAction = () -> cancel(operation);
        if (operations.putIfAbsent(request.target().id(), operation) != null) {
            response.accept(ProbeResponse.error(request));
            return new EmptyHandle();
        }
        operation.startedNanos = timeline.start();
        timeline.event("TRANSPORT_OPERATION_START", operation.startedNanos, request, null);
        scheduleAtEntity(operation, () -> {
            if (!activeAtEntity(operation)) return;
            debug("send session=" + request.sessionId() + " player=" + request.target().name()
                    + " probes=" + request.probes().size());
            placeNext(operation, 0);
        });
        return operation;
    }

    private void placeNext(Operation operation, int index) {
        if (!eligible(operation)) return;
        if (!active(operation)) return;
        if (operation.candidateBase == null) {
            timeline.event("CANDIDATE_SELECTION_START", operation.startedNanos, operation.request, null);
            operation.candidateBase = world.playerBlockLocation(operation.player);
            timeline.event("CANDIDATE_SELECTION_END", operation.startedNanos, operation.request,
                    "candidateCount=" + CANDIDATE_COUNT + " strategy=first-candidate-fast-path");
        }
        if (index >= CANDIDATE_COUNT) {
            finish(operation, ProbeResponse.error(operation.request));
            return;
        }
        Location location = candidate(operation, index);
        // Capture the immutable cleanup target now, while this candidate's region context is
        // active and its world is provably loaded. Paper's Location holds its world through a
        // weak reference and getWorld() throws once it is cleared, so the target must never be
        // reconstructed from a Location after the operation terminalizes.
        RegionTarget cleanupTarget = regionTarget(location);
        scheduleAtLocation(operation, cleanupTarget, () -> {
            if (!eligible(operation)) return;
            if (!active(operation)) return;
            timeline.event("REGION_PLACEMENT_START", operation.startedNanos, operation.request,
                    "candidate=" + index + " location=" + location.getBlockX() + ","
                            + location.getBlockY() + "," + location.getBlockZ());
            if (!world.isAir(location)) {
                placeNext(operation, index + 1);
                return;
            }
            operation.location = location;
            operation.cleanupTarget = cleanupTarget;
            operation.original = Objects.requireNonNull(world.capture(location), "captured block state");
            operation.temporaryStateStarted = true;
            Location below = location.clone().subtract(0, 1, 0);
            operation.barrierLocation = below;
            operation.barrierPlaced = world.isAir(below);
            if (operation.barrierPlaced) world.setType(below, Material.BARRIER);
            world.setType(location, Material.OAK_SIGN);
            Sign sign = world.configureSign(location, operation.request.probes(), operation.playerId);
            operation.sign = sign;
            operation.packetOrder.signUpdated();
            timeline.event("SIGN_UPDATED", operation.startedNanos, operation.request, null);
            debug("placed session=" + operation.request.sessionId() + " location=" + location);
            timeline.event("SIGN_PLACED", operation.startedNanos, operation.request, null);
            operation.blockEntityPacket = packets.createBlockEntityPacket(operation.location, plugin);
            if (operation.blockEntityPacket == null) {
                finish(operation, ProbeResponse.error(operation.request));
                return;
            }
            timeline.event("BLOCK_ENTITY_PREPARED", operation.startedNanos, operation.request, null);
            scheduleAtEntity(operation, () -> {
                if (!activeAtEntity(operation)) return;
                if (!operation.packetOrder.canSendBlockEntity()
                        || !packets.sendBlockEntity(operation.player, operation.blockEntityPacket, plugin)) {
                    finish(operation, ProbeResponse.error(operation.request));
                    return;
                }
                operation.packetOrder.blockEntitySent();
                debug("sent block-entity session=" + operation.request.sessionId());
                timeline.event("BLOCK_ENTITY_SENT", operation.startedNanos, operation.request, null);
                scheduleGlobalLater(operation, () -> scheduleAtEntity(operation, () -> {
                    if (!activeAtEntity(operation)) return;
                    if (!operation.packetOrder.canOpenEditor()) {
                        finish(operation, ProbeResponse.error(operation.request));
                        return;
                    }
                    if (!packets.openEditor(operation.player, operation.location, plugin)) {
                        finish(operation, ProbeResponse.error(operation.request));
                        return;
                    }
                    operation.packetOrder.openEditorSent();
                    debug("sent open-editor session=" + operation.request.sessionId());
                    timeline.event("OPEN_SIGN_SENT", operation.startedNanos, operation.request, null);
                    packets.hideTemporarySign(operation.player, operation.location);
                }), 1);
            });
            TaskHandle timeout = scheduleGlobalLater(operation,
                    () -> finish(operation, ProbeResponse.timeout(operation.request)),
                    operation.request.timeoutTicks() > 0 ? operation.request.timeoutTicks() : timeoutTicks);
            operation.timeout = timeout;
            if (timeout != null && operation.terminal.get()) timeout.cancel();
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Operation operation = operations.get(event.getPlayer().getUniqueId());
        if (operation == null || operation.player != event.getPlayer() || operation.sign == null
                || !event.getBlock().equals(operation.sign.getBlock())) return;
        event.setCancelled(true);
        List<String> lines = new ArrayList<>();
        List<String> identities = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Component line = event.line(i);
            lines.add(line == null ? "" : PlainTextComponentSerializer.plainText().serialize(line));
            identities.add(componentIdentity(line));
        }
        acceptResponse(new ProbeResponse(operation.request.sessionId(), operation.player.getUniqueId(),
                lines, ProbeResponse.Outcome.RESPONSE, identities));
    }

    /** Accepts a response only for the currently owned transport operation identity. */
    void acceptResponse(ProbeResponse response) {
        Operation operation = operations.get(response.targetId());
        if (operation == null || !operation.request.sessionId().equals(response.sessionId())
                || !operation.playerId.equals(response.targetId())) return;
        finish(operation, response);
    }

    private static String componentIdentity(Component component) {
        if (component instanceof TranslatableComponent translatable) {
            return "translate:" + translatable.key();
        }
        if (component instanceof KeybindComponent keybind) {
            return "keybind:" + keybind.keybind();
        }
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Operation operation = operations.get(event.getPlayer().getUniqueId());
        if (operation != null && operation.player == event.getPlayer()) {
            finish(operation, ProbeResponse.disconnected(operation.request));
        }
    }

    private void finish(Operation operation, ProbeResponse response) {
        if (!claim(operation)) return;
        debug("finish session=" + operation.request.sessionId() + " outcome=" + response.outcome());
        if (operation.timeout != null) operation.timeout.cancel();
        timeline.event("CLIENT_RESPONSE", operation.startedNanos, operation.request,
                "outcome=" + response.outcome());
        if (operation.location == null) {
            notifyResponse(operation, response);
            return;
        }
        Location location = operation.location;
        try {
            notifyResponse(operation, response);
        } finally {
            scheduleCleanup(operation, location);
        }
    }

    private void notifyResponse(Operation operation, ProbeResponse response) {
        try {
            operation.response.accept(response);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Client probe response callback failed session="
                    + operation.request.sessionId() + " reason=" + exception.getClass().getSimpleName());
        }
    }

    private void cancel(Operation operation) {
        if (!claim(operation)) return;
        operation.cancelled = true;
        if (operation.timeout != null) operation.timeout.cancel();
        restore(operation);
    }

    private void restore(Operation operation) {
        if (operation.location == null) return;
        scheduleCleanup(operation, operation.location);
    }

    private void scheduleCleanup(Operation operation, Location location) {
        RegionTarget target = operation.cleanupTarget;
        if (target == null) {
            // No region context was ever captured, so no temporary block state was mutated and
            // there is nothing to restore. Never rebuild a target from a Location here.
            plugin.getLogger().warning("Client probe cleanup skipped without a captured region target session="
                    + operation.request.sessionId());
            return;
        }
        try {
            timeline.event("CLEANUP_START", operation.startedNanos, operation.request, null);
            scheduler.runAtLocation(target, () -> {
                try {
                    if (operation.original != null) {
                        if (!world.restore(operation.original)) {
                            plugin.getLogger().warning("Client probe sign restoration was rejected for session="
                                    + operation.request.sessionId());
                        }
                    } else if (operation.temporaryStateStarted) {
                        world.setType(location, Material.AIR);
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Client probe sign restoration failed session="
                            + operation.request.sessionId() + " reason=" + exception.getClass().getSimpleName());
                }
                try {
                    if (operation.barrierPlaced && operation.barrierLocation != null) {
                        try {
                            world.setType(operation.barrierLocation, Material.AIR);
                        } catch (RuntimeException exception) {
                            plugin.getLogger().warning("Client probe barrier restoration failed session="
                                    + operation.request.sessionId() + " reason="
                                    + exception.getClass().getSimpleName());
                        }
                    }
                    timeline.event("CLEANUP_END", operation.startedNanos, operation.request, null);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Client probe cleanup completion failed session="
                            + operation.request.sessionId() + " reason=" + exception.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Client probe cleanup scheduling failed session="
                    + operation.request.sessionId() + " reason=" + exception.getClass().getSimpleName());
        }
    }

    private void scheduleAtEntity(Operation operation, Runnable task) {
        try {
            scheduler.runAtEntity(new EntityTarget(operation.player), () -> runSafely(operation, task),
                    () -> schedulingFailed(operation, new IllegalStateException("entity scheduler retired target")));
        } catch (RuntimeException exception) {
            schedulingFailed(operation, exception);
        }
    }

    private void scheduleAtLocation(Operation operation, RegionTarget target, Runnable task) {
        try {
            scheduler.runAtLocation(target, () -> runSafely(operation, task));
        } catch (RuntimeException exception) {
            schedulingFailed(operation, exception);
        }
    }

    private TaskHandle scheduleGlobalLater(Operation operation, Runnable task, long delayTicks) {
        try {
            return scheduler.runGlobalLater(() -> runSafely(operation, task), delayTicks);
        } catch (RuntimeException exception) {
            schedulingFailed(operation, exception);
            return null;
        }
    }

    private void runSafely(Operation operation, Runnable task) {
        synchronized (operation) {
            if (!active(operation)) return;
            try {
                task.run();
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Client probe operation failed session="
                        + operation.request.sessionId() + " stage=" + operation.packetOrder.stage()
                        + " reason=" + exception.getClass().getSimpleName());
                finish(operation, ProbeResponse.error(operation.request));
            }
        }
    }

    private void schedulingFailed(Operation operation, RuntimeException exception) {
        plugin.getLogger().warning("Client probe scheduling failed reason="
                + exception.getClass().getSimpleName());
        finish(operation, ProbeResponse.error(operation.request));
    }

    private boolean claim(Operation operation) {
        synchronized (operation) {
            if (!operation.terminal.compareAndSet(false, true)) return false;
            operations.remove(operation.playerId, operation);
            return true;
        }
    }

    private static boolean isTerminal(Operation operation) {
        return operation.terminal.get();
    }

    private static RegionTarget regionTarget(Location location) {
        return new RegionTarget(location.getWorld(), chunkCoordinate(location.getBlockX()),
                chunkCoordinate(location.getBlockZ()));
    }

    static int chunkCoordinate(int blockCoordinate) {
        return blockCoordinate >> 4;
    }

    private boolean active(Operation operation) {
        return operations.get(operation.playerId) == operation
                && !operation.cancelled && !isTerminal(operation);
    }

    private boolean activeAtEntity(Operation operation) {
        if (!eligible(operation) || !active(operation)) return false;
        if (!operation.player.isOnline()) {
            finish(operation, ProbeResponse.disconnected(operation.request));
            return false;
        }
        return true;
    }

    private boolean eligible(Operation operation) {
        try {
            if (probeEligibility.test(operation.request.target().id())) return true;
        } catch (RuntimeException exception) {
            finish(operation, ProbeResponse.error(operation.request));
            return false;
        }
        finish(operation, ProbeResponse.skipped(operation.request));
        return false;
    }

    private void debug(String message) {
        if (debug) plugin.getLogger().info("[ClientProbe] " + message);
    }

    private static Location candidate(Operation operation, int index) {
        if (index == 0) return operation.candidateBase.clone().add(0, 4, 0);
        if (operation.fallbackCandidates == null) {
            operation.fallbackCandidates = fallbackCandidates(operation.candidateBase);
        }
        return operation.fallbackCandidates.get(index - 1);
    }

    static List<Location> candidates(Location base) {
        List<Location> result = new ArrayList<>(CANDIDATE_COUNT);
        for (int index = 0; index < CANDIDATE_COUNT; index++) result.add(candidateAt(base, index));
        return List.copyOf(result);
    }

    private static List<Location> fallbackCandidates(Location base) {
        List<Location> result = new ArrayList<>(CANDIDATE_COUNT - 1);
        for (int index = 1; index < CANDIDATE_COUNT; index++) result.add(candidateAt(base, index));
        return List.copyOf(result);
    }

    private static Location candidateAt(Location base, int index) {
        // Keep the sign above the player's head so placement cannot damage or
        // obstruct the player while the probe is running.
        return switch (index) {
            case 0 -> base.clone().add(0, 4, 0);
            case 1 -> base.clone().add(0, 2, 2);
            case 2 -> base.clone().add(0, 2, -2);
            case 3 -> base.clone().add(2, 2, 0);
            case 4 -> base.clone().add(-2, 2, 0);
            case 5 -> base.clone().add(0, 3, 2);
            case 6 -> base.clone().add(0, 3, -2);
            case 7 -> base.clone().add(2, 3, 0);
            case 8 -> base.clone().add(-2, 3, 0);
            default -> throw new IndexOutOfBoundsException("candidate=" + index);
        };
    }

    @Override
    public void stop() {
        for (Operation operation : List.copyOf(operations.values())) {
            finish(operation, ProbeResponse.disconnected(operation.request));
        }
    }

    int activeOperations() { return operations.size(); }

    private static final class Operation implements ProbeHandle {
        private final ProbeRequest request;
        private final Player player;
        private final UUID playerId;
        private final Consumer<ProbeResponse> response;
        private volatile Location location;
        private volatile RegionTarget cleanupTarget;
        private volatile BlockState original;
        private volatile Sign sign;
        private volatile TaskHandle timeout;
        private volatile Runnable cancelAction;
        private volatile Location barrierLocation;
        private volatile boolean barrierPlaced;
        private volatile boolean temporaryStateStarted;
        private volatile boolean cancelled;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile long startedNanos;
        private volatile Location candidateBase;
        private volatile List<Location> fallbackCandidates;
        private final PacketOrder packetOrder = new PacketOrder();
        private volatile Object blockEntityPacket;

        private Operation(ProbeRequest request, Player player, Consumer<ProbeResponse> response) {
            this.request = request;
            this.player = player;
            this.playerId = request.target().id();
            this.response = response;
        }

        @Override public void cancel() { if (cancelAction != null) cancelAction.run(); else cancelled = true; }
        @Override public boolean cancelled() { return cancelled; }
    }

    enum PacketStage { NEW, SIGN_UPDATED, BLOCK_ENTITY_SENT, OPEN_EDITOR_SENT }

    static final class PacketOrder {
        private volatile PacketStage stage = PacketStage.NEW;

        void signUpdated() {
            if (stage != PacketStage.NEW) throw new IllegalStateException("Sign update out of order: " + stage);
            stage = PacketStage.SIGN_UPDATED;
        }

        boolean canSendBlockEntity() { return stage == PacketStage.SIGN_UPDATED; }

        void blockEntitySent() {
            if (stage != PacketStage.SIGN_UPDATED) throw new IllegalStateException("Block entity packet out of order: " + stage);
            stage = PacketStage.BLOCK_ENTITY_SENT;
        }

        boolean canOpenEditor() { return stage == PacketStage.BLOCK_ENTITY_SENT; }

        void openEditorSent() {
            if (stage != PacketStage.BLOCK_ENTITY_SENT) throw new IllegalStateException("Open editor packet out of order: " + stage);
            stage = PacketStage.OPEN_EDITOR_SENT;
        }

        PacketStage stage() { return stage; }
    }

    private static final class EmptyHandle implements ProbeHandle {
        @Override public void cancel() { }
        @Override public boolean cancelled() { return false; }
    }
}
