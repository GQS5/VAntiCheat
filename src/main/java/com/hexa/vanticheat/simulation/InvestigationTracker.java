package com.hexa.vanticheat.simulation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded investigation tracker (§18-19). 95%+ of players stay NORMAL (cheapest).
 * Escalation from confidence + diversity; decay back on quiet periods.
 * Owner: region threads (ConcurrentHashMap, per-player small records).
 */
public final class InvestigationTracker {
    private record Entry(InvestigationState state, long at) {}
    private final Map<UUID, Entry> states = new ConcurrentHashMap<>();
    private static final int MAX = 5000;

    public InvestigationState get(UUID id) {
        Entry e = states.get(id);
        return e == null ? InvestigationState.NORMAL : e.state();
    }

    /** Called on evidence (cold path only, never per-move). */
    public InvestigationState update(UUID id, int confidence, int diversity) {
        InvestigationState next;
        if (confidence >= 95) next = InvestigationState.CONFIRMED;
        else if (confidence >= 80 && diversity >= 2) next = InvestigationState.HIGH_CONFIDENCE;
        else if (confidence >= 60) next = InvestigationState.INVESTIGATING;
        else if (confidence >= 45) next = InvestigationState.SUSPICIOUS;
        else if (confidence >= 30) next = InvestigationState.WATCH;
        else next = InvestigationState.NORMAL;
        if (states.size() > MAX) states.clear();
        states.put(id, new Entry(next, System.currentTimeMillis()));
        return next;
    }

    public void decay(UUID id, int confidence) {
        if (confidence < 20) states.remove(id);
    }

    public void purge(UUID id) { states.remove(id); }
}
