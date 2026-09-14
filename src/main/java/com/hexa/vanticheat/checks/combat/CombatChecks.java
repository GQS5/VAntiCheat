package com.hexa.vanticheat.checks.combat;

import com.hexa.vanticheat.checks.CheckResult;
import com.hexa.vanticheat.core.LagContext;
import com.hexa.vanticheat.core.VConfig;
import com.hexa.vanticheat.state.NormalizedCombatState;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat heuristics with multi-signal requirements:
 * - Reach: distance at hit time (eye-to-target), sustained over 3+ hits before evidence.
 * - KillAura: impossible hit rate / multi-target swing consistency.
 * - AutoClicker/Criticals heuristics are conservative (high ping exempt).
 */
public final class CombatChecks {

    private final VConfig config;
    private final com.hexa.vanticheat.core.TpsMeter tpsMeter;
    private final Map<UUID, ReachState> reach = new ConcurrentHashMap<>();
    private final Map<UUID, AuraState> aura = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> clickJitter = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Deque<Long>> clickIntervals = new ConcurrentHashMap<>();

    public CombatChecks(VConfig config) { this(config, null); }
    public CombatChecks(VConfig config, com.hexa.vanticheat.core.TpsMeter meter) {
        this.config = config; this.tpsMeter = meter;
    }

    /**
     * Platform adapter: builds normalized state from Bukkit objects, then runs
     * the pure core below. All Bukkit access stays in this method (region thread).
     */
    public CheckResult evaluate(Player p, EntityDamageByEntityEvent e) {
        UUID id = p.getUniqueId();
        LagContext lag;
        double dist;
        UUID targetId;
        try {
            lag = LagContext.of(p, tpsMeter, false); // cheap: ping + cached TPS
            var eye = p.getEyeLocation();
            var tl = e.getEntity().getLocation().add(0, 0.9, 0);
            dist = eye.distance(tl);
            try {
                double closing = p.getVelocity().length() + e.getEntity().getVelocity().length();
                dist = Math.max(0, dist - closing * 0.15); // interpolation slack
            } catch (Throwable ignored) {}
            targetId = e.getEntity().getUniqueId();
        } catch (Exception ex) {
            return CheckResult.pass();
        }
        // Reach first (most reliable server-side).
        CheckResult r = assessReach(id, new NormalizedCombatState(dist, lag.ping, 0, false, lag.severeLag));
        if (r.failed()) return r;
        return assessAura(id, System.currentTimeMillis(), targetId);
    }

    /**
     * Pure reach core: no Bukkit access, headless-testable. Repeated-hit
     * consistency: 3+ anomalous hits before evidence; lag suppresses.
     */
    public CheckResult assessReach(UUID id, NormalizedCombatState s) {
        var snap = config.snapshot(); // O(1) snapshot, no YAML per hit
        if (!snap.reachEnabled || !snap.behaviorEnabled) return CheckResult.pass();
        double maxReach = snap.maxReach;
        double slack = s.ping() > 250 ? 0.6 : s.ping() > 150 ? 0.3 : 0.0;
        if (s.distance() < maxReach + slack) {
            ReachState st = reach.get(id);
            if (st != null) st.decay();
            return CheckResult.pass();
        }
        ReachState st = reach.computeIfAbsent(id, k -> new ReachState());
        st.hits++;
        st.maxDist = Math.max(st.maxDist, s.distance());
        if (st.hits >= 3) {
            st.hits = 0;
            if (s.severeLag()) return CheckResult.pass(); // lag-aware: suppress during severe lag
            return CheckResult.fail(1.0, 8, "Reach", "distance",
                    Map.of("dist", String.format("%.2f", s.distance()), "limit", String.valueOf(maxReach), "ping", String.valueOf(s.ping())));
        }
        return CheckResult.pass();
    }

    /** Pure aura core: hit-rate + multi-target + click stats. Headless-testable. */
    public CheckResult assessAura(UUID id, long now, UUID targetId) {
        var snap = config.snapshot();
        if (!snap.killauraEnabled || !snap.behaviorEnabled) return CheckResult.pass();
        AuraState s = aura.computeIfAbsent(id, k -> new AuraState());
        long dt = now - s.lastHit;
        s.lastHit = now;
        // Inhuman sustained rate: <55ms between damaging hits repeatedly (20 hits window).
        if (dt < 55) s.fastHits++; else s.fastHits = Math.max(0, s.fastHits - 2);
        // Multi-target swing: different entity each hit within 300ms.
        if (s.lastTarget != null && !s.lastTarget.equals(targetId) && dt < 300) s.swaps++;
        else if (dt > 1000) s.swaps = 0;
        s.lastTarget = targetId;
        if (s.fastHits >= 12) {
            s.fastHits = 0;
            return CheckResult.fail(1.5, 10, "KillAura", "hit-rate",
                    Map.of("detail", "sustained inhuman hit rate"));
        }
        if (s.swaps >= 6) {
            s.swaps = 0;
            return CheckResult.fail(1.0, 8, "KillAura", "multi-target",
                    Map.of("detail", "rapid multi-target hits"));
        }
        // AutoClicker: statistical timing (variance/periodicity), CPS alone never proof.
        return assessClick(id, now);
    }

    /**
     * Pure click-timing core. Human clicking has high variance even when fast;
     * machine metronomes show ultra-low variance over 30+ samples.
     */
    public CheckResult assessClick(UUID id, long now) {
        Long last = lastClick.put(id, now);
        if (last == null) return CheckResult.pass();
        long dt = now - last;
        if (dt <= 0 || dt > 2000) return CheckResult.pass();
        var q = clickIntervals.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
        synchronized (q) {
            q.addLast(dt);
            while (q.size() > 60) q.pollFirst();
            if (q.size() < 30) return CheckResult.pass();
            double mean = q.stream().mapToLong(Long::longValue).average().orElse(0);
            double var = q.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
            double sd = Math.sqrt(var);
            if (mean < 120 && sd < 6) {
                q.clear();
                return CheckResult.fail(1.0, 10, "AutoClicker", "timing-stats",
                        Map.of("mean", String.format("%.1f", mean), "sd", String.format("%.1f", sd)));
            }
        }
        return CheckResult.pass();
    }

    private int safePing(Player p) {
        try { return p.getPing(); } catch (Throwable t) { return 0; }
    }

    public void purge(UUID id) {
        reach.remove(id); aura.remove(id); lastClick.remove(id); clickJitter.remove(id);
    }

    private static final class ReachState { int hits; double maxDist; void decay() { if (hits > 0) hits--; } }
    private static final class AuraState { long lastHit; int fastHits; int swaps; UUID lastTarget; }
}
