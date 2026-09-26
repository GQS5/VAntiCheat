package site.vackstudio.vanticheat.platform.paper;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import site.vackstudio.vanticheat.detection.behavior.BehaviorRegistry;
import site.vackstudio.vanticheat.detection.behavior.BoundingBox3d;
import site.vackstudio.vanticheat.detection.behavior.CombatObservation;
import site.vackstudio.vanticheat.detection.behavior.InteractionObservation;
import site.vackstudio.vanticheat.detection.behavior.MovementObservation;
import site.vackstudio.vanticheat.detection.behavior.MovementContext;
import site.vackstudio.vanticheat.detection.behavior.Position3d;
import site.vackstudio.vanticheat.detection.behavior.PlacementObservation;
import site.vackstudio.vanticheat.detection.behavior.RotationObservation;
import site.vackstudio.vanticheat.detection.behavior.FallDamageObservation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class PaperBehaviorObservationListener implements Listener {
    private final BehaviorRegistry registry;
    private final Logger logger;
    private final Map<UUID, PlayerState> previous = new ConcurrentHashMap<>();
    private final Map<UUID, Long> previousAttacks = new ConcurrentHashMap<>();

    public PaperBehaviorObservationListener(Plugin plugin, BehaviorRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        long now = System.nanoTime();
        boolean grounded = ((Entity) player).isOnGround();
        PlayerState prior = previous.put(player.getUniqueId(), new PlayerState(to.getYaw(), to.getPitch(), grounded,
                now, position(to)));
        Position3d fromPosition = position(from);
        Position3d toPosition = position(to);
        long durationMillis = prior == null ? 0 : Math.max(0, (now - prior.timestampNanos()) / 1_000_000);
        MovementContext movementContext = movementContext(player, to).withTimingUncertain(durationMillis > 250);
        registry.observe(new MovementObservation(player.getUniqueId(), now, fromPosition, toPosition,
                to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ(),
                fromPosition.horizontalDistance(toPosition), Math.abs(to.getY() - from.getY()),
                durationMillis,
                grounded, prior != null && prior.grounded() != grounded, position(player.getVelocity()), movementContext));
        if (prior != null) {
            registry.observe(new RotationObservation(player.getUniqueId(), now, to.getYaw(), to.getPitch(),
                    angleDelta(to.getYaw(), prior.yaw()), to.getPitch() - prior.pitch()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        Entity target = event.getEntity();
        Location eye = player.getEyeLocation();
        Position3d eyePosition = position(eye);
        BoundingBox3d bounds = bounds(target);
        long now = System.nanoTime();
        Long priorAttack = previousAttacks.put(player.getUniqueId(), now);
        PlayerState prior = previous.get(player.getUniqueId());
        Position3d attackerPosition = position(player.getLocation());
        boolean attackerMoving = prior != null && attackerPosition.distance(prior.position()) > 0.001;
        boolean targetMoving = target.getVelocity().lengthSquared() > 0.0001;
        registry.observe(new CombatObservation(player.getUniqueId(), now, target.getUniqueId(),
                attackerPosition, eyePosition, position(target.getLocation()), bounds,
                bounds.distanceTo(eyePosition), priorAttack == null ? 0 : Math.max(0, (now - priorAttack) / 1_000_000),
                player.hasLineOfSight(target), false, player.getCooledAttackStrength(0.0f), attackerMoving,
                targetMoving, target.getVelocity().lengthSquared() > 0.25, false));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        registry.observe(new FallDamageObservation(player.getUniqueId(), System.nanoTime(), event.getFinalDamage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        observeInteraction(event.getPlayer(), "interact", event.getClickedBlock().getType(),
                event.getClickedBlock().getLocation(), event.getPlayer().getInventory().getItemInMainHand());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        observeInteraction(event.getPlayer(), "place", event.getBlockPlaced().getType(),
                event.getBlockPlaced().getLocation(), event.getItemInHand());
        Player player = event.getPlayer();
        Position3d eye = position(player.getEyeLocation());
        Position3d placed = position(event.getBlockPlaced().getLocation());
        Position3d support = position(event.getBlockAgainst().getLocation());
        registry.observe(new PlacementObservation(player.getUniqueId(), System.nanoTime(), placed, support,
                event.getBlockAgainst().getFace(event.getBlockPlaced()) == null ? "UNKNOWN"
                        : event.getBlockAgainst().getFace(event.getBlockPlaced()).name(),
                event.getBlockPlaced().getType().name(),
                event.getItemInHand() == null ? "AIR" : event.getItemInHand().getType().name(),
                position(player.getLocation()), event.getPlayer().getLocation().getYaw(),
                event.getPlayer().getLocation().getPitch(), ((Entity) player).isOnGround(),
                player.isSneaking(), movementContext(player, player.getLocation()), eye.distance(placed)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        observeInteraction(event.getPlayer(), "break", event.getBlock().getType(), event.getBlock().getLocation(),
                event.getPlayer().getInventory().getItemInMainHand());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (!event.isCancelled()) reset(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) reset(player);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) reset(player);
    }

    private void observeInteraction(Player player, String interaction, Material material,
                                    Location location, ItemStack heldItem) {
        registry.observe(new InteractionObservation(player.getUniqueId(), System.nanoTime(), interaction,
                material.name(), position(location), heldItem == null ? "AIR" : heldItem.getType().name()));
    }

    private static Position3d position(Location location) {
        return new Position3d(location.getX(), location.getY(), location.getZ());
    }

    private static Position3d position(org.bukkit.util.Vector vector) {
        return new Position3d(vector.getX(), vector.getY(), vector.getZ());
    }

    private MovementContext movementContext(Player player, Location location) {
        Material current = location.getBlock().getType();
        Material below = location.getBlock().getRelative(0, -1, 0).getType();
        var velocity = player.getVelocity();
        GameMode gameMode = player.getGameMode();
        boolean activeEffect = !player.getActivePotionEffects().isEmpty();
        boolean fluid = isFluid(current) || isFluid(below);
        boolean climbable = isClimbable(current) || isClimbable(below);
        boolean slime = isSlime(current) || isSlime(below);
        boolean ice = isIce(current) || isIce(below);
        boolean reducing = fluid || climbable || slime || isFallReducing(current) || isFallReducing(below);
        return new MovementContext(fluid, climbable, slime, ice, player.isInsideVehicle(),
                player.isGliding(), gameMode == GameMode.SPECTATOR || gameMode == GameMode.CREATIVE,
                activeEffect, velocity.lengthSquared() > .25, false, false, reducing, reducing, below.name(),
                player.isSprinting(), player.hasPotionEffect(PotionEffectType.SPEED),
                player.hasPotionEffect(PotionEffectType.SLOWNESS), false, false);
    }

    private static boolean isFluid(Material material) {
        return material == Material.WATER || material == Material.LAVA
                || material == Material.BUBBLE_COLUMN || material.name().contains("KELP");
    }

    private static boolean isClimbable(Material material) {
        return switch (material.name()) {
            case "LADDER", "VINE", "WEEPING_VINES", "TWISTING_VINES", "CAVE_VINES",
                    "CAVE_VINES_PLANT", "SCAFFOLDING" -> true;
            default -> false;
        };
    }

    private static boolean isSlime(Material material) {
        return material == Material.SLIME_BLOCK || material == Material.HONEY_BLOCK;
    }

    private static boolean isIce(Material material) {
        return material == Material.ICE || material == Material.PACKED_ICE
                || material == Material.BLUE_ICE || material == Material.FROSTED_ICE;
    }

    private static boolean isFallReducing(Material material) {
        return switch (material.name()) {
            case "BED", "WHITE_BED", "ORANGE_BED", "MAGENTA_BED", "LIGHT_BLUE_BED", "YELLOW_BED",
                    "LIME_BED", "PINK_BED", "GRAY_BED", "LIGHT_GRAY_BED", "CYAN_BED", "PURPLE_BED",
                    "BLUE_BED", "BROWN_BED", "GREEN_BED", "RED_BED", "BLACK_BED", "HAY_BLOCK",
                    "POWDER_SNOW", "SCAFFOLDING" -> true;
            default -> false;
        };
    }

    private void reset(Player player) {
        UUID playerId = player.getUniqueId();
        previous.remove(playerId);
        previousAttacks.remove(playerId);
        registry.remove(playerId);
    }

    private static BoundingBox3d bounds(Entity entity) {
        var box = entity.getBoundingBox();
        return new BoundingBox3d(box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }

    private static float angleDelta(float current, float prior) {
        float delta = (current - prior) % 360.0f;
        return delta > 180.0f ? delta - 360.0f : delta < -180.0f ? delta + 360.0f : delta;
    }

    private record PlayerState(float yaw, float pitch, boolean grounded, long timestampNanos, Position3d position) { }
}
