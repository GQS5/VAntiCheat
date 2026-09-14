package com.hexa.vanticheat.checks;

import com.hexa.vanticheat.alerts.AlertManager;
import com.hexa.vanticheat.core.VAntiCheat;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.core.VLogger;
import com.hexa.vanticheat.core.VProfiler;
import com.hexa.vanticheat.core.VTaskManager;
import com.hexa.vanticheat.evidence.ConfidenceEngine;
import com.hexa.vanticheat.evidence.EvidenceManager;
import com.hexa.vanticheat.evidence.EvidenceRecord;
import com.hexa.vanticheat.evidence.ViolationHistory;
import com.hexa.vanticheat.punishment.PunishmentManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central behavior-check hub. Event-driven only (no global tick loop).
 * Each handler is O(1), allocation-free in the common pass case.
 */
public final class CheckManager implements Listener {

    private final VAntiCheat plugin;
    private final VConfig config;
    private final VLogger log;
    private final VTaskManager tasks;
    private final VProfiler profiler;
    private final EvidenceManager evidence;
    private final ConfidenceEngine confidence;
    private final ViolationManager violations;
    private final AlertManager alerts;
    private final PunishmentManager punishments;

    private final com.hexa.vanticheat.checks.combat.CombatChecks combat;
    private final com.hexa.vanticheat.checks.movement.MovementChecks movement;
    private final com.hexa.vanticheat.checks.interaction.InteractionChecks interaction;
    private final com.hexa.vanticheat.checks.player.PlayerChecks playerChecks;

    private final Map<UUID, Long> alertCooldown = new ConcurrentHashMap<>();
    private static final long FAIL_COOLDOWN_MS = 1500;

    public CheckManager(VAntiCheat plugin, VConfig config, VLogger log, VTaskManager tasks,
                        VProfiler profiler, EvidenceManager evidence, ConfidenceEngine confidence,
                        ViolationManager violations, AlertManager alerts, PunishmentManager punishments) {
        this.plugin = plugin;
        this.config = config;
        this.log = log;
        this.tasks = tasks;
        this.profiler = profiler;
        this.evidence = evidence;
        this.confidence = confidence;
        this.violations = violations;
        this.alerts = alerts;
        this.punishments = punishments;
        this.combat = new com.hexa.vanticheat.checks.combat.CombatChecks(config,
                plugin == null ? null : plugin.tps());
        this.movement = new com.hexa.vanticheat.checks.movement.MovementChecks(config,
                plugin == null ? null : plugin.tps());
        this.interaction = new com.hexa.vanticheat.checks.interaction.InteractionChecks(config);
        this.playerChecks = new com.hexa.vanticheat.checks.player.PlayerChecks(config);
    }

    private boolean skip(Player p) {
        if (!config.snapshot().behaviorEnabled) return true;
        if (config.exempt(p)) return true;
        return false;
    }

    private void fail(Player p, String checkId, String display, CheckResult r) {
        long now = System.currentTimeMillis();
        Long last = alertCooldown.get(p.getUniqueId());
        double vlBefore = violations.get(p.getUniqueId(), checkId);
        double vl = violations.add(p.getUniqueId(), checkId, r.vlAdd());
        // Always accumulate VL, but only emit evidence on cooldown to avoid spam.
        if (last != null && now - last < FAIL_COOLDOWN_MS) return;
        alertCooldown.put(p.getUniqueId(), now);
        int confBefore = confidence.get(p.getUniqueId());
        int conf = confidence.addSignal(p.getUniqueId(), "check_violation", r.confidenceAdd());
        // Correlation bonus: diverse independent signals weigh more than repeats.
        try { conf = Math.min(100, conf + evidence.correlatedBonus(p.getUniqueId())); } catch (Throwable ignored) {}
        int ping = 0;
        try { ping = p.getPing(); } catch (Throwable ignored) {}
        String brand = plugin.clients() != null ? plugin.clients().brandOf(p.getUniqueId()) : "?";
        EvidenceRecord rec = EvidenceRecord.builder()
                .player(p.getUniqueId(), p.getName())
                .detector("Behavior").check(display).detection(r.detection()).detectionMethod(r.method())
                .confidenceBefore(confBefore).confidenceAfter(conf)
                .violationBefore(vlBefore).violationAfter(vl).brand(brand)
                .matched("").values(r.values())
                .correlated(evidence.correlatedSignals(p.getUniqueId()))
                .ping(ping).build();
        evidence.report(rec);
        plugin.violationHistory().add(p.getUniqueId(), display + " vl=" + String.format("%.1f", vl) + " conf=" + conf);
        // Investigation depth follows evidence (cold path only).
        try {
            int div = evidence.correlatedSignals(p.getUniqueId()).size();
            plugin.investigations().update(p.getUniqueId(), conf, Math.max(div, 1));
        } catch (Throwable ignored) {}
    }

    // ---- combat ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (skip(p)) return;
        boolean prof = profiler.enabled();
        long t0 = prof ? System.nanoTime() : 0;
        try {
            CheckResult r = combat.evaluate(p, e);
            if (r.failed()) fail(p, "combat", r.detection().contains("Reach") ? "Reach" : "KillAura", r);
        } catch (Throwable t) {
            log.debug("combat unavailable: " + t.getMessage());
        } finally {
            if (prof) profiler.record("check.combat", System.nanoTime() - t0);
        }
    }

    // ---- movement ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        // Cheap prefilter: ignore tiny head-only rotations.
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        if (dx * dx + dy * dy + dz * dz < 1e-8) return;
        boolean prof = profiler.enabled();
        long t0 = prof ? System.nanoTime() : 0;
        try {
            CheckResult r = movement.evaluate(p, from, to);
            if (r.failed()) fail(p, "movement." + r.detection(), r.detection(), r);
        } catch (Throwable t) {
            log.debug("movement unavailable: " + t.getMessage());
        } finally {
            if (prof) profiler.record("check.movement", System.nanoTime() - t0);
        }
    }

    // ---- interaction ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        CheckResult r = interaction.onPlace(p, e);
        if (r.failed()) fail(p, "interaction.fastplace", "FastPlace", r);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        CheckResult r = interaction.onBreak(p, e);
        if (r.failed()) fail(p, "interaction.fastbreak", "FastBreak", r);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        violations.reset(e.getPlayer().getUniqueId());
        alertCooldown.remove(e.getPlayer().getUniqueId());
        combat.purge(e.getPlayer().getUniqueId());
        movement.purge(e.getPlayer().getUniqueId());
        interaction.purge(e.getPlayer().getUniqueId());
        playerChecks.purge(e.getPlayer().getUniqueId());
        try {
            plugin.investigations().purge(e.getPlayer().getUniqueId());
            plugin.replay().purge(e.getPlayer().getUniqueId());
            plugin.replica().purge(e.getPlayer().getUniqueId());
            plugin.via().purge(e.getPlayer().getUniqueId());
            evidence.purge(e.getPlayer().getUniqueId());
            config.invalidateExempt(e.getPlayer().getUniqueId());
        } catch (Throwable ignored) {}
    }

    public Map<String, Double> vlSnapshot(UUID player) {
        return Map.of();
    }
}
