# VAntiCheat 0.2.0

Release candidate. This release consolidates the P14–P31 rework into a single
artifact and is internally consistent, tested, and packaged for Paper and
Folia 1.21.11 on Java 21.

**This release is not a claim of complete live cheat-client coverage.** Only 3
of 38 probe definitions carry real-client verification evidence.

## Breaking changes

- **Behavior detection removed.** The `behavior-detection.yml` configuration,
  behavior module API, and behavior detection categories no longer exist.
  Existing `behavior-detection.yml` files are ignored rather than loaded.
  Detection is now exclusively configuration-driven client/mod identity.
- Verification metadata is now required for `VERIFIED` probes: a definition
  with `verification: VERIFIED` and no `notes`/`source` is rejected at load
  and reload time instead of being accepted silently.
- Xaero's World Map and Litematica probe identifiers were corrected to keys
  that actually exist in the shipped mod artifacts.

## Probe catalog

```text
38 total
3 VERIFIED    meteor-client, apple-skin, jade-config-screen
33 UNVERIFIED no live client evidence available
2 DISABLED    xaeros-minimap, itemscroller
```

Live-verified scope, exactly as tested:

- `meteor-client` — Minecraft 1.21.11 / Meteor Client 1.21.11-86, manual and
  automatic detection, double-check confirmation, enforcement enforced exactly
  once per session.
- `apple-skin` — Minecraft 1.21.11 / AppleSkin 3.0.8, manual detection.
- `jade-config-screen` — Minecraft 1.21.11 / Jade 21.1.6, manual detection.

A `VERIFIED` probe is verified only for the recorded client/Minecraft versions.

`xaeros-minimap` stays disabled because of a known legitimate-client false
positive. `itemscroller` is disabled because its configured identifier matches
a template that was disproved for a comparable mod and no artifact was
available to confirm a real identifier; no replacement identifier was invented.

## Architecture

- Universal, configuration-driven `ProbeRegistry` is the single source of
  truth for client/mod probes. Adding a probe requires no Java changes.
- One shared evaluator and aggregator serve manual and automatic scans, so
  manual and automatic results cannot diverge.
- Result semantics are explicit and frozen: `CLEAN`, `DETECTED`, `TIMEOUT`,
  `PROTECTED`, `ERROR`, `UNSUPPORTED`, `SKIPPED`, `UNCERTAIN`. A timeout is
  never clean, an error is never a detection, and weak evidence is never
  actionable.
- Automatic join detection owns one operation per player with a coordinator
  ticket, capacity limit, trusted-player skip, reload snapshot, and
  exactly-once enforcement.

## Platform and isolation policy

- Bedrock players are excluded by platform classification before any probe
  session, transport call, sign, editor, evidence, or enforcement.
- `UNKNOWN` platform fails closed with bounded retries and no enforcement.
- `NO_PROVIDER` applies the documented Java-eligible policy.
- Lunar is an allow-policy integration through the optional Apollo plugin.
  Players are never kicked or classified as cheats for using Lunar.

## Reliability

Bounded and lifecycle-scoped state throughout: enforcement de-duplication
(capped and expiring), diagnostics history, platform cache, coordinator
tickets, transport operations, debug timelines, and integration references.
Exactly-once terminalization, cleanup, capacity release, and enforcement are
covered by deterministic tests, including scheduler refusal, Folia retirement,
disconnect, reconnect, reload, and shutdown paths.

## Performance

Sign-probe scanning is sequential by design: the client protocol allows only
one open sign editor per player, so batches are never overlapped.

- Responsive real-client scans: approximately 0.95–1.8 s in the tested
  isolated environment.
- Silent/non-responsive clients: approximately 8 s for a 28-probe automatic
  scan and 9.5–10 s for a 37-probe manual scan.

These are environment-specific measurements, not universal guarantees. After
consecutive full-window timeouts, later batches use a shorter bounded deadline
(`short-timeout-ticks`), which shortens silent-client wall time without
changing result semantics: a shortened wait is still recorded as `TIMEOUT`,
never as clean.

## Administration

- Commands: `/vac help`, `/vac status [verbose]`, `/vac detections`,
  `/vac reload`, `/vac check <player> [probe-ids]`, `/vac probes`,
  `/vac probe <probe-id>`, `/vac lunar <player>`, `/vac trust ...`, and
  `/vacprobe <player>`.
- Permissions: `vanticheat.status`, `vanticheat.reload`, `vanticheat.probe`,
  `vanticheat.admin`.
- `/vac status` and its variants are read-only: they start no scan, touch no
  world, and perform no integration action.
- `/vac probes` and `/vac probe <id>` report per-probe status as `VERIFIED`,
  `UNVERIFIED`, or `DISABLED`, plus mode, category, notes, and source.
- `/vac reload` validates before swapping; a rejected configuration leaves the
  last known-good registry active.

## Migration

- `auto-check.probes` legacy list syntax is still parsed and migrated into
  per-probe `automatic` policy.
- Existing probe `manual`/`automatic` flags, verification metadata, adaptive
  timeout keys, and disabled probes are preserved as authored.
- Files removed from the plugin are ignored, not migrated destructively.

## Known limitations

- Real-client verification covers 3 of 38 probes. 33 probes remain
  `UNVERIFIED` because no real client was available, and 2 are `DISABLED`.
- Bedrock isolation is enforced in code and unit-tested but was not
  live-validated against a real Bedrock client.
- The Lunar integration is live-verified only as available/ready with the
  Apollo plugin present; no real Lunar client was tested.
- Several client families (EMI, LiquidBounce, Wurst, BleachHack, Aristois)
  were not obtainable in the isolated environment, so their probes remain
  unverified.
- Translation-based probes without an exact expected response return
  `UNCERTAIN` with weak evidence and are never actionable, because the
  protocol carries plain strings rather than structured component identity.
- A planned further real-client expansion (P30) was intentionally skipped;
  broad client compatibility therefore remains unverified.

## Verification

- `mvn clean test` — 224 tests passing, stable across 6 complete runs.
- `mvn package` — success; thin JAR, all runtime dependencies provided.
- Paper 1.21.11, Folia 1.21.11, and Java 21 are the explicitly tested scope.

## Release artifact checksum

```text
asset:  vanticheat-0.2.0.jar
sha256: fdc731b7863f790ec316708dc29be07e27bf95abb85f1e924c8c3e0bcf3b8f73
```

## Reliability fix in this release

A concurrent cleanup race in the sign-probe transport could leave the temporary
probe sign and its barrier in the world while the operation still terminalized
normally. Paper stores a `Location`'s world in a `WeakReference`, and cleanup
re-derived its region target from that `Location` after terminalization, so a
cleared world reference aborted the restore. The region target is now captured
once during placement, while the candidate's region context is active, and
reused for cleanup. Exactly-once cleanup, exactly-once enforcement, and the
result semantics are unchanged.

