# Changelog

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
