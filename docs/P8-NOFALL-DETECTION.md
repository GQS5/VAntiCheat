# P8 NoFall Detection

## Scope

P8 adds a conservative, observe-only NoFall detector. It asks whether the server observed a downward fall sequence whose expected impact consequence is inconsistent with the observed landing, rather than treating a long fall as proof of cheating.

## Architecture

NoFall consumes the existing P5 movement observations through the existing `BehaviorRegistry` and `PaperBehaviorObservationListener`. It does not add another listener or duplicate movement history. Each `PlayerBehaviorSession` contains one compact bounded `NoFallState` and one bounded impact window.

`EntityDamageEvent` with cause `FALL` is converted to an immutable `FallDamageObservation` at the event boundary. Generic NoFall classes have no Bukkit, Paper, Folia, or NMS imports.

## Fall state and measurement

The state model distinguishes `NOT_FALLING`, `FALLING`, `LONG_FALL`, `LANDING_PENDING`, `LANDED`, `RESET`, and `UNCERTAIN`.

The detector begins a fall when a previously grounded player produces a descending airborne observation, or when an airborne trajectory begins descending. It tracks the start Y, lowest observed Y, elapsed descent time, landing Y, and observed fall impact. A fall is expected to cause damage only when the estimated downward distance reaches the conservative three-block threshold and no known context suppresses that expectation.

The estimate is derived from the immutable trajectory, not solely from Bukkit `Player#getFallDistance()`. The implementation does not assume that the server receives a perfect continuous client trajectory.

## Landing and damage correlation

Grounded transition after a tracked descent creates a bounded `LANDING_PENDING` result. A fall-damage event before or shortly after landing marks the expected impact as observed. Missing impact is evaluated only after a short landing/tick grace period and only when the fall had a reliable damage expectation.

An ordinary fall with observed damage is clear. A missing impact produces compact evidence with fall sequence, excessive distance, missing expected impact, and landing consistency categories. Damage events are never treated as proof by themselves.

## Legitimate no-damage handling

The movement context records fluid, climbable, slime/honey, ice, vehicle, gliding, game mode, active effect, external velocity, safe landing, fall-damage-reducing context, and landing surface. Water, climbables, slime, beds, hay, powder snow, scaffolding, vehicles, gliding, effects, special game modes, teleports, and plugin/server velocity therefore suppress or downgrade expectations. External velocity is retained as a context conflict rather than being mistaken for an ordinary fall or a cheat.

The adapter only records material context available at the region event boundary. Custom plugin launch mechanics and complete latency/TPS models remain unknown and are handled conservatively.

## Confidence and evidence

NoFall uses the established confidence vocabulary:

- `CLEAR`: normal fall consequence or no reliable expected consequence.
- `UNCERTAIN`: the first missing-impact mismatch or incomplete/context-conflicted information.
- `SUSPICIOUS`: repeated missing-impact mismatches.
- `STRONG_SIGNAL`: three or more repeated mismatches for reliable, comparable fall sequences.

`NoFallEvidence` includes detector, timestamp, player UUID, signal categories, duration, estimated distance, start/lowest/landing Y, state, expected and observed impact flags, confidence, and compact context. Ordinary movement and ordinary falls are not persisted or logged as evidence.

## Reset and lifecycle safety

Teleport, respawn, world change, vehicle enter/exit, quit, player removal, and registry shutdown clear the existing player session. Delayed or non-monotonic movement observations reset the NoFall trajectory. A teleport cannot become a fall sequence.

## Folia safety and performance

All Bukkit reads occur in `PaperBehaviorObservationListener` on the event/region thread. Only immutable values enter the generic state. No cross-region access, async Bukkit access, scheduler assumption, disk I/O, network access, world scan, or blocking operation is used. State is bounded to counters, the previous snapshot, and one pending fall record.

## Configuration and enforcement

Configuration is intentionally small:

```yaml
behavior-detection:
  movement:
    enabled: true
    no-fall:
      enabled: true
      mode: observe
```

P8 is explicitly observe-only. No NoFall signal calls `EnforcementService`, kicks, bans, commands, or disconnect operations.

## Limitations

Paper and Folia live validation were not available. A controlled NoFall client positive is therefore `UNVERIFIED`. The expected-impact model is intentionally conservative and may remain uncertain for custom blocks, plugin movement, high latency, TPS degradation, unusual collision geometry, or damage behavior not visible through the standard fall event.
