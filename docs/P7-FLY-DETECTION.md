# P7 Fly Detection

## Scope

P7 adds a conservative, observe-only Fly detector. It analyzes server-observable movement sequences and never calls `EnforcementService`.

## Architecture

`FlyDetector` consumes the existing immutable `MovementObservation` stream through `BehaviorRegistry`. No second movement listener or unbounded movement history was added. Each existing `PlayerBehaviorSession` owns one compact bounded `FlyState`; normal movement remains in the P5 `ObservationWindow`.

The generic detector package contains no Bukkit, Paper, Folia, or NMS imports. `PaperBehaviorObservationListener` captures immutable values on the region/event thread and submits them synchronously.

## State model

The derived state distinguishes `GROUNDED`, `RISING`, `FALLING`, `AIRBORNE`, `LANDING`, `SPECIAL_MOVEMENT`, and `UNCERTAIN`. State transitions use consecutive observations, vertical deltas, velocity, ground transitions, and elapsed airborne time. A single airborne observation cannot produce Fly evidence.

## Analysis

The bounded state tracks only counters and the previous immutable observation:

- Air time is measured from a grounded-to-airborne transition and is anomalous only after a conservative duration.
- Hover requires repeated near-zero vertical deltas and near-zero vertical velocity while airborne.
- Vertical trajectory evidence requires repeated rising-after-falling reversals.
- Horizontal air movement requires repeated airborne speed above the conservative supporting threshold and is never treated as Speed detection.
- Ground contact clears airborne counters; ordinary jump/fall/landing sequences therefore remain clear.

The detector reports only when a meaningful confidence level changes, preventing normal movement and sustained anomalies from flooding logs.

## Legitimate movement protection

The Paper adapter captures fluid, climbable, slime/honey, ice, vehicle, gliding, special game mode, active effect, and external velocity context. These contexts suspend Fly analysis and rebuild state. Teleport, respawn, world change, vehicle enter/exit, quit, and registry shutdown clear player state. Delayed movement observations over 250 ms are treated as timing uncertainty and reset the derived trajectory.

The detector does not claim to know client ping, plugin launch pads, or every server movement mechanic. When the event context is unavailable or marked uncertain, it avoids a definitive classification.

## Signals and confidence

Evidence categories are explicit: `AIR_TIME_ANOMALY`, `HOVER_ANOMALY`, `VERTICAL_TRAJECTORY`, `HORIZONTAL_AIR_MOVEMENT`, `GROUND_TRANSITION`, and `CONTEXT_CONFLICT`.

Confidence uses the existing signal vocabulary:

- `CLEAR`: no current anomaly.
- `UNCERTAIN`: one weak anomaly or an ambiguous sequence.
- `SUSPICIOUS`: repeated anomaly evidence or multiple related categories.
- `STRONG_SIGNAL`: at least three repeated independent aerial categories over a sustained period without a known special context.

`STRONG_SIGNAL` is not a confirmed cheat decision and is not connected to enforcement.

## Evidence and performance

`FlyEvidence` contains detector, timestamp, player UUID, signal categories, airborne duration, vertical and horizontal deltas, movement state, confidence, and compact context fields. Normal movement is not persisted. Processing is synchronous, allocation-light, and bounded; it performs no disk I/O, database I/O, network access, blocking work, world scan, or cross-region lookup.

## Folia safety

Live Bukkit values are read only in the event boundary. Only immutable positions, velocity values, booleans, and material-derived context enter the generic detector. No asynchronous Bukkit access or global scheduler assumption is used.

## Limitations

Paper and Folia live movement validation were not available in this workspace. A controlled Fly client positive is therefore unverified. The detector does not model all plugin-controlled movement, latency distributions, or every custom block mechanic; those cases are intentionally conservative and may remain unclassified.

Configuration is intentionally small:

```yaml
behavior-detection:
  movement:
    enabled: true
    fly:
      enabled: true
      mode: observe
```
