# Changelog

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
