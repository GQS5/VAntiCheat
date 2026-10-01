# Changelog

## 0.2.1 - Detection correctness, gameplay safety, and passive detection

> **Read this before upgrading from 0.2.0.** This release deliberately changes what
> automatic detection does. Automatic coverage is now **1 of 42 probes**, down from 27.
> Interactive probes no longer run unattended, and a passive-only detection does not
> enforce by default. This is intentional: 0.2.0 opened a sign editor on joining
> players, which captured their client UI and stopped them moving. Read
> [RELEASE-NOTES-0.2.1.md](docs/RELEASE-NOTES-0.2.1.md) before deploying.

Scope: the P32 detection-correctness and gameplay-safety patch, and the P33 passive
detection research and implementation. Paper and Folia 1.21.11, Java 21.

### Breaking changes

- **Interactive probes no longer run automatically.** The sign-editor transport
  opens a client screen, so running it unattended interrupted normal movement.
  With `auto-check.interactive: false` (the default) an automatic join scan opens
  no client UI and mutates no world. Automatic-eligible probes drop from 27 to 1.
- **`transport` is now required metadata and defaults to `INTERACTIVE`.** An
  undeclared probe can never silently join the automatic path.
- **A resolved-but-unmatched response is now `UNCERTAIN`, not `CLEAN`.** A client
  that clearly resolved a submitted key is no longer reported as clean.
- **The `VERIFIED` guardrail also applies to a passive probe**, which must declare
  the channel it observes.
- A `PASSIVE` probe may not declare `expected-response` or `identity-resolution`.

### Added

- **Passive detection (P33), the first non-interactive identity path.** A probe may
  declare a `passive-channel`; the client emitting that inbound custom payload is
  authoritative identity evidence, observed with no UI, no sign editor, and no
  movement impact. One probe ships enabled this way: `jade-network-handshake`
  (`jade:client_handshake`).
- **Transport capability model** with a hard gate: `automatic-eligible = enabled &&
  automatic && (transport == PASSIVE || auto-check.interactive)`.
- **Passive evidence context**: UUID scoped, connection scoped, TTL bounded,
  cleared on disconnect, reconnect-isolated, no raw payload retained, and a repeated
  packet collapses to one recorded fact. No score.
- **`identity-resolution`**: a probe may declare that its key is defined only by the
  target, making any non-fallback resolution authoritative and **locale
  independent**.
- **Corroboration rules** (`ProbeCorroboration`): a scan reaches `DETECTED` only on
  authoritative evidence, and a non-promotion reason is recorded instead of being
  silently dropped. The confirmation pass is authoritative.
- **Client probe health** (`READY`, `RESPONSIVE`, `UNSUPPORTED`, `UNRESPONSIVE`,
  `TIMED_OUT`, `INTERRUPTED`) for observable probing-channel state. Never a
  detection, and never a statement about hardware.
- **Probe validity matrix**: per-probe transport, evidence quality, observable
  artifact, expected clean/target behaviour, and whether `DETECTED` is reachable.
  Derived from configuration, so it cannot claim an undeclared capability.
- **Diagnostics**: `/vac status` and `/vac status verbose` now report passive versus
  interactive counts, automatic-eligible and blocked-by-transport totals, health,
  and the promotion decision per scan. `/vac probe <id>` shows the full validity row.
- Three operator-requested client entries: `cezar-client`, `nova-client`,
  `forwarded` - all **disabled**, pending artifact verification.

### Fixed

- **Keybind probes could never report `CLEAN`.** `evaluateKeybind` never checked the
  fallback sentinel, so a clean client permanently returned `UNCERTAIN` and every
  keybind scan looked ambiguous.
- A detected Meteor user running a non-default client locale was reported `CLEAN`.
  The recorded en_us string was the only accepted match; identity resolution is now
  locale independent.
- `lastDecision` retained one entry per completed scan and was never released.
  Now bounded to active sessions.
- Client brand lookup no longer throws on platforms that do not expose it.

### Detection coverage

```text
42 probes | 3 VERIFIED | 39 UNVERIFIED | 5 DISABLED
1 PASSIVE | 41 INTERACTIVE | 37 manual-eligible
automatic-eligible 1 (28 only with the interactive opt-in) | DETECTED-capable 4
```

`VERIFIED` remains 3. It was not inflated.

**What percentage of the catalog is detectable automatically without opening a
client UI? 1 of 42, about 2.4%.** Not optimized. Most client mods transmit nothing
about themselves: Fabric Loader sends no mod list, and Meteor registers no
server-bound channel at all.

### Safety properties verified

- Zero gameplay-freeze mechanisms: no movement cancellation, velocity, teleport,
  slowness, freeze, spectator, or movement lock anywhere in the plugin.
- Bedrock receives zero probes; `UNKNOWN` fails closed; a passive observation can
  never reclassify the platform.
- Client brand is context only and never evidence.
- Passive identity does not reach enforcement unless `passive.enforce: true`,
  because the one proven channel belongs to a legitimate utility mod.

### Known limitations

- Automatic coverage is 1 of 42 probes.
- `jade-network-handshake` is `UNVERIFIED`: proven from the mod and server
  bytecode, not from a live client session.
- Passive signals are client-asserted and a modified client can suppress them.
- Meteor, the primary cheat target, has no passive signal and remains manual only.
- `xaeros-minimap` stays disabled for its false-positive history; `itemscroller`
  for an unconfirmable identifier.
- Movement and combat behavior detection is still not part of the product.

### Validation

- 332 tests, 0 failures, 0 errors, 0 skipped, stable across three full runs.
- `mvn package` successful, `git diff --check` clean.

## 0.2.0 - Release candidate

Scope note: this release consolidates the P14-P31 rework into one artifact. It is
internally consistent, tested, and packaged for Paper and Folia 1.21.11 on
Java 21, but it is not a claim of complete live cheat-client coverage.

### Breaking changes

- Behavior detection removed: `behavior-detection.yml`, the behavior module
  API, and behavior detection categories no longer exist. Existing
  `behavior-detection.yml` files are ignored, not loaded. Detection is now
  exclusively configuration-driven client/mod identity.
- A probe with `verification: VERIFIED` and no `notes`/`source` is now rejected
  at load and reload time instead of being accepted silently.
- Xaero's World Map and Litematica probe identifiers corrected to keys that
  exist in the shipped mod artifacts.

### Added

- Universal configuration-driven `ProbeRegistry` (38 probe definitions; adding
  a probe requires no Java changes).
- Automatic join detection with per-player operation ownership, coordinator
  capacity limits, trusted-player skip, reload snapshot, and exactly-once
  enforcement.
- Manual and admin surface: `/vac status [verbose]`, `/vac detections`,
  `/vac reload`, `/vac check <player> [probe-ids]`, `/vac probes`,
  `/vac probe <probe-id>`, `/vac lunar <player>`, `/vac trust ...`,
  `/vacprobe <player>`.
- Per-probe status reporting (`VERIFIED` / `UNVERIFIED` / `DISABLED`) with
  notes and source in `/vac probes` and `/vac probe <id>`.
- Consecutive-silence adaptive timeout (`short-timeout-ticks`,
  `short-timeout-after-consecutive-timeouts`) that shortens silent-client wall
  time without changing result semantics.
- Release notes and catalog verification guardrails.

### Changed

- Result semantics are explicit and frozen: `CLEAN`, `DETECTED`, `TIMEOUT`,
  `PROTECTED`, `ERROR`, `UNSUPPORTED`, `SKIPPED`, `UNCERTAIN`. A timeout is
  never clean, an error is never a detection, weak evidence is never
  actionable.
- Bedrock players are excluded before any probe session, transport call, sign,
  editor, evidence, or enforcement. `UNKNOWN` fails closed; `NO_PROVIDER`
  applies the Java-eligible policy.
- Lunar is an allow-policy integration through the optional Apollo plugin;
  players are never kicked or classified as cheats for using it.
- Sign-probe scanning is sequential by design (one open sign editor per
  player); batches are never overlapped.

### Fixed

- Concurrent cleanup race in the sign-probe transport: the temporary probe sign
  and barrier could be left in the world while the operation terminalized
  normally. Paper stores a `Location`'s world in a `WeakReference`, and cleanup
  re-derived its region target from that `Location` after terminalization, so a
  cleared world reference aborted the restore. The region target is now captured
  once during placement and reused for cleanup.

### Probe catalog

```text
38 total
3 VERIFIED    meteor-client, apple-skin, jade-config-screen
33 UNVERIFIED
2 DISABLED    xaeros-minimap, itemscroller
```

Live-verified scope: `meteor-client` (Minecraft 1.21.11 / Meteor Client
1.21.11-86, manual and automatic), `apple-skin` (Minecraft 1.21.11 / AppleSkin
3.0.8, manual), `jade-config-screen` (Minecraft 1.21.11 / Jade 21.1.6,
manual). A `VERIFIED` probe is verified only for the recorded versions.

### Performance (environment-specific measurements)

- Responsive real-client scans: ~0.95-1.8 s.
- Silent automatic 28-probe scan: ~8 s after the adaptive timeout.
- Silent manual 37-probe scan: ~9.5-10 s after the adaptive timeout.

Silent clients remain slower because each probe batch has a bounded response
deadline and sign editors are intentionally never overlapped.

### Known limitations

- Only 3 of 38 probes have real-client verification evidence; 33 remain
  `UNVERIFIED` because no real client was available, and 2 are `DISABLED`.
- Bedrock isolation is enforced in code and unit-tested but was not
  live-validated against a real Bedrock client.
- The Lunar integration was verified as available/ready with Apollo present;
  no real Lunar client was tested.
- EMI, LiquidBounce, Wurst, BleachHack, and Aristois were not obtainable in
  the isolated environment, so their probes remain unverified.
- A planned further real-client expansion (P30) was intentionally skipped.

### Verification

- `mvn clean test` — 224 tests passing, stable across 6 complete runs.
- Release asset `vanticheat-0.2.0.jar`
  sha256 `fdc731b7863f790ec316708dc29be07e27bf95abb85f1e924c8c3e0bcf3b8f73`.

## 0.1.13 - Trusted player probe exclusion

- Skip automatic and manual client probes for trusted players.
- Trusted players no longer receive probe signs or create detection sessions.

## 0.1.12 - Safer probe placement

- Raised probe signs one block higher to prevent player obstruction or damage.

## 0.1.11 - Clean reload output

- Avoid warnings when existing configuration resources are reloaded.
- Bundled configuration files are now copied only when missing.

## 0.1.10 - Geyser/Floodgate detection fix

- Resolve optional Geyser and Floodgate APIs through their Bukkit plugin
  classloaders so Bedrock players are skipped reliably.

## 0.1.9 - Bedrock probe exclusion

- Skip automatic and manual client probes for Bedrock players detected through
  Floodgate or Geyser.
- Prevent Bedrock clients from entering probe sessions that cannot complete.

## 0.1.8 - Configurable kick messages

- Moved the confirmed-detection kick message into `messages.yml`.
- Added `%mods%` and `%reason%` placeholders with color and multiline support.
- Preserved dynamic detection details for existing custom kick messages.

## 0.1.7 - Detection evidence and enforcement diagnostics

- Added detected mod names and detection reasons to centralized kick messages.
- Confirmed detections now log the player, action, detected mods, and reason.
- Added probe display names to evidence and expanded automatic/manual check
  lifecycle diagnostics.
- Added automatic probe selection parity coverage and documented the Xaero map
  policy.

## 0.1.6 - Apollo runtime diagnostics

- Added explicit Lunar startup states: `DISABLED`, `READY`, and
  `UNAVAILABLE reason=APOLLO_NOT_PRESENT`.
- Apollo registration now confirms `hasSupport(UUID)` before tracking a player.
- Policy diagnostics now report whether the Minimap override request succeeded.
- Documented the official Apollo Folia runtime requirement and live validation
  limits.

## 0.1.5 - Simpler probe configuration

- Automatic checks now use every probe with `enabled: true`.
- Removed the need to maintain a separate automatic probe ID list.

## 0.1.4 - Translation probe evaluator fix

- Fixed empty `TRANSLATE` fallbacks incorrectly classifying every non-empty
  response as `CLEAN`.
- Xaero World Map and Litematica translation responses are now evaluated
  normally.

## 0.1.3 - Xaero World Map probe policy

- Enabled `xaeros-worldmap` in the bundled automatic probe policy.
- Kept `xaeros-minimap` disabled.
- Existing server configurations must add `xaeros-worldmap` to
  `client-detection.auto-check.probes`; VAntiCheat never overwrites them.

## 0.1.2 - Apollo optional-dependency hotfix

- Fixed startup failure when the optional Apollo plugin/API is absent or
  incompatible. VAntiCheat now continues with Lunar integration unavailable
  while all other systems remain enabled.
- Added regression coverage for missing Apollo bridge loading.

## 0.1.1 - Paper/Folia release

### Added

- Paper/Folia 1.21.11 server-side client/mod probe detection with automatic
  join checks, confirmation passes, bounded sessions, and result reporting.
- Observe-only Reach, KillAura, Fly, NoFall, Speed, Scaffold, and AutoClicker
  behavior modules with structured evidence and bounded per-player state.
- UUID-based Trusted Players with `/vac trust`, `/vac trust add`, `/vac trust
  remove`, and `/vac trust list` administration.
- Official Lunar Apollo integration that allows Lunar Client while disabling
  only the Lunar Minimap when Apollo supports the player.
- Path-aware client-detection configuration validation and last-known-good
  reload behavior.

### Validation

- 102 automated tests pass.
- `mvn clean test`, `mvn clean package`, and `git diff --check` pass.
- Production JAR inspection passes with no test, source, or Velocity classes.
- Live Paper/Folia player validation, controlled positive behavior-client
  validation, and real Lunar GUI validation remain incomplete.

## 0.1.0-SNAPSHOT - Paper/Folia behavior foundation

This is the current development snapshot of the Paper/Folia implementation.

### Detection

- Added server-observable client/mod probes with automatic join checks and
  confirmation passes.
- Added observe-only Reach, KillAura-oriented combat, Fly, NoFall, Speed,
  Scaffold, and AutoClicker behavior modules.
- Added immutable, structured evidence and bounded per-player behavior state.
- Disabled the unverified Xaero's Minimap probe after live evidence showed a
  legitimate-client false positive; it is excluded from automatic join checks.

### Enforcement

- Added centralized enforcement for confirmed client-detection results.
- Added UUID-backed Trusted Players with `/vac trust` management commands.
- Trusted players still produce detection and evidence, but confirmed
  enforcement resolves to `NONE` for their UUID.

### Platform And Administration

- Added Paper/Folia platform detection and scheduler boundaries.
- Added lifecycle cleanup for quit, teleport, respawn, world changes, and
  vehicle transitions.
- Added configuration for foundation, client detection, enforcement, and
  observe-only behavior modules.

### Validation

- 69 automated tests pass.
- `mvn clean package` passes and the production JAR contains no test classes,
  Velocity classes, or source files.
- Live player validation remains incomplete. Paper/Folia player sessions,
  controlled positive behavior clients, Trusted Players live enforcement, and
  concurrency remain unverified or require manual client sessions.

## Historical Tags

- `v1.3.0` is the previous Paper/Folia product history.
- `v0.1.0` is the previous Velocity verification-firewall release.

The current snapshot has not been assigned a new stable release tag because
its Maven version remains `0.1.0-SNAPSHOT`.
