# VAntiCheat 0.2.0

VAntiCheat is a server-side client/mod detection plugin for Paper and Folia
1.21.11. It uses configuration-driven probes, automatic join checks, manual
scans, Lunar/Apollo integration, Bedrock isolation, trusted players, and
centralized enforcement for confirmed client detections.

This release is not a claim of complete live cheat-client coverage.

## Requirements

- Minecraft server: Paper or Folia **1.21.11**
- Java **21**
- A server plugin installation; no proxy or client component is bundled

## Included Detection

Client detection supports configured translation/keybind probes, automatic join
checks, confirmation passes, bounded sessions, and enforcement for confirmed
client-detection results. 3 of 38 probe definitions are live-validated
(`VERIFIED`): Meteor Client, AppleSkin, and Jade Config Screen, each verified
on Minecraft 1.21.11 against the exact mod versions recorded in its notes.
Xaero's Minimap remains disabled, and Item Scroller is disabled because its
configured identifier matches a fabricated template with no confirmable
artifact. Xaero's World Map and Litematica probes use corrected real-mod
identifiers and remain `UNVERIFIED`: they separate targets (`UNCERTAIN`) from
baselines (`CLEAN`) but cannot produce `DETECTED`. AppleSkin and Inventory
Profiles Next have additional upstream-language-key definitions, enabled for
manual checks only; the IPN definitions remain `UNVERIFIED`.

## Automatic Detection Lifecycle

Automatic join checks use the same `ProbeRegistry`, scan engine, transport,
normalizer, evaluator, result aggregator, confirmation rules, and enforcement
policy as manual checks. The trigger differs; result interpretation does not.
The listener classifies platform first, then applies the existing trusted-player
exclusion, then admits a delayed scan from the registry's `enabled &&
automatic` snapshot. Empty automatic selection creates no session or transport
operation. Bedrock is skipped; provider-pending `UNKNOWN` is retried a bounded
number of times and then remains unprobed.

The coordinator admits at most one automatic operation per UUID and enforces
`max-concurrent`. It uses operation tickets so a late completion from an old
connection cannot release or enforce a new connection's scan. Quit, reconnect,
Bedrock reclassification, reload, and shutdown cancel their owned retries and
pending work. Join attempts rejected because the concurrency limit is full are
logged and skipped rather than queued; completion, disconnect, and shutdown
release their slots once. Active scans use the immutable configuration/registry snapshot
captured at start; a successful `/vac reload` affects new scans. Trusted players
remain excluded from automatic and manual probing under the existing policy.
`TIMEOUT` stays distinct from `PROTECTED`, is not treated as clean/detected, and
does not trigger confirmation.

Concurrency and lifecycle behavior is covered with deterministic stubs. No live
Paper/Folia client timing or client-compatibility test was performed in this
phase.

Server-observable movement and combat behavior detection is intentionally not
part of the active product.

## Client Platform Classification

Before probing, VAntiCheat classifies a UUID as `JAVA`, `BEDROCK`, or `UNKNOWN`
using optional Floodgate and Geyser APIs. With neither provider installed, the
service reports `NO_PROVIDER` and normal Java probing remains available. When a
provider is installed but not ready or cannot answer, the result remains
`UNKNOWN`; it is retried briefly for automatic checks and never reaches the
probe engine while unresolved. Bedrock classifications are excluded before
automatic/manual checks and checked again by the engine and transport, so they
cannot receive probe sign UI. Live player platform state is cached per UUID and
removed on disconnect. Pre-login inspection is uncached because later login
rejection has no quit event to release UUID state. Live Bedrock-client validation
remains pending.
For backend servers behind a proxy, Floodgate player data must be forwarded and
configured correctly by the proxy/server stack; VAntiCheat classifies only from
the installed providers' authoritative API responses.

## Lunar Client Policy

Lunar Client itself is allowed. When the official Apollo plugin recognizes a
Lunar player, VAntiCheat disables the Lunar Minimap through Apollo while the
player remains connected. This policy does not disable Xaero or arbitrary
Fabric minimap mods. Xaero Minimap remains disabled; Xaero World Map and
Litematica detection are enabled in the bundled probe policy.

## Enforcement And Trust

Confirmed client-detection results are evaluated by the centralized enforcement
service. Trusted Players are excluded from automatic and manual client probes.

Administrators can use:

```text
/vac help
/vac status
/vac status verbose
/vac reload
/vac check <player>
/vac check <player> <probe-id[,probe-id...]>
/vac probes
/vac probe <probe-id>
/vac detections
/vac lunar <player>
/vac trust add <player>
/vac trust remove <player>
/vac trust list
/vacprobe <player>
```

The console may use all administrative commands. In-game permissions are:

- `vanticheat.status` — `/vac status` and `/vac detections`
- `vanticheat.reload` — `/vac reload`
- `vanticheat.probe` — `/vac check` and `/vacprobe`
- `vanticheat.admin` — help, probe catalog/details, Lunar diagnostics, and trust management

`/vac status` is a read-only overview of engine health, active and automatic
capacity, registry policy counts, cached platform classifications, Lunar/Apollo
state, reload status, and result counters. `verbose` adds active batch details,
recent scan summaries, coordinator counters, and measured runtime scan wall-time.
Health is derived from concrete state: `HEALTHY` means the core and configured
engine are running, `DEGRADED` means client detection is unavailable or its
enabled registry is empty, and `ERROR` means the core is not running or client
configuration is unavailable. Optional Floodgate, Geyser, and Apollo absence is
reported separately and does not by itself degrade health.

Provider states distinguish `READY`, `NOT_PRESENT`, `NOT_READY`, and `FAILED`;
the aggregate provider line may also be `DEGRADED` when only one installed
provider is ready. Apollo reports `AVAILABLE`, `NOT_PRESENT`, `NOT_READY`,
`FAILED`, or `DISABLED`. Status reads cached snapshots and do not perform player,
provider, Apollo, or world lookups.

`/vac probes` summarizes the immutable active registry and lists each probe's
ID, mode, policy, verification state, and category. `/vac probe <id>` includes
its source/notes and manual/automatic eligibility. Verification metadata does
not claim live client validation.

`/vac detections` lists up to ten recent `DETECTED` scans. The engine retains at
most 100 recent completed scans and stores only player identity/name, trigger,
probe IDs, result, timestamp, and elapsed time—never raw response or packet
contents. `/vac status verbose` shows up to ten active scans with current batch
and probe IDs. `/vac reload` reports its last result, timestamp, registry
revision, concise error summary, and whether the last known-good registry
remains active.

Runtime duration is **scan wall-time** from engine admission to final result and
includes scheduling, client response waits, and confirmation. Scheduler
transition counts and isolated client latency are `N/A`; the P21 structural
benchmark is a separate mocked model and is not presented as live performance.

Trust is UUID-based and persists in
`plugins/VAntiCheat/data/trusted-players.yml`. Removing trust restores normal
enforcement for subsequent confirmed results. See
[`docs/TRUSTED-PLAYERS.md`](docs/TRUSTED-PLAYERS.md).

`/vac reload` validates and atomically replaces the active client probe registry.
Existing probe sessions finish with their original definitions; new sessions use
the replacement registry. Invalid client-detection configuration leaves the last
known-good registry active. It does not invoke Bukkit's global `/reload` command.
If client detection was unavailable at startup, `/vac reload` is rejected; fix
the startup configuration and restart VAntiCheat rather than resetting optional
integrations through an in-plugin restart.

`/vac check <player>` runs the same client probe as `/vacprobe <player>`.

## Installation

Build the exact production artifact with:

```bash
mvn clean package
```

Copy the built JAR from `target/` to the server `plugins/` directory,
then start Paper or Folia. The plugin creates or loads:

- `config.yml` for foundation, detection, enforcement, and Lunar policy settings
- `client-detection.yml` for client/mod probes and automatic join checks
- `messages.yml` for configurable command, probe, and kick messages using `&`
  colors and `%placeholders%`

Each probe supports independent `enabled`, `manual`, and `automatic` policy
fields. Manual checks use `enabled && manual`; join checks use `enabled &&
automatic`. Adding a compatible `TRANSLATE`, `KEYBIND`, or `METEOR` definition
does not require a Java change. The older `auto-check.probes` list is accepted
as a migration format only when per-probe `automatic` is absent.

Do not enable an unverified client probe solely because it exists in the
configuration. `xaeros-minimap` remains disabled. The bundled policy explicitly
enables `xaeros-worldmap` and `litematica`; live positive validation remains
unverified.

## Adding a Probe

Probe definitions live in `plugins/VAntiCheat/client-detection.yml` under the
single `probes:` mapping. Adding a compatible definition and running `/vac
reload` does not require Java changes. Use an exact client/mod translation key
or keybind identifier from its resources; do not treat an upstream key's
existence as proof of detection.

```yaml
  probes:
    example-client:
      display-name: "Example Client"
      key: "example.translation.key"
      mode: TRANSLATE
      enabled: true
      manual: true
      automatic: false
      verification: UNVERIFIED
      category: client
      source: "https://example.invalid/client/lang/en_us.json"
      notes: "Identifier source found; client response not live-verified."
```

Modes are `TRANSLATE`, `KEYBIND`, and `METEOR`. Use `TRANSLATE` for a known
translation identifier, `KEYBIND` for a known keybind identifier, and `METEOR`
only for a probe compatible with Meteor's probe response semantics. Execution
requires `enabled: true` plus `manual: true` for manual checks or
`automatic: true` for join checks. New catalog entries default to
`UNVERIFIED`; that state explicitly does not mean a live client was detected.
Keep new or uncertain probes manual-only until the exact response and false
positive risk have been assessed. `source`, `notes`, and `category` are optional
maintainer metadata displayed by `/vac probe <probe-id>`. Verification metadata
is independent of `enabled`: `VERIFIED` is reserved for exact probe evidence,
`UNVERIFIED` means no live confirmation, and `enabled: false` disables execution
regardless of verification state.

`expected-response` is an optional exact response from the selected language or
keybind mapping. Use it only when the resolved text is known; do not guess. When
the response retains a structured Component identity, the evaluator compares
that identity with the configured key. If only localized text is available and
no exact expected response is configured, ambiguous translation/keybind text is
reported as `UNCERTAIN` with `WEAK` evidence, not promoted to a confirmed
detection. `METEOR` uses exact configured identity/response matches; unrelated
responses are clean. Manual and automatic scans share the same evaluator.

## Probe Result Semantics

Transport outcomes are distinct from evaluator outcomes. A valid response is
normalized before evaluation; malformed line counts and cross-session or
cross-player responses become `ERROR`. A timeout remains `TIMEOUT`, never
`CLEAN`, and is not confirmation evidence. Disconnect/internal failures become
`ERROR`; an unanswerable protocol becomes `UNSUPPORTED`; policy/eligibility
stops become `SKIPPED`. These states do not constitute detection. `PROTECTED`
retains the existing neutralized/blocked-probe meaning. Results retain
per-probe evidence, and only targeted confirmation reaches existing enforcement.

Evidence includes categorical strength (`NONE`, `WEAK`, `STRONG`) and the
configured verification state. Verification state describes how well the probe
definition itself has been validated; runtime evidence describes how strong one
scan's response was — the two concepts are independent. An `UNVERIFIED` probe
is not represented as live-proven. P19 result semantics are covered
code-level; live real-client validation is recorded per probe in
`client-detection.yml` notes (see Probe verification status below).

Bedrock clients remain excluded by platform classification before probe
selection; the registry configuration cannot bypass the engine or transport
eligibility guards.

## Reliability and Failure Handling

Lifecycle ownership and state transitions are:

| Owner | States / transitions | Cleanup and terminal authority |
| --- | --- | --- |
| `DetectionSession` / probe module | `NEW → STARTING → RUNNING → COMPLETING → COMPLETED`; or `CANCELLED`, `TIMED_OUT`, `FAILED` | Module wins completion through synchronized state changes; disconnect/shutdown cancel transport and terminalize the session. Module removes active session/timing/callback ownership and records diagnostics. |
| Sign transport operation | `NEW → SIGN_UPDATED → BLOCK_ENTITY_SENT → OPEN_EDITOR_SENT`; atomic terminal flag | Transport owns timeout and packet stage; first response, timeout, disconnect, cancel, scheduler failure, or stop claims terminal. Transport removes its UUID entry; region cleanup restores captured block state/barrier where scheduling permits. |
| Automatic coordinator ticket | admitted → started → completion-claimed → released; or released before start | Ticket identity owns one UUID/capacity slot. Coordinator's admission lock controls admission/start/release. Listener claims the final callback before enforcement and releases in `finally`; disconnect/reload/shutdown release pending tickets. |
| Platform classification | cached `JAVA`, `BEDROCK`, `UNKNOWN`, or `NO_PROVIDER` | Service owns per-UUID cache and count; quit removes the entry. Provider exceptions resolve to `UNKNOWN` when providers exist. |
| Probe diagnostics | active scan → completed history, or active removal on cancellation | Module records one terminal result; history is immutable to readers and capped at 100 entries. |
| Lunar/Apollo | stopped/available/suspended; registration ticket scheduled → applied/failed/removed | Lunar service owns tickets and state; player quit/replacement/disable/shutdown cancels or removes identity-matched ticket work. |

These code-level transitions are exercised by deterministic unit tests, not a
formal proof of server behavior. A terminal session ignores later transport
callbacks, automatic tickets are identity-scoped across reconnects, and the
automatic result claim gates enforcement. Shutdown invalidates sessions/tickets
and stops transport work.

Enforcement deduplication reserves in-flight `player UUID + session ID` keys and
retains completed keys for 15 minutes, capped at 4,096 total active/recent keys.
When that cap is reached, an otherwise eligible new kick is withheld with an
explicit capacity reason rather than evicting a still-relevant key and risking a
duplicate action. Expired completed keys are removed on later claims. This
bounded process-local protection complements, rather than replaces, session and
coordinator ticket identity checks.

The sign transport's narrow world and packet adapters delegate to Paper APIs in
production and permit deterministic stage failures in tests. A block's original
state must be captured before temporary mutation; if capture fails, cleanup does
not guess by setting the block to air. Cleanup scheduling/restoration failures
are logged as secondary infrastructure failures and do not rewrite the primary
probe result. Region/world restoration still depends on the platform accepting
the required scheduler and block operation.

Timeout remains `TIMEOUT`; disconnect, transport, response-processing, and
scheduler failures remain `ERROR` (disconnect cancellation during teardown may
be reported as `SKIPPED`). Neither timeout nor infrastructure failure creates
detection evidence or confirmation. Duplicate and late callbacks cannot reopen
a terminal session or repeat its terminal callback. A failed final consumer is
logged after the result has been recorded and does not restore active ownership.

An active scan keeps its immutable configuration/registry snapshot through a
valid or invalid reload. A valid reload changes only new scans; an invalid reload
retains the last known-good configuration. Disabling automatic checks cancels
not-yet-started admission work while already running scans follow their captured
snapshot. Shutdown and disconnect stop later packet stages and release automatic
capacity. Folia entity retirement is reported at transport, automatic admission,
automatic result, retry, and Lunar/Apollo entity-work boundaries rather than
running player work from another scheduler context.

Bedrock classification is checked before engine admission and again at the
transport boundary; a known Bedrock player receives no new probe. `UNKNOWN`
remains fail-closed and does not become Java due to provider exceptions or retry
exhaustion. `NO_PROVIDER` continues to permit Java probing by explicit policy.
Automatic concurrency capacity is reserved/released by operation-ticket
identity; duplicate/stale releases are harmless. Temporary sign cleanup is
scheduled in the owning region and cleanup failures are logged separately; the
already selected primary result is preserved. Restoration is best-effort when
the server rejects region scheduling or block restoration, so it is observable
but cannot be guaranteed after those external failures.

Status counters distinguish terminal result categories and the bounded recent
history excludes raw packet data. Failures during response processing,
scheduling, and cleanup are logged with operation identity; exception histories
are not retained. Deterministic tests include tick-ordered production-timeout races
(before-deadline, exact-deadline, after-deadline, barrier-released concurrent,
plus timeout racing cancel/shutdown); manual/automatic parity for all terminal
result categories; confirmation disconnect, reconnect, shutdown, duplicate
callback, and batch scheduling failure; enforcement exceptions, player
disappearance, and reentrant reconnect/shutdown; pending reload
cancellation/replacement; shutdown during aggregation, diagnostics publication,
and enforcement; a shutdown-while-everything-happens proof; and a 32-player
synthetic storm including Bedrock, UNKNOWN, trust exclusion, entity-scheduler
failure, transport failure, reconnect, timeout, enforcement failure, and a
deterministic Apollo subset (absent, not ready, action failure, scheduler
failure, retirement/disappearance). Candidate selection and sign restoration
shutdown behavior is covered transitively by the transport placement/packet
failure matrix and the editor-open shutdown test. Actual Folia retirement
callbacks and refusal of real block restoration remain platform-dependent;
fakes cannot establish live-server behavior.

## Validation

### P21 Structural Performance Model

`ProbeEnginePerformanceStructureTest` compares the previous inter-batch delay
and successful-first-candidate transport structure with the optimized structure
for scans of 1, 3, 10, 30, 38, 50, 75, and 100 probes. Each model is repeated ten
times and asserts deterministic counts. The mock completes responses immediately
and does not run Paper/Folia or a Minecraft client; these counts are structural
estimates, not elapsed-time or live scan-latency measurements.

| Probes | Batches | Scheduler calls before → after | Batch-delay tasks before → after | Candidate location clones before → after | World block lookups before → after |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 7 → 7 | 0 → 0 | 19 → 2 | 7 → 5 |
| 3 | 1 | 7 → 7 | 0 → 0 | 19 → 2 | 7 → 5 |
| 10 | 4 | 31 → 28 | 3 → 0 | 76 → 8 | 28 → 20 |
| 30 | 10 | 79 → 70 | 9 → 0 | 190 → 20 | 70 → 50 |
| 38 | 13 | 103 → 91 | 12 → 0 | 247 → 26 | 91 → 65 |
| 50 | 17 | 135 → 119 | 16 → 0 | 323 → 34 | 119 → 85 |
| 75 | 25 | 199 → 175 | 24 → 0 | 475 → 50 | 175 → 125 |
| 100 | 34 | 271 → 238 | 33 → 0 | 646 → 68 | 238 → 170 |

The model retains one tick between the block-entity packet and open-editor
packet, one timeout schedule and cleanup per sign operation, and the existing
three-probes-per-batch grouping. Reduced scheduler counts come from removing
inter-batch delay scheduling; reduced location allocations/lookups model the
first-candidate fast path and reuse of candidate/barrier data. Other candidate
fallbacks and real client response times are not represented by these rows.
Reproduce the model with:

```bash
mvn -Dtest=ProbeEnginePerformanceStructureTest -Dsurefire.useFile=false test
```

The model validates expected operation counts only. It cannot establish that
client latency or wall-clock scan duration improved on a live server.

### Live scan timing (isolated Paper/Folia environment)

Measured with protocol-level test clients on isolated localhost servers
(Java 21, Minecraft 1.21.11), not production load:

- Responsive scans (test client answers every sign editor promptly): around
  the 1-second range for a full 28-probe automatic scan.
- Silent/non-responsive scans (test client never answers): approximately 8 s
  (28 probes) and 9.5–10 s (37 probes) after the consecutive-silence backoff;
  approximately 20 s and 26 s respectively with the backoff disabled.

Silent scans remain longer because each probe batch has a bounded response
deadline and the protocol intentionally avoids overlapping sign editors for
the same player. This is a safety property, not a bug, and the 1–3 second
range is not claimed universally.

### Probe verification status (P28 live real-client evidence)

Catalog state: **3 VERIFIED, 33 UNVERIFIED, 2 DISABLED, 38 total**. A
`VERIFIED` definition is validated only for the client/Minecraft versions in
its notes. A runtime `DETECTED` result is a single scan's outcome; `VERIFIED`
is the definition's validation state, and `UNVERIFIED`/`DISABLED` are shown
explicitly by `/vac probes` and `/vac probe <id>`. Live-validated:
`meteor-client` (Meteor 1.21.11-86, manual + automatic), `apple-skin`
(AppleSkin 3.0.8, manual-only), `jade-config-screen` (Jade 21.1.6,
manual-only). Only probes with an exact structured-identity or exact
expected-response match can produce `STRONG`/`DETECTED`; plain
translation/keybind text without an exact match ceilings at `UNCERTAIN`
(`WEAK`), which is never actionable: Xaero Minimap/World Map, Litematica, IPN
probes, and Jade Config behave this way live. Disabled: `xaeros-minimap`
(false-positive history) and `itemscroller` (unconfirmable identifier).
Automatic join scanning keeps the no-false-positive 27-probe set: detectable
manually does not imply appropriate automatically (utility-mod detections such
as AppleSkin/Jade stay manual-only), and weak probes stay because they cost
only bounded backoff time. A planned further real-client expansion (P30) was
intentionally skipped, so broad client compatibility remains unverified. See
per-probe `source`/`notes` in `client-detection.yml`,
`/vac probe <probe-id>`, and
[`docs/RELEASE-NOTES-0.2.0.md`](docs/RELEASE-NOTES-0.2.0.md).

Current automated validation:

- Full client-detection, Lunar, trusted-player, and enforcement test suite
- `mvn clean package` passing
- Production JAR inspection passing
- `git diff --check` passing

Live validation remains incomplete. Paper player validation, Folia player
validation, Trusted Players live enforcement tests, concurrency tests, and
controlled positive client validation requires real client sessions. See
[`docs/P12-LIVE-VALIDATION.md`](docs/P12-LIVE-VALIDATION.md) and
[`docs/P12.2-VALIDATION-READINESS.md`](docs/P12.2-VALIDATION-READINESS.md).

## Configuration References

- [`src/main/resources/config.yml`](src/main/resources/config.yml)
- [`src/main/resources/client-detection.yml`](src/main/resources/client-detection.yml)
- [`src/main/resources/messages.yml`](src/main/resources/messages.yml)
- [`docs/RELEASE-NOTES-0.2.0.md`](docs/RELEASE-NOTES-0.2.0.md) (release candidate
  notes, migration notes, and known limitations)

License: MIT.
