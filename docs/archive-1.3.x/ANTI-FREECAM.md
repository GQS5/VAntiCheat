# Anti-Freecam

Behavior-based camera/body divergence detection plus server-authoritative
exposure prevention. No client fingerprinting: renamed, modified, or
timing-altered Freecam clients are caught by what they *do*, not what they
*claim to be*.

## Architecture

```
PacketEvents (inbound movement/rotation, outbound world data)
  → AntiFreecamPackets      (normalize only: zero Bukkit, fail-open)
  → Player State            (BodySnapshot region-written / Netty-read)
  → DivergenceAnalyzer      (rotation-without-movement, pure logic)
  → Sightline               (region-thread raycast + reach validation)
  → ExposureGuard           (depth shadow + distance gate on outbound)
  → Evidence                (violations → confidence → evidence → punishment)
```

Components (`com.hexa.vanticheat.antifreecam`):

| Class | Role | Thread |
|---|---|---|
| `AntiFreecamManager` | Facade: body truth, validation, evidence, lifecycle | region |
| `AntiFreecamPackets` | Single PacketEvents listener (in + out) | Netty |
| `BodySnapshot` / `CameraObservation` | Immutable body / mutable observation | both (lock-free) |
| `DivergenceAnalyzer` | Multi-signal scoring, no Bukkit | any |
| `Sightline` | Bounded raycast + reach check | region only |
| `ExposureGuard` | Outbound information withholding | Netty |
| `AntiFreecamConfig` | Small flat snapshot of `anti-freecam:` | load-time |

## Prevention model (exposure-based, learned from a live regression)

FPAntiFreeCam hides everything below a static `voidY`. The first version of
this system hid tile entities below a body-relative plane — and live QA
proved that wrong: stripped tile data never healed (updates were suppressed
too), producing "invisible until touched" chests. Body-distance proxies for
*world data* are therefore gone. The corrected rule:

- **World data is server-authorized.** Chunk loading is body-driven, so every
  emitted chunk/block/tile-entity/signal packet is already part of the body's
  legitimate world state. The guard never withholds it (except opt-in
  `shadow-chunks` paranoia mode, which trades movement integrity for hiding).
- **Entities are gated** (no rendering coupling — hiding an entity cannot
  create holes or invisible walls):
  - below the depth shadow (`prevention.depth-margin`, default 10): spawns,
    metadata, entity-bound sounds withheld;
  - beyond the distance gate (`prevention.distance-limit`, default 64):
    non-player spawns withheld, blinding ESP/radar through a detached camera.
- **Player entities are never suppressed** (legitimate long-distance
  rendering must keep working; player ESP is Anti-ESP's job).

Deliberately NOT ported from FPAntiFreeCam: the anvil translation-key probe
(client-signature detection — bypassed by renaming the mod) and the static
floor (breaks none of our cases but is less precise than body-relative).

## Detection model

Independent signals, evidence only when `detection.min-signals` (default 2)
corroborate:

- `ROTATION_WITHOUT_MOVEMENT` — sustained look change with no position change
  inside `rotate-window-ms` (detached camera). Position packets reset it, so
  walking/elytra/PvP never accumulate.
- `REACH_VIOLATION` — interaction target beyond `interact-reach` from the
  body eye. Blatant cases (`reach + 3`) or corroborated cases are cancelled.
- `SIGHTLINE_VIOLATION` — target in reach but occluded (bounded raycast).
  Recorded, never used to cancel (avoids wall-edge false positives).

Exemptions (no duplicate system — uses `VConfig.exempt`, gamemode/vehicle
state, and grace timestamps): spectator, vehicle, teleport/respawn/death/
world-change grace (3 s), ops/bypass permission.

## Configuration (`anti-freecam:`)

See `config.yml`. Small on purpose: `enabled`, `prevention.*`,
`detection.*`, `evidence-weight`, `debug`, `performance.*`. Migration v5
carries the old `freecam.enabled` master switch forward; all other old keys
are ignored.

## Operations

- `/vac status` — module row; `/vac diagnostics` — guard counters
  (`evaluated/suppressed/shadowedBlocks/tracked`).
- `/vac debug freecam <player>` — body truth, packet observation, look
  accumulation, signals, confidence. Rate-limited by design (on demand).
- `anti-freecam.debug: true` — throttled suppression trace to console.

## Camera forcing: tested, ineffective, not shipped

Live QA (isolated harness, real Freecam client): a single server Camera
packet, repeated spam (5 and 20 sends), and re-detach attempts all produced
zero visible effect — the mod reasserts its local camera every client tick
and ignores the packet completely (result C). Vanilla clients are unaffected
by the packet (no flicker). Conclusion: no server packet can force a
client-local camera; no camera lock is implemented, and none is claimed.
The active consequence path is persistence-gated evidence → the existing
punishment pipeline (kick per `forbidden_mod` policy).

## Detection: persistence-gated consequence

A lone look-sweep never triggers evidence (min-signals rule). Sustained
across `detection.persistence-windows` consecutive evaluations (~5s each,
default 6 ≈ 30s of frozen body + 180°+ sweeps) it becomes its own
corroboration and emits evidence. Legitimate play cannot hold that pattern:
walking/elytra/PvP move the body (resets accumulation), teleports and grace
reset the streak, spectator/vehicle/exempt skip evaluation.

## Known limitation (protocol, not a bug)

The server cannot unsend terrain the client already holds around the body,
and it must not break legitimate chunk rendering. Prevention withholds
*entity* information outside body context (deep/distant spawns, metadata,
entity sounds); chest *boxes* and terrain inside body-loaded chunks still
render at a detached camera, because that data is body-authorized —
withholding it would break vanilla for legitimate players (proven live).
Detection (camera-sweep signals) + interaction cancellation cover what
prevention structurally cannot: the camera may look, but out-of-reach use is
blocked and evidenced.
