# VAntiCheat 0.2.0

Release candidate for Paper and Folia 1.21.11 on Java 21.

This release consolidates the complete P14–P31.1 rework into a single
artifact. It replaces the previous behavior-detection foundation with a
configuration-driven client/mod identity engine, adds a full reliability and
operations layer, hardens the production surface, and fixes a real concurrent
cleanup defect in the sign-probe transport.

**This release is not a claim of complete live cheat-client coverage.** Only 3
of 38 probe definitions carry real-client verification evidence.

---

## Table of contents

- [At a glance](#at-a-glance)
- [What changed and why](#what-changed-and-why)
- [Breaking changes and migration](#breaking-changes-and-migration)
- [Architecture](#architecture)
- [Result semantics](#result-semantics)
- [Evidence model](#evidence-model)
- [Platform policy](#platform-policy)
- [Automatic detection lifecycle](#automatic-detection-lifecycle)
- [Sign-probe transport and reliability](#sign-probe-transport-and-reliability)
- [Performance](#performance)
- [Probe catalog](#probe-catalog)
- [Live validation performed](#live-validation-performed)
- [Commands and permissions](#commands-and-permissions)
- [Configuration reference](#configuration-reference)
- [Security posture](#security-posture)
- [Verification and reproducibility](#verification-and-reproducibility)
- [Release artifact checksum](#release-artifact-checksum)
- [Known limitations](#known-limitations)
- [Work summary by phase](#work-summary-by-phase)

---

## At a glance

```text
artifact            vanticheat-0.2.0.jar (270747 bytes, 162 files, 136 classes)
runtime             Java 21 (bytecode major 65)
servers             Paper 1.21.11, Folia 1.21.11 (both live-validated)
Minecraft           1.21.11
probe catalog       38 definitions: 3 VERIFIED / 33 UNVERIFIED / 2 DISABLED
automatic catalog   27 enabled probes; 9 manual-only (36 enabled in total)
detection approach  sign-editor probe protocol, configuration-driven registry
optional integrations Apollo (Lunar policy), Floodgate/Geyser (platform classification)
network I/O         none
test suite          224 tests, stable across 6 complete runs
```

---

## What changed and why

### Behavior detection was removed (P14)

The previous behavior-detection foundation — movement, combat, and rotation
heuristics — was removed entirely: the behavior module API, the behavior
detection category, `behavior-detection.yml`, and the P5–P11 design documents
are gone. Detection is now **exclusively** configuration-driven client/mod
identity. This removed a large class of false positives on legitimate players
and replaced unverifiable heuristics with probes whose evidence is inspectable
and whose semantics are explicit.

### Universal config-driven probe registry (P15, P18)

All client/mod knowledge now lives in one place: `client-detection.yml`. A
probe is data (identifier, mode, policy, evidence metadata), never a
client-specific Java branch. The catalog holds 38 definitions across client,
utility, and map categories, and adding a probe requires no Java change and no
recompile.

### Bedrock isolation (P17)

Bedrock players are excluded by platform classification **before** any probe
session exists — no session, no transport operation, no sign, no editor, no
detection evidence, no enforcement. `UNKNOWN` fails closed with bounded retries.
`NO_PROVIDER` applies an explicit Java-eligible policy. This is a hard
invariant, enforced in code and covered by tests.

### Accurate evaluator and result semantics (P19)

Transport outcomes and evaluator outcomes are separated and frozen. A timeout is
never clean, an error is never a detection, weak evidence is never actionable,
and structured component identity is preserved and compared exactly rather than
approximated by text matching. Unsafe prefix/substring fallback matching was
deliberately **not** restored.

### Automatic detection lifecycle (P20)

Join-time scanning owns exactly one operation per player, with coordinator
tickets, a capacity limit, trusted-player exclusion, a configuration snapshot
per scan, reconnect protection, reload handling, shutdown handling, and
exactly-once enforcement.

### Performance work (P21, P26)

P21 removed structural overhead (no inter-batch delay scheduling, candidate
fast path, far fewer location allocations and block lookups). P26 added a
consecutive-silence backoff that shortens silent-client wall time without
changing any result. Sign editors are **never** overlapped, because the client
protocol allows only one open editor per player.

### Reliability and fault engineering (P16, P24–P24.3)

Every asynchronous path was given an explicit failure story: scheduler refusal,
Folia retirement, disconnect mid-operation, reconnect, reload during an active
scan, and shutdown. All persistent state is bounded, lifecycle-scoped, expiring,
or explicitly cleared.

### Administration and observability (P23)

A complete `/vac` command family with per-probe status, runtime health, bounded
scan history, and read-only diagnostics. Status output distinguishes
`VERIFIED`, `UNVERIFIED`, and `DISABLED` and never claims live verification for
a probe that lacks repository-backed evidence.

### Lunar/Apollo hardening (P22)

Lunar is an allow-policy integration through the optional Apollo plugin. It is
optional, isolated from the probe path, Folia-safe, idempotent, and safe across
absent / not-ready / failed / disabled / shutdown / stale-player states. Players
are never kicked or classified as cheats for using Lunar.

### Production hardening and packaging (P27, P29, P31)

Dead code removed, resource filtering and plugin metadata corrected, runtime
data excluded from version control, catalog consistency enforced by tests, and
`VERIFIED` made impossible to claim without evidence metadata.

### Cleanup race fixed (P31.1)

A real concurrent defect was found and fixed. Paper stores a `Location`'s world
in a `WeakReference`, and the transport re-derived its cleanup region target
from that `Location` **after** the operation had terminalized. A cleared world
reference made `getWorld()` throw `IllegalArgumentException("World unloaded")`,
which aborted the restore and left the temporary probe sign and its barrier in
the world while the session still completed normally. The region target is now
captured once during placement, while the candidate's region context is active,
and reused for cleanup. Verified with forced-GC stress runs: 1200 and 4000
iterations, zero restore misses, zero leaked operations.

---

## Breaking changes and migration

1. **Behavior detection removed.** `behavior-detection.yml` and all behavior
   configuration are ignored, not migrated. Remove the file if you like; nothing
   reads it.
2. **`VERIFIED` now requires evidence metadata.** A probe declared
   `verification: VERIFIED` without non-blank `notes` and `source` is rejected
   at load **and** reload time. Previously it was accepted silently.
3. **Corrected probe identifiers.** `xaeros-worldmap` and `litematica` used
   translation keys that do not exist in the shipped mod artifacts and were
   replaced with real keys taken from the mod jars.
4. **Disabled probes.** `xaeros-minimap` (known legitimate-client false
   positive) and `itemscroller` (identifier could not be confirmed) ship
   disabled. They remain visible in `/vac probes` with their reasons.

### Migration compatibility

- The legacy `auto-check.probes` list syntax is still parsed and migrated into
  per-probe `automatic` policy.
- Existing `manual`, `automatic`, `verification`, `category`, `source`, and
  `notes` fields are preserved as authored.
- New adaptive-timeout keys default safely when absent
  (`short-timeout-ticks` falls back to `timeout-ticks`, which disables the
  backoff).
- Reload is pre-validated: a rejected configuration leaves the last known-good
  registry active. There is no silent destructive migration.

---

## Architecture

81 production classes, no behavior-detection remnants, no client-specific
detection branches.

```text
VAntiCheatPlugin          entry point, wiring, reload, shutdown
core/                     engine lifecycle and readiness
config/                   config parsing/validation, messages, enforcement config
detection/                generic detection session/result/evidence model
detection/probe/          ProbeRegistry, evaluator, aggregator, diagnostics, transport contract
platform/                 scheduler abstraction, platform classification, automatic coordinator
platform/paper/           Paper/Folia transport, packets, commands, listeners
platform/lunar/           optional Apollo bridge
enforcement/              confirmed-detection policy, executor, bounded de-duplication
trusted/                  persistent trusted-player store
lunar/                    Lunar/Apollo policy and player state
diagnostics/              runtime health snapshot for /vac status
```

Key structural properties:

- **One registry.** `ProbeRegistry` is the single source of truth for probe
  definitions. There is no duplicate registry, evaluator, or aggregator.
- **One evaluator.** Manual and automatic scans share the same evaluator and
  aggregator, so they cannot diverge.
- **One scheduler abstraction.** All scheduling goes through `Scheduler` with
  entity, region, and global contexts. There are no direct Bukkit scheduler
  calls outside the Paper/Folia implementation.
- **No network I/O.** The plugin never makes an outbound connection. Optional
  integrations are discovered through the plugin manager and fail safe when
  absent.

---

## Result semantics

| Status | Meaning | Enforceable |
| --- | --- | --- |
| `CLEAN` | The client answered and the response does not match the probe | no |
| `DETECTED` | Exact structured identity or exact configured response matched | yes, after confirmation |
| `UNCERTAIN` | Response arrived but was ambiguous localized text (weak evidence) | no |
| `TIMEOUT` | No answer within the bounded deadline — **not** clean, **not** detected | no |
| `UNSUPPORTED` | The transport cannot carry this probe | no |
| `PROTECTED` | The probe was answered but neutralized/blocked | no |
| `ERROR` | Transport, scheduling, or processing failure — **never** a detection | no |
| `SKIPPED` | Policy/eligibility stop (trusted, Bedrock, disabled, reloaded away) | no |

Rules that are enforced by tests:

- `TIMEOUT != CLEAN` and `TIMEOUT != DETECTED`.
- `ERROR != DETECTED`.
- `UNCERTAIN != DETECTED`; weak evidence is never actionable.
- `SKIPPED` produces no detection evidence.
- Only `DETECTED` and `PROTECTED` can require a confirmation pass.
- Only targeted confirmation reaches enforcement.

---

## Evidence model

Two independent concepts, deliberately not merged:

**Verification state** — how well the probe definition itself is validated:

```text
VERIFIED    repeatable real-client detection with clean separation, on the recorded versions
UNVERIFIED  no live evidence available
DISABLED    intentionally not executed (visible in /vac probes with the reason)
```

**Runtime evidence strength** — how strong one scan's response was:

```text
STRONG  exact identity or exact configured response matched
WEAK    ambiguous localized text only
NONE    no meaningful evidence
```

A runtime `DETECTED` is one scan's outcome. A `VERIFIED` probe is a validation
state. Neither implies the other, and a `VERIFIED` probe is verified only for
the exact client and Minecraft versions recorded in its notes.

Guardrail: `verification: VERIFIED` without non-blank `notes` and `source` is a
configuration error and is rejected at load and reload.

---

## Platform policy

| Classification | Policy |
| --- | --- |
| `JAVA` | Probes are eligible |
| `NO_PROVIDER` | Probes are eligible (documented Java policy) |
| `BEDROCK` | **Zero** probes: no session, no transport, no sign, no editor, no evidence, no enforcement |
| `UNKNOWN` | Fail closed: no probe, bounded retries, no enforcement |

Bedrock exclusion happens before probe selection, so no Bedrock-visible UI can
occur. Bedrock isolation is enforced in code and covered by tests, but it has
**not** been live-validated against a real Bedrock client (no Bedrock device and
no Geyser/Floodgate in the test environment).

---

## Automatic detection lifecycle

```text
join
 → platform classification (Bedrock/UNKNOWN excluded)
 → trusted-player check
 → duplicate-operation guard (one per UUID)
 → coordinator ticket + capacity limit (max-concurrent, default 32)
 → delay (auto-check.delay-ticks, default 20)
 → configuration snapshot for this scan
 → probe batches (3 probes per sign editor)
 → aggregation
 → confirmation pass for flagged probes (double-check)
 → targeted enforcement, exactly once
 → completion, capacity release, cleanup
```

Invariants: one active operation per player; a configuration snapshot that
reload cannot mutate mid-scan; exactly-once terminalization; exactly-once
capacity release; exactly-once enforcement; no work after a terminal state.

---

## Sign-probe transport and reliability

A probe batch is delivered through a temporary sign above the player: sign
update → block-entity packet → open-editor packet, in that enforced order, with
a one-tick boundary between block-entity and open-editor. The client answers by
editing the sign; the server reads the resulting `SignChangeEvent`, extracts
both the plain text and the structured component identity, and hands them to the
shared evaluator.

Reliability properties, all covered by deterministic tests:

- one outstanding sign operation per player (`putIfAbsent` operation identity);
- response accepted only for the owning session and player;
- terminal claim guarantees at most one terminal outcome and at most one
  cleanup schedule;
- timeout handle cancelled on any terminal path, including a terminal state
  reached before the handle was assigned;
- cleanup always attempted, restoring the captured block state and removing the
  temporary barrier, inside the owning region context;
- scheduler refusal, Folia entity/region retirement, plugin disable, and
  shutdown all end in a controlled terminal state with the callback fired
  exactly once;
- no post-terminal world mutation.

### P31.1 cleanup fix

Root cause: `org.bukkit.Location` keeps its world in a `WeakReference<World>`,
and `getWorld()` throws `IllegalArgumentException("World unloaded")` once that
reference is cleared. Cleanup used to rebuild its `RegionTarget` from the
`Location` after terminalization, so a cleared reference skipped the restore
and stranded the sign and barrier in the world.

Fix: the `RegionTarget` is captured once during placement, inside the valid
region context, and stored operation-scoped on the operation. Cleanup reuses
that exact target and never derives a world from a `Location` after
terminalization. No scheduler fallback, no global cache, no unbounded state, and
cleanup failures never alter the authoritative primary result. Four deterministic
regression tests were added; both new cleanup-target tests were proven to fail
against the pre-fix code.

---

## Performance

Scanning is sequential by design. The client protocol permits only one open sign
editor per player, so overlapping batches would orphan responses. Each batch
therefore waits for its own bounded response deadline.

Measured in the isolated test environment (Java 21, Minecraft 1.21.11, localhost,
protocol-level and real clients). **These are environment-specific observations,
not universal guarantees.**

| Scan | Probes | Before P26 | After P26 |
| --- | --- | --- | --- |
| Responsive automatic | 28 | ~0.95–1.8 s | unchanged |
| Silent automatic | 28 | ~20 s | **~8 s** |
| Silent manual | 37 | ~26 s | **~9.5–10 s** |

The adaptive timeout (`short-timeout-ticks`, default 10 ticks after
`short-timeout-after-consecutive-timeouts` full-window timeouts, default 2)
shortens silent-client wall time while preserving semantics exactly: a shortened
wait is still recorded as `TIMEOUT`, never as clean. Any response resets the
streak, and confirmation passes always start with the full deadline, so
responsive clients never reach the short deadline.

The 1–3 second range is **not** claimed universally. A fully silent client still
takes several seconds for a large probe set because overlapping sign editors is
intentionally avoided.

---

## Probe catalog

```text
38 total
3 VERIFIED
33 UNVERIFIED
2 DISABLED
36 enabled (all 36 are manual-eligible, 27 of them also automatic)
```

### VERIFIED (3) — real-client evidence

| Probe | Target | Version | Evidence |
| --- | --- | --- | --- |
| `meteor-client` | Meteor Client | 1.21.11-86 | 3 automatic + 1 manual `DETECTED` (STRONG, double-check confirmed, enforcement fired exactly once per session); 6 clean baselines (3 vanilla + 3 clean Fabric, 28 `CLEAN` each); 0 cross-probe collisions |
| `apple-skin` | AppleSkin | 3.0.8 | 3 manual `DETECTED` (STRONG, double-check confirmed); 3 vanilla manual `CLEAN` |
| `jade-config-screen` | Jade | 21.1.6 | 3 manual `DETECTED` (STRONG, double-check confirmed); 3 vanilla manual `CLEAN` |

All three were verified on Minecraft 1.21.11. A `VERIFIED` probe is verified
only for the recorded client and Minecraft versions.

### DISABLED (2)

| Probe | Reason |
| --- | --- |
| `xaeros-minimap` | Known legitimate-client false positive |
| `itemscroller` | Configured identifier matched a template disproved for a comparable mod; no artifact available to confirm a real identifier, and no replacement was invented |

### UNVERIFIED (33) — no live evidence, still configurable

These remain enabled or manual-only and are honestly labelled. Many are
translation probes that return ambiguous localized text and therefore ceiling at
`UNCERTAIN`; that is a deliberate correctness choice, not a defect. Observed
live behaviour: Xaero's World Map, Litematica, Inventory Profiles Next, and Jade
Config all separated target from baseline (`UNCERTAIN` vs `CLEAN`) but could not
reach `DETECTED`, because the protocol carries plain strings rather than
structured component identity.

Automatic policy rationale: only probes with a clean false-positive record are
eligible for join-time scanning. Detectable-manually does not imply
appropriate-automatically, so utility-mod detections (AppleSkin, Jade, EMI, IPN)
stay manual-only, and weak probes are retained because they cost only bounded
backoff time and never enforce.

---

## Live validation performed

Isolated Paper 1.21.11 and Folia 1.21.11 servers on localhost, real Minecraft
clients driven with a purpose-built offline launcher, and the plugin's own
diagnostics.

**Clients exercised:** Vanilla 1.21.11 (3 runs), clean Fabric Loader 0.19.3
(3 runs), Meteor Client 1.21.11-86, Xaero's Minimap 26.5.0, Xaero's World Map
1.46.0, Litematica 0.26.11 (+ malilib 0.27.16), Inventory Profiles Next 2.2.6
(+ libIPN 6.6.3), AppleSkin 3.0.8, Jade 21.1.6.

**Not obtainable in the isolated environment:** EMI (no 1.21.11 release),
LiquidBounce, Wurst, BleachHack, Aristois, Lunar, and Bedrock/Geyser (no device,
no Geyser). Their probes remain `UNVERIFIED`; none were fabricated.

Also validated live: manual/automatic parity, exactly-once enforcement, reload
during an active scan, disconnect mid-scan, immediate reconnect, trusted-player
exclusion, platform classification, and cleanup/world integrity. Zero false
positives were observed on vanilla and clean Fabric baselines across all runs.

---

## Commands and permissions

| Command | Permission | Purpose |
| --- | --- | --- |
| `/vac help` | `vanticheat.admin` | Command overview |
| `/vac status [verbose]` | `vanticheat.status` | Runtime health, cached counters, provider and integration state |
| `/vac detections` | `vanticheat.status` | Recent confirmed detections |
| `/vac reload` | `vanticheat.reload` | Pre-validated atomic configuration reload |
| `/vac check <player> [probe-ids]` | `vanticheat.probe` | Run a manual probe scan |
| `/vac probes` | `vanticheat.admin` | Configured probes with per-probe status |
| `/vac probe <probe-id>` | `vanticheat.admin` | Full probe detail, notes, source |
| `/vac lunar <player>` | `vanticheat.admin` | Lunar/Apollo policy state |
| `/vac trust add\|remove\|list` | `vanticheat.admin` | Trusted-player management |
| `/vacprobe <player>` | `vanticheat.probe` | Run configured manual probes |

`/vac status` and its variants are strictly read-only: they start no scan, touch
no world, and perform no integration action. Tab completion is
permission-filtered. All four permissions are declared in `plugin.yml` with
descriptions and default to operator.

---

## Configuration reference

### `config.yml`

```yaml
vanticheat.enabled           # master switch
vanticheat.debug             # verbose probe timeline logging (default false)
detection.enabled            # detection engine switch
enforcement.enabled          # confirmed-detection enforcement
enforcement.confirmed-detection.message
lunar.enabled                # Lunar allow-policy
lunar.minimap.enabled        # disable the Lunar Minimap via Apollo override
lunar.minimap.action         # DISABLE
```

### `client-detection.yml`

```yaml
client-detection.enabled
client-detection.double-check            # confirmation pass for flagged probes
client-detection.timeout-ticks           # full response deadline (default 40 ≈ 2 s)
client-detection.short-timeout-ticks     # silent-client deadline (default 10)
client-detection.short-timeout-after-consecutive-timeouts   # default 2
client-detection.between-probe-ticks     # inter-batch delay (default 0, inline)
client-detection.auto-check.on-join
client-detection.auto-check.delay-ticks
client-detection.auto-check.first-join-only
client-detection.auto-check.max-concurrent
client-detection.probes.<id>.display-name
client-detection.probes.<id>.key
client-detection.probes.<id>.mode        # TRANSLATE | KEYBIND | METEOR
client-detection.probes.<id>.fallback
client-detection.probes.<id>.expected-response
client-detection.probes.<id>.enabled
client-detection.probes.<id>.manual
client-detection.probes.<id>.automatic
client-detection.probes.<id>.verification
client-detection.probes.<id>.category
client-detection.probes.<id>.source
client-detection.probes.<id>.notes
```

Invalid values are rejected with file-scoped errors naming the exact path,
expected type, and actual value. Reload never replaces a live configuration with
an invalid one.

---

## Security posture

```text
network I/O                 none — no telemetry, no outbound connections
embedded secrets            none
path traversal              none — fixed filenames under the plugin data folder
unsafe input paths          none — probe ids validated by pattern
unsafe reflection           none — all reflection sites fail safe to null/FAILED
blocking hot-path calls     none
command injection           none — no shell or process execution
```

Optional integrations (Apollo, Floodgate, Geyser) are declared as `softdepend`
and are resolved through the plugin manager; absence degrades to a documented
state rather than an error. All five reflection sites (Apollo bridge loader,
Folia platform detection, NMS packet access, platform providers) catch
`LinkageError`/`ReflectiveOperationException` and degrade safely.

---

## Verification and reproducibility

```bash
mvn clean test     # 224 tests, 0 failures/errors/skipped
mvn package        # thin JAR, all runtime dependencies provided
git diff --check   # clean
```

The suite is stable: 6 complete `mvn clean test` runs were executed with zero
failures after the P31.1 fix, versus a ~3 % intermittent class-level failure
before it. Coverage includes Bedrock zero-probe, UNKNOWN fail-closed, P19 result
semantics for every status, P20 lifecycle, P21 packet ordering, P22 Apollo
isolation, P23 diagnostics, P24 reliability (exactly-once terminalization,
cleanup, capacity release, enforcement, no post-terminal work, no stale
reconnect effects, no post-shutdown mutation), P26 adaptive timeout, and P29
verification guardrails.

Build reproducibility: no local file dependencies, no `system`-scoped
dependencies, no environment variables, no generated-source prerequisites, and
no test or benchmark code in the shipped artifact.

---

## Release artifact checksum

```text
asset:  vanticheat-0.2.0.jar
size:   270747 bytes
sha256: fdc731b7863f790ec316708dc29be07e27bf95abb85f1e924c8c3e0bcf3b8f73
```

The checksum was computed from the exact uploaded artifact and re-verified by
downloading the published asset back from GitHub.

---

## Known limitations

- Only 3 of 38 probes have real-client verification evidence. 33 remain
  `UNVERIFIED` and 2 are `DISABLED`.
- Bedrock isolation is enforced in code and unit-tested but was **not**
  live-validated against a real Bedrock client.
- The Lunar integration was live-verified as available/ready with Apollo
  present; **no real Lunar client** was tested.
- EMI, LiquidBounce, Wurst, BleachHack, and Aristois were not obtainable in the
  isolated environment, so their probes remain unverified.
- Translation probes without an exact expected response return `UNCERTAIN` with
  weak evidence and are never actionable, because the protocol carries plain
  strings rather than structured component identity.
- A planned further real-client expansion was intentionally skipped, so broad
  client compatibility remains unverified.
- Performance figures are environment-specific and must not be generalized to
  production server load.

---

## Work summary by phase

| Phase | Outcome |
| --- | --- |
| P14 | Behavior detection removed entirely; detection is now client/mod identity only |
| P15 | Universal config-driven `ProbeRegistry` as the single source of truth |
| P16 | Probe lifecycle and performance hardening |
| P17 | Bedrock isolation, UNKNOWN fail-closed, NO_PROVIDER policy |
| P18 | Catalog expanded to 38 definitions |
| P19 | Evaluator and result-semantic hardening (frozen semantics) |
| P20 | Automatic detection lifecycle (tickets, capacity, trust, exactly-once) |
| P21 | Structural performance optimization (ordering, candidate fast path) |
| P22 | Lunar/Apollo optional, isolated, Folia-safe hardening |
| P23 | Administration and observability (`/vac` family, diagnostics) |
| P24–P24.3 | Reliability and fault engineering; deterministic closure |
| P25 | First isolated Paper/Folia live validation |
| P26 | Safe live latency optimization (consecutive-silence backoff) |
| P27 | Production hardening and packaging readiness |
| P28 | Real-client verification (partial): 3 probes verified with repeatable evidence |
| P29 | Probe catalog and evidence cleanup; guardrails against false verification claims |
| P30 | Intentionally skipped (further real-client expansion) |
| P31 | Release-candidate hardening, dead-code removal, packaging hygiene |
| P31.1 | Concurrent cleanup race fixed and stress-verified |
