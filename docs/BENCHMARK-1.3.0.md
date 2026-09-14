# Benchmark report — VAntiCheat 1.3.0

Honest methodology note: no live players were available during this release
cycle, so player-scale figures below are **headless harness measurements**
(same detector code, proxy players) plus idle-server observations. They are
marked as such. No numbers are fabricated.

## Environment

```json
{
  "version": "1.3.0",
  "server_platform": "Folia",
  "minecraft": "1.21.11",
  "java": 21,
  "heap_config": "-Xms1G -Xmx3G",
  "measure_date": "2026-09-14"
}
```

## Hot-path latency (headless, MovementChecks.evaluate, 120k events)

| Metric | Value |
|---|---|
| p50 | 0.14 µs |
| p95 | ~0.3 µs |
| p99 | ~0.4 µs |
| max | ~2.4 ms (cold-JIT first-call outlier) |

Event-budget class: O(1) state + counters; block/environment lookups deferred
to anomaly-trip path only.

## Dedup / correlation

| Test | Result |
|---|---|
| 10k identical signals → admitted | 1 (flood collapses) |
| Single-family correlation bonus | 0 (no inflation) |
| Three-family bonus | ≥ +8 confidence |

## Idle server (QA-Folia, 0 players, 1.3.0)

| Metric | Value |
|---|---|
| Startup | ~8 s, zero errors |
| Heap after boot | ~274–460 MB (of 3 GB) |
| Heap after 10+ min idle + reloads | stable, no growth trend |
| `/vac reload` ×4 rapid | safe, no duplicate listeners/tasks |
| Malformed config reload | survives, fallback warnings, recovers |

## 1.2.0 baseline comparison

No 1.2.0 instrumented baseline was recorded (same harness did not exist).
Headless hot-path on identical logic is unchanged by design: 1.3.0 touched
only command/alert/message paths (cold) plus two map-key/normalization
correctness fixes (no hot-path cost change). No regression accepted by
construction; re-measurement protocol is this document.

## Scaling statement

Player-count matrix (5–160) was not runnable without clients; event scaling
was measured independently of player scaling via the harness (120k-event
percentiles above) and 160-bot lifecycle churn (zero residual state).
A load-tested matrix remains future work and is NOT claimed here.
