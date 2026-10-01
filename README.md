# VAntiCheat 0.2.1

VAntiCheat is a server-side client/mod detection plugin for Paper and Folia
1.21.11. It uses configuration-driven probes, automatic join checks, manual
scans, Lunar/Apollo integration, Bedrock isolation, trusted players, and
centralized enforcement for confirmed client detections.

This release is not a claim of complete live cheat-client coverage.

## Requirements

- Minecraft server: Paper or Folia **1.21.11**
- Java **21**
- A server plugin installation; no proxy or client component is bundled

## What This Plugin Actually Detects

Read this before configuring anything. VAntiCheat detects **client and mod identity**
over one specific protocol channel. It does not detect "all cheats", and it does not
detect hardware, device, CPU, GPU, RAM, or FPS quality.

### The one mechanism that works

Every probe is delivered the same way:

1. The server writes a translatable component onto a temporary sign above the player.
2. The server opens that sign's editor on the client.
3. The client resolves the translation and submits the sign back.
4. The server compares the answer against what a clean client is required to return.

A client that does not have a mod's translation key returns the server-supplied
sentinel. A client that has it returns its own rendering. That difference is the
only authoritative per-mod signal available here.

### Why there is no automatic mod detection by default

The sign editor is a **client screen**. While it is open, the client captures the
player's input and the player cannot walk normally. Running that on every join, for
every probe, is not an acceptable background behaviour.

There is also no alternative. On Paper 1.21.11 there is no non-interactive,
server-observable per-mod identity signal for vanilla or Fabric clients. The
available passive signals are platform classification and client brand, and a
Fabric client that is running Meteor still reports the same brand as a legitimate
Fabric client running only Sodium. Reading Forge's mod-list handshake or an
inbound plugin-message payload would need a packet-level library, which this
plugin deliberately does not depend on.

So the default is explicit and safe:

- `client-detection.auto-check.interactive` is **`false`**.
- An automatic join scan therefore opens **no client UI and never touches the world**.
- Interactive probes run only when you ask for them: `/vac check <player>`.
- Setting the flag to `true` restores unattended interactive scanning. Read the
  caveat in `client-detection.yml` first; it will repeatedly open a sign editor
  on the player's screen and block their movement.

### What a scan can conclude

| Conclusion | Meaning |
| --- | --- |
| `DETECTED` | Exact structured identity, or a client resolved a translation key that only this target defines |
| `CLEAN` | The client answered and nothing indicated a probed target |
| `UNCERTAIN` | The client rendered translated text with no identity attached; not attributable to any specific mod |
| `PROTECTED` | The response channel was neutralized |
| `TIMEOUT` | No answer inside the deadline; never clean, never detected |
| `UNSUPPORTED` | The transport cannot carry this probe |
| `ERROR` | Transport, scheduling, or processing failure; never a detection |
| `SKIPPED` | Policy or eligibility stop |

`UNCERTAIN` is the honest ceiling for a translation probe with no declared
identity resolution, because the protocol returns a plain string and a plain
string cannot be attributed to a specific mod.

### Coverage, stated exactly

```text
39 probe definitions
3   VERIFIED     live-validated against a real client on Minecraft 1.21.11
36  UNVERIFIED   no live evidence; may reach UNCERTAIN, never DETECTED
2   DISABLED     intentionally not executed, with a recorded reason
1   PASSIVE      answered by an observed protocol channel, no client UI
38  INTERACTIVE  answered through the sign editor, manual verification only
37  manual-eligible
28  automatic-eligible   (1 passive by default; 28 only with the interactive opt-in)
4   DETECTED-capable
```

**What percentage can be detected automatically without opening a client UI?**
**1 of 39 probes — 2.6%.** That is the honest number and it is not optimized. The single
automatic probe is `jade-network-handshake`, explained below. Raising it would require
either client-side code or protocol tooling this plugin deliberately does not use.

VERIFIED, with the exact versions recorded in each probe's notes:

| Probe | Version | Observable evidence |
| --- | --- | --- |
| `meteor-client` | Meteor Client 1.21.11-86 | Client resolves `key.meteor-client.open-gui` |
| `apple-skin` | AppleSkin 3.0.8 | Client resolves `text.autoconfig.appleskin.title` |
| `jade-config-screen` | Jade 21.1.6 | Client resolves `gui.jade.configuration` |

Detection is verified only for those exact mod and Minecraft versions. A newer
build of the same mod is not covered by that evidence.

### The one passive signal, and exactly how it works

Most client mods send the server nothing about themselves. Fabric Loader does not
transmit a mod list at all — verified by inspecting the loader, which contains no
client-to-server networking. Meteor registers no server-bound channel whatsoever.
AppleSkin only *receives* server-to-client payloads. Litematica's channels are
render overlays.

Jade is different, and it is the reason automatic detection is possible at all.
Jade 21.1.6 hooks `ClientPlayConnectionEvents.JOIN` and, in that handler,
unconditionally sends a custom payload on the channel `jade:client_handshake`.
No player action is involved. The full chain, every link verified against the
real Jade jar and the real Paper 1.21.11 server jar:

```text
Jade: ClientPlayConnectionEvents.JOIN
  -> ClientPlayNetworking.send(new ClientHandshakePacket("9"))
  -> inbound custom payload, channel "jade:client_handshake"
  -> server has no codec for that channel, so it decodes as DiscardedPayload,
     which preserves the channel id and the raw bytes
  -> ServerCommonPacketListenerImpl.handleCustomPayload
  -> CraftServer.getMessenger().dispatchIncomingMessage(conn, channel, data)
  -> StandardMessenger dispatches to listeners registered for that exact channel
  -> jade-network-handshake probe: STRONG evidence, automatic, no UI
```

This uses only the supported Bukkit/Paper plugin-messaging API. There is no NMS
access, no Netty access, no packet rewriting, no new dependency, and nothing is
ever sent to the client, so it cannot prompt the client to do anything either.

**Its absence proves nothing.** A client that does not send the channel is
`CLEAN`, so this can never produce a false positive. **It is not live-verified.**
It is marked `UNVERIFIED` because it is proven from bytecode, not from a client
session, and it must not be promoted without a live run.

### Passive identity does not kick by default

`client-detection.passive.enforce` is `false`. A confirmed passive detection is
reported in `/vac detections` and `/vac status`, but does not reach enforcement.
The one channel proven so far belongs to Jade, a legitimate utility mod, so
kicking players for it automatically would be a false positive. Operators who
want it actionable must opt in explicitly.

### Locale independence

A probe that declares `identity-resolution: true` accepts **any** non-sentinel
resolution of its own target-specific key, not only the recorded default-locale
string. That matters because a client running a different locale renders a
different string for the same key, and a naive exact-string match reports a
present mod as `CLEAN`. The sentinel check still guarantees that a client
without the key is never detected.

### What is never evidence

None of these can produce a detection on their own, by design and by test:

- substring, prefix, or partial matches
- generic visible text or arbitrary localized text
- a guessed or merely plausible mod identifier
- client brand, including Fabric brand
- timing, latency, or absence of a response
- a probe id or category that merely looks right

`/vac probe <id>` shows, per probe, the evidence quality, the observable artifact,
the expected clean and target behaviour, and whether the probe can reach
`DETECTED` at all.

### Client probe health

Separately from detection, VAntiCheat records what it can actually observe about
the probing channel: `READY`, `RESPONSIVE`, `UNSUPPORTED`, `UNRESPONSIVE`,
`TIMED_OUT`, `INTERRUPTED`. This is transport and client readiness only. It is
never a detection and says nothing about a player's hardware. See
`/vac status verbose`.

Server-observable movement and combat behavior detection is intentionally not
part of the active product, and this patch did not add any.

## Automatic Detection Lifecycle

Automatic join checks use the same `ProbeRegistry`, scan engine, transport,
normalizer, evaluator, corroboration rules, confirmation rules, and enforcement
policy as manual checks. The trigger differs; result interpretation does not.
The listener classifies platform first, then applies the existing trusted-player
exclusion, then admits a delayed scan from the registry's **transport-gated**
automatic snapshot. Empty automatic selection creates no session or transport
operation. Bedrock is skipped; provider-pending `UNKNOWN` is retried a bounded
number of times and then remains unprobed.

### The transport capability gate

Every probe declares its transport requirements as configuration metadata. It is
never inferred from the probe id.

```yaml
transport: PASSIVE | INTERACTIVE
```

- `PASSIVE` reads an already-available signal. It opens no client UI, captures no
  input, and is the only kind allowed to run unattended.
- `INTERACTIVE` needs the client to open and submit UI, so it may capture
  movement and input.

An absent `transport` declaration **fails closed to `INTERACTIVE`**, so a
newly added probe can never silently join the automatic path. Admission is:

```text
automatic-eligible = enabled && automatic && (transport == PASSIVE || auto-check.interactive)
```

With the shipped catalog every probe is `INTERACTIVE`, so the default automatic
eligible set is empty and a join performs no probing at all. `/vac status` reports
`Probes passive/interactive`, `Automatic-eligible`, and `Blocked-by-transport` so
the reduction is visible rather than silent, and a startup warning lists every
excluded probe.

### Corroboration

A scan's terminal status comes from explicit promotion rules, not from counting.
A detection requires authoritative evidence for a specific probe: exact component
identity, an exact configured response, or a declared identity resolution.
Multiple weak signals are never summed; they are reported `UNCERTAIN` together
with the reason they were not promoted. A `DETECTED` that somehow arrives without
authoritative evidence is refused defensively. The confirmation pass is
authoritative, so a single non-reproducing response cannot detect.

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
      fallback: "⟦NO_EXAMPLE_CLIENT⟧"
      enabled: true
      manual: true
      automatic: false
      verification: UNVERIFIED
      category: client
      source: "https://example.invalid/client/lang/en_us.json"
      notes: "Identifier source found; client response not live-verified."
      transport: INTERACTIVE
      identity-resolution: false
```

`transport` and `identity-resolution` are the two fields that decide what a probe
can actually do, and both must be chosen deliberately.

**`transport`** defaults to `INTERACTIVE` when omitted, which is the safe default:
an undeclared probe can never join the automatic path. Declare `PASSIVE` only for
a probe served entirely from an already-available signal. A `PASSIVE` probe may
not declare `expected-response` or `identity-resolution`, because it never
performs the interactive round-trip those depend on.

**`identity-resolution`** is the difference between a probe that can detect and
one that can only report ambiguity. Set it to `true` only when the key is defined
by the target mod's own translation table and no clean client can resolve it. A
client that returns the fallback sentinel is then `CLEAN`; a client that returns
anything else resolved that mod-only key, which is authoritative and independent
of display locale. Leave it `false` when you have only found a plausible
identifier, and the probe will honestly report `UNCERTAIN` instead of detecting.

Before enabling a new probe, confirm against the mod's own language resource that
the key exists, and record where you read it in `source`. An identifier's
existence upstream is not proof of detection.

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
keybind mapping. Use it only when the resolved text is known; do not guess, and
remember it only covers the locale you recorded. When the response retains a
structured Component identity, the evaluator compares that identity with the
configured key. If only localized text is available and neither an exact expected
response nor a declared identity resolution applies, ambiguous translation or
keybind text is reported as `UNCERTAIN` with `WEAK` evidence and is never
promoted. `METEOR` uses exact configured identity and response matches. A
response that resolves a key but does not match a recorded exact string is
reported `UNCERTAIN`, not `CLEAN`, because the client clearly resolved something
and claiming a clean bill of health would be a false negative. Manual and
automatic scans share the same evaluator and the same corroboration rules.

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

### Player impact (the metric that matters)

Measured on Java 21 in this repository, 2000 iterations after warm-up:

| Operation | Cost | Player-facing effect |
| --- | --- | --- |
| Automatic join admission (42-probe catalog) | 0.08 µs | none; the passive probe is answered from evidence |
| Automatic join, client UI opened | 0 | none |
| Automatic join, world mutations | 0 | none |
| Automatic join, player state changes | 0 | none |
| Single probe evaluation | 0.04 µs | none |
| Client-probe health record | 2.8 µs | none |

With the shipped catalog and the default `auto-check.interactive: false`, an
automatic join scan performs **no player-impacting work at all**: it opens no
client screen, mutates no block, and applies no velocity, teleport, freeze, or
movement lock. The plugin contains no mechanism that could do any of those; this
is asserted by test and by static audit, not just by configuration.

The only path that costs a player attention is an explicit interactive scan
(`/vac check <player>`) or an operator who has deliberately enabled
`auto-check.interactive`.

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

Catalog state: **3 VERIFIED, 39 UNVERIFIED, 5 DISABLED, 42 total**, of which
**0 are passive and 38 are interactive**, and **3 are DETECTED-capable**. A
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
(false-positive history), `itemscroller` (unconfirmable identifier), and
`cezar-client` / `nova-client` / `forwarded` (requested, but no build available to
confirm an identifier, so they carry a visibly marked placeholder key).
Twenty-eight probes are configured `automatic: true`, but the transport gate
excludes every `INTERACTIVE` one by default, leaving exactly one automatic probe:
the passive `jade-network-handshake`. Detectable manually does not imply appropriate
automatically (utility-mod detections such as AppleSkin/Jade stay manual-only), and
weak probes are retained because an operator may run them deliberately with
`/vac check`. That is a real reduction in automatic coverage and it is intentional:
gameplay safety was chosen over automatic coverage. See
per-probe `source`/`notes` in `client-detection.yml`,
`/vac probe <probe-id>`, and
[`docs/RELEASE-NOTES-0.2.1.md`](docs/RELEASE-NOTES-0.2.1.md).

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
- [`docs/RELEASE-NOTES-0.2.1.md`](docs/RELEASE-NOTES-0.2.1.md) (current release notes,
  migration notes, and known limitations)
- [`CHANGELOG.md`](CHANGELOG.md)

License: MIT.
