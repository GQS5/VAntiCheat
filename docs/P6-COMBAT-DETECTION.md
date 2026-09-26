# P6 Combat Detection

## Status and scope

P6 adds a conservative, observe-only KillAura-oriented combat detector. It analyzes server-observable attack sequences; it does not identify clients and it does not call `EnforcementService`.

## Architecture

`PaperBehaviorObservationListener` remains the only combat event adapter. On the region thread it captures immutable `CombatObservation` values containing attacker and target geometry, line of sight, attack interval, attack cooldown strength, movement context, and knockback context. The platform-neutral implementation is under `detection/behavior/combat`.

`PlayerBehaviorSession` owns one bounded `CombatState`. The state stores compact derived attack sequences and recent Reach outcomes rather than creating a second unbounded player history. `BehaviorRegistry.remove` and `stop` clear it through the existing session lifecycle.

## Sequence model

Each attack sequence entry contains a bounded sequence index, target UUID, interval, rotation delta immediately preceding the attack, and signal categories. The detector considers:

- `TIMING`: a short attack interval while the server reports a sufficiently charged attack; timing is supporting evidence, not a CPS rule.
- `TARGET_SWITCH`: a rapid change from the previous target; switching alone cannot produce a strong result.
- `ROTATION`: a meaningful rotation immediately before an attack; large rotation alone cannot produce a strong result.
- `GEOMETRY` and `REACH`: the existing Reach module's result is consumed as supporting evidence, without duplicating its thresholds.

The recent sequence is bounded to at most 32 derived attacks. The normal behavior observation windows remain bounded by the P5 registry capacity.

## Confidence

The detector emits compact `CombatEvidence` only for non-clear outcomes:

- `CLEAR`: no current combat anomaly.
- `UNCERTAIN`: one weak anomaly or a sequence affected by movement, knockback, missing line of sight, nearby-target ambiguity, or an explicitly uncertain snapshot.
- `SUSPICIOUS`: at least two related categories with repeated supporting observations.
- `STRONG_SIGNAL`: at least three independent categories, repeated across a longer sequence, without an uncertainty condition.

Evidence retains detector, timestamp, attacker and target UUIDs, interval, measured distance, rotation delta, sequence index, category set, confidence, and compact repetition context. Normal attacks are not written to disk.

## Server conditions and limitations

The detector does not infer ping or client-side aim. It marks observations uncertain when the adapter reports line-of-sight ambiguity, movement, knockback, nearby-target ambiguity, or explicit uncertainty. Attack cooldown strength comes from Paper's server-observable `getCooledAttackStrength(0.0f)` value. Tick boundaries and latency can still affect event timing, so these are evidence signals rather than enforcement decisions.

The current adapter handles direct player damage events. Projectile, multi-region target acquisition, and a validated latency model are intentionally out of scope.

## Folia safety and performance

No live Bukkit object leaves the event boundary. Generic combat classes import no Bukkit, Paper, Folia, or NMS classes. Processing remains synchronous on the event/region thread and uses bounded deques, compact records, and near-O(1) insertion. There is no disk I/O, network I/O, world scan, database access, or blocking operation in the combat path.

## Enforcement

P6 is explicitly observe-only. Signals and evidence are sent to the existing behavior sink for logging/inspection only. There is no combat-signal path to kicking, banning, muting, disconnecting, commands, or `EnforcementService`.

## Validation limitations

Unit tests cover timing, target switching, rotation correlation, Reach integration, uncertainty, bounded history, and lifecycle isolation. Paper and Folia live combat validation requires a running server and is therefore `UNVERIFIED` in this workspace. A real KillAura positive is also `UNVERIFIED`; no positive result is manufactured.
