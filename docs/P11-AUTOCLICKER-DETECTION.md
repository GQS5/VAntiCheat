# P11 AutoClicker Detection

P11 is an observe-only detector for suspicious observable attack cadence. **The detector identifies suspicious observable attack/input cadence; it does not directly observe the physical mouse.** An attack or damage event is not treated as an exact physical click count, CPS value, or mouse timestamp.

## Observation model

The existing Paper adapter observes `EntityDamageByEntityEvent` when a player damages an entity. It snapshots the player and target UUIDs, positions, target bounds, line of sight, attack interval derived from the existing per-player event timestamp, cooled attack strength, movement flags, knockback context, and target context into `CombatObservation`. No second combat listener, swing listener, or attack history is created. The current platform does not provide a reliable independent arm-swing stream, so swing-to-attack correlation is not claimed.

## Analysis

The detector analyzes the existing bounded combat observation window. It uses a small recent interval window for mean interval, variance, repeated interval count, cadence, periodicity, and burst length. Regularity is supporting evidence only; the detector does not assume that humans are random or that periodic input proves automation. Same-target and target-switch context, attack strength, and combat transitions are recorded as context rather than treated as violations.

Server tick quantization is explicitly recognized. Intervals predominantly aligned to 50 ms are marked `PARTIAL` timing quality and cannot by themselves reach suspicious confidence. Same-tick samples, uncertain combat observations, very short resolution, and large gaps reduce or reset confidence. This avoids manufacturing precision from latency, jitter, delayed packets, or low/irregular server timing.

## Confidence and evidence

The detector emits `CLEAR` implicitly by producing no evidence, then `UNCERTAIN`, `SUSPICIOUS`, or `STRONG_SIGNAL` as a persistent pattern supports it. Strong output requires reliable non-quantized timing, persistence, multiple regularity/cadence features, and a bounded sample count. Evidence is immutable and compact: sample count, mean and variance, repeated intervals, burst length, periodicity, cadence, timing quality, signal types, target context, and cooldown context.

## Safety and limits

State is bounded per player and runs synchronously at the observation boundary. It performs no disk I/O, network access, blocking work, or expensive large-scale statistics. The adapter passes immutable values into the detector and keeps Bukkit access on the event thread, preserving the existing Paper/Folia region-event model. Player lifecycle cleanup continues through the existing registry removal path.

Fast legitimate clicking, drag/butterfly/jitter clicking, rhythmic clicking, gaming mice, allowed macros, attack cooldown behavior, target switching, latency, and server timing can all produce regular-looking observations. The detector therefore produces evidence for review and does not decide server policy.

Configuration is intentionally minimal:

```yaml
behavior-detection:
  combat:
    autoclicker:
      enabled: true
      mode: observe
```

Trusted Players are not consulted by this detector. Trusted players continue to produce observations and evidence; trust remains an enforcement-policy decision. P11 has no kick, ban, command, or `EnforcementService` path.
