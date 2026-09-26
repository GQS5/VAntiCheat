# P5 Behavior Foundation

## Scope

P5 adds a platform-neutral observation domain and one conservative Reach proof of concept. The implementation is observe-only: signals are logged at `FINE` level and never reach `EnforcementService`.

## Domain boundary

`site.vackstudio.vanticheat.detection.behavior` contains immutable observations, geometry value objects, bounded player sessions, module contracts, and the registry. It has no Bukkit, Paper, Folia, or NMS dependency.

`PaperBehaviorObservationListener` is the platform adapter. It reads Bukkit event state on the event thread and immediately constructs immutable snapshots. Detectors receive only those snapshots.

## Observations

- Movement snapshots include position deltas, duration, ground state, ground transitions, and velocity.
- Rotation snapshots are derived from movement events.
- Combat snapshots include attacker eye position, target bounds, line of sight, and conservative distance to the target box.
- Interaction snapshots cover interact, place, and break events for future Scaffold and mining detectors.

Per-player sessions are UUID-keyed, bounded to 128 observations per stream, and removed on quit and registry shutdown.

## Reach proof of concept

Reach compares the attacker eye to the nearest point on the target bounding box:

- `CLEAR`: less than `3.15` blocks
- `UNCERTAIN`: the observation is explicitly marked uncertain
- `SUSPICIOUS`: `3.15` through less than `3.50` blocks
- `STRONG_SIGNAL`: `3.50` blocks or more

These thresholds are deliberately a signal vocabulary, not a punishment policy. They do not account for a complete latency model and must not be treated as proof of cheating without sustained evidence and further validation.

## Configuration

`behavior-detection.yml` controls the foundation:

```yaml
behavior-detection:
  enabled: true
  reach:
    enabled: true
    mode: observe
```

Only `observe` mode is accepted in P5. No behavior detector directly kicks, bans, or changes player state.
