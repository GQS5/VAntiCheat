package com.hexa.vanticheat.core;

import java.lang.management.ManagementFactory;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny lock-free profiler: per-check timings + global counters.
 * No allocations in hot path beyond map lookup.
 */
public final class VProfiler {

    private final Map<String, LongSummaryStatistics> stats = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AtomicLong totalChecks = new AtomicLong();
    private volatile boolean enabled;
    private volatile int sampleRate = 20;
    private final java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();

    public void configure(boolean enabled, int sampleRate) {
        this.enabled = enabled;
        this.sampleRate = Math.max(1, sampleRate);
    }

    public boolean enabled() { return enabled; }

    public long time(String key, Runnable r) {
        long t0 = System.nanoTime();
        try {
            r.run();
        } finally {
            long dt = System.nanoTime() - t0;
            record(key, dt);
        }
        return 0;
    }

    public void record(String key, long nanos) {
        if (!enabled) return; // zero-cost when disabled (§27)
        long n = seen.incrementAndGet();
        if ((n % sampleRate) != 0) return; // sampling
        totalChecks.incrementAndGet();
        stats.computeIfAbsent(key, k -> new LongSummaryStatistics()).accept(nanos);
        AtomicLong c = counters.computeIfAbsent(key, k -> new AtomicLong());
        c.incrementAndGet();
    }

    public void count(String key) {
        if (!enabled) return;
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("checks=").append(totalChecks.get());
        for (Map.Entry<String, LongSummaryStatistics> e : stats.entrySet()) {
            LongSummaryStatistics s = e.getValue();
            synchronized (s) {
                sb.append(" | ").append(e.getKey())
                  .append(": n=").append(s.getCount())
                  .append(" avg=").append(String.format("%.2f", s.getAverage() / 1000.0)).append("us")
                  .append(" max=").append(s.getMax() / 1000).append("us");
            }
        }
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024 / 1024;
        sb.append(" | heap=").append(heap).append("MB");
        return sb.toString();
    }

    public long totalChecks() { return totalChecks.get(); }
}
