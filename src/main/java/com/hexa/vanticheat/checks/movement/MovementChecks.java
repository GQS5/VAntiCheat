package com.hexa.vanticheat.checks.movement;

import com.hexa.vanticheat.checks.CheckResult;
import com.hexa.vanticheat.core.LagContext;
import com.hexa.vanticheat.core.TpsMeter;
import com.hexa.vanticheat.core.VConfig;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative movement heuristics (1.2 hot-path).
 * Event budget: positional math + counters only; block/environment lookups
 * deferred until an anomaly counter trips (rare path). Snapshot config,
 * cached TPS, no per-event YAML.
 * State owner: per-player MoveState in region-local map (no global locks).
 */
public final class MovementChecks {

    private final VConfig config;
    private final TpsMeter tpsMeter;
    private final Map<UUID, MoveState> states = new ConcurrentHashMap<>();

    public MovementChecks(VConfig config) { this(config, null); }
    public MovementChecks(VConfig config, TpsMeter meter) { this.config = config; this.tpsMeter = meter; }

    public CheckResult evaluate(Player p, Location from, Location to) {
        // Cheap early exits first: gamemode/state checks are field reads.
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return CheckResult.pass();
        if (p.isGliding() || p.isInsideVehicle()) return CheckResult.pass();
        try { if (p.getAllowFlight()) return CheckResult.pass(); } catch (Throwable ignored) {}

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        // Micro-move prefilter: head-only rotations already filtered by caller; skip sub-mm.
        if (dx * dx + dy * dy + dz * dz < 1e-8) return CheckResult.pass();

        var snap = config.snapshot();
        if (!snap.behaviorEnabled) return CheckResult.pass();
        LagContext lag = LagContext.of(p, tpsMeter, false);
        if (lag.severeLag) {
            states.remove(p.getUniqueId());
            return CheckResult.pass();
        }
        MoveState s = states.computeIfAbsent(p.getUniqueId(), k -> new MoveState());

        // --- Fly: sustained upward / hover without support ---
        if (snap.flyEnabled) {
            boolean onGround;
            try { onGround = p.isOnGround(); } catch (Throwable t) { return CheckResult.pass(); }
            if (!onGround && dy > 0.15 && horiz < 0.15) s.airRise++;
            else if (!onGround && Math.abs(dy) < 0.02 && horiz > 0.1) {
                float fall;
                try { fall = p.getFallDistance(); } catch (Throwable t) { fall = 99; }
                if (fall < 1) s.hover++;
            }
            else { if (s.airRise > 0) s.airRise--; if (s.hover > 0) s.hover--; }
            if (s.airRise >= 8 || s.hover >= 20) {
                if (!environmentExempt(p)) { // rare path: block lookup only here
                    boolean rise = s.airRise >= 8;
                    s.airRise = 0; s.hover = 0;
                    return fail(rise ? "Fly" : "Fly", rise ? "vertical-rise" : "hover",
                            Map.of("dy", fmt(dy)));
                }
                s.airRise = 0; s.hover = 0;
            }
        }

        // --- Speed: horizontal velocity vs sprint baseline, ping-aware ---
        if (snap.speedEnabled) {
            boolean sprinting;
            try { sprinting = p.isSprinting(); } catch (Throwable t) { sprinting = false; }
            double limit = sprinting ? 0.75 : 0.45;
            if (lag.ping > 250) limit += 0.25; else if (lag.ping > 150) limit += 0.12;
            boolean onGround;
            try { onGround = p.isOnGround(); } catch (Throwable t) { onGround = true; }
            if (horiz > limit && onGround) {
                if (++s.speed >= 10) {
                    // Knockback/velocity check only on trip (rare path).
                    if (knockbackExempt(p)) { s.speed = 0; }
                    else if (!environmentExempt(p)) {
                        s.speed = 0;
                        return fail("Speed", "horizontal", Map.of("horiz", fmt(horiz), "limit", fmt(limit)));
                    } else s.speed = 0;
                }
            }
            else if (s.speed > 0) s.speed--;
        }

        // --- NoFall ---
        if (snap.nofallEnabled) {
            try {
                if (p.getFallDistance() > 6 && p.isOnGround()) s.fall++;
                else if (p.isOnGround()) s.fall = 0;
            } catch (Throwable ignored) {}
            if (s.fall >= 30) { s.fall = 0; return fail("NoFall", "fall-pattern", Map.of("detail", "repeat")); }
        }

        // --- Step ---
        if (snap.stepEnabled) {
            try {
                if (dy > 1.1 && p.isOnGround()) {
                    if (++s.step >= 3 && !environmentExempt(p)) {
                        s.step = 0;
                        return fail("Step", "instant-step", Map.of("dy", fmt(dy)));
                    }
                }
                else if (s.step > 0) s.step--;
            } catch (Throwable ignored) {}
        }
        return CheckResult.pass();
    }

    /** Rare-path only: block/environment exemptions (liquid/slime/ladder/piston/levitation). */
    private boolean environmentExempt(Player p) {
        try {
            var b = p.getLocation().getBlock();
            if (b.isLiquid()) return true;
            String bt = b.getType().name();
            if (bt.contains("SLIME") || bt.contains("LADDER") || bt.contains("VINE") || bt.contains("WATER")
                    || bt.contains("PISTON") || bt.contains("HONEY") || bt.contains("SCAFFOLD")) return true;
            if (p.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean knockbackExempt(Player p) {
        try {
            if (p.getFallDistance() > 0 && p.getVelocity().lengthSquared() > 0.2) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private CheckResult fail(String name, String method, Map<String, String> values) {
        return CheckResult.fail(1.0, 8, name, method, values);
    }

    private static String fmt(double d) { return String.format("%.2f", d); }

    public void purge(UUID id) { states.remove(id); }

    private static final class MoveState {
        int airRise, hover, speed, fall, step;
    }
}
