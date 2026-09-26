# P9 Speed Detection

## Scope

P9 adds a conservative, observe-only Speed detector. It evaluates whether horizontal movement is repeatedly inconsistent with legitimate movement capabilities and surrounding server context. A single fast sample is never treated as proof.

## Architecture

`SpeedDetector` consumes the existing immutable `MovementObservation` stream through `BehaviorRegistry`. It uses the existing movement listener, P5 movement window, P7 Fly state, and lifecycle reset behavior. `PlayerBehaviorSession` stores only compact Speed counters and the previous observation; no second listener or unbounded movement history exists.

The generic Speed implementation has no Bukkit, Paper, Folia, or NMS dependency. The Paper adapter captures sprint, effect, surface, vehicle, gliding, game-mode, velocity, and timing context before submitting the immutable observation.

## Timing and rate

Horizontal rate is calculated independently of Y movement:

```text
sqrt(deltaX^2 + deltaZ^2) / elapsedSeconds
```

The detector uses the observation's measured elapsed time rather than assuming one event equals one tick. Zero, non-monotonic, delayed, correction-marked, or otherwise uncertain timing resets the Speed streak. This prevents packet batching, gaps, and tick irregularity from becoming speed evidence.

Ground and air rates are evaluated separately with conservative supporting thresholds. Sprinting uses a separate ground/air envelope. Speed and Slowness effects are captured, and active effects suspend analysis rather than being misclassified.

## Sustained analysis

The detector tracks only previous rate, acceleration, elevated streak, and a short external-velocity grace counter. Timing, ground/air category, acceleration, and repeated elevation become separate signal categories. Confidence progresses as follows:

- `CLEAR`: no current anomaly.
- `UNCERTAIN`: one elevated sample or incomplete sequence.
- `SUSPICIOUS`: repeated elevated movement.
- `STRONG_SIGNAL`: persistent elevated movement over a longer sequence with no legitimate context.

Evidence is emitted only when the confidence level changes, avoiding normal-movement log flooding.

## Legitimate movement and velocity

Fluids, climbables, slime, ice, vehicles, Elytra/gliding, special game modes, active effects, teleports, and position corrections suspend analysis. External velocity receives a bounded four-observation grace period. It is not a permanent exemption, and the grace is reset when normal movement resumes.

Sprint jumping remains in the air envelope and is not treated as Fly. Vertical movement is not used as horizontal Speed evidence. The detector consumes the existing Fly state for evidence context but does not duplicate or rewrite Fly logic.

## Evidence

`SpeedEvidence` contains detector, timestamp, player UUID, signal categories, horizontal distance, normalized rate, previous rate, acceleration, Fly movement state, ground state, elapsed time, confidence, and compact movement context. Ordinary movement is not persisted.

## Resets, Folia safety, and performance

Existing quit, teleport, respawn, world-change, vehicle, registry removal, and shutdown paths clear the session and therefore Speed state. NoFall and Fly retain their existing reset behavior.

Live Bukkit values are read only in the Paper/Folia-safe event boundary. Generic processing uses immutable snapshots synchronously on the event/region thread. There is no disk I/O, network I/O, world scan, blocking operation, cross-region access, or async Bukkit access. Per-player Speed state is constant-sized and uses O(1) updates.

## Trust integration

Speed is independent of Trusted Players. Trusted players can produce Speed signals and evidence normally. The existing centralized `EnforcementService` remains the only trust bypass boundary; Speed does not read trusted state and does not return `CLEAR` for trusted players.

## Configuration

```yaml
behavior-detection:
  movement:
    enabled: true
    speed:
      enabled: true
      mode: observe
```

Only observe mode is supported in P9.

## Limitations

Paper and Folia live movement validation were not available. A controlled Speed-positive test is therefore `UNVERIFIED`. The implementation cannot infer a complete latency/TPS model or every custom plugin launch mechanic; those situations are handled conservatively as context or timing uncertainty.
