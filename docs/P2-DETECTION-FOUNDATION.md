# P2 - VAntiCheat Detection Foundation

## Architecture

```text
Paper/Folia platform
        |
        v
VAntiCheatCore
        |
        v
DetectionRegistry
        |
        v
future DetectionModule implementations
```

P2 adds platform-neutral detection contracts only. The active registry is
empty and no detection definition or module is shipped.

## Module contract and lifecycle

`DetectionModule` exposes a `DetectionDefinition`, module version, and three
lifecycle operations:

```text
register
  -> initialize(context)
  -> start()
  -> runtime
  -> stop()
```

`DetectionRegistry` uses explicit registration, rejects null/duplicate/invalid
modules, supports lookup and removal before startup, and shuts modules down in
reverse registration order. It performs no classpath scanning. A disabled
`detection.enabled` configuration prevents module activation.

## Definitions

`DetectionDefinition` contains only generic metadata:

- stable lowercase ID;
- display name and description;
- category: `CLIENT`, `PROTOCOL`, or `BEHAVIOR`;
- severity;
- enabled state.

No category has an implementation in P2. No Freecam, ESP, XRay, SPMT,
CheckHacks, or sign-probe definition exists.

## Result model

`DetectionStatus` is VAntiCheat-native:

```text
NOT_CHECKED
RUNNING
CLEAN
DETECTED
UNCERTAIN
PROTECTED
SKIPPED
ERROR
```

Infrastructure failure, timeout, and module failure produce `ERROR`; they are
never silently converted to `CLEAN`. Cancellation produces `SKIPPED`. The
model intentionally does not copy CheckHacks `HackResult`; later adapters may
map external semantics explicitly.

## Evidence model

`Evidence` is immutable and contains:

- generic evidence type;
- source identifier;
- timestamp;
- immutable string metadata.

Evidence is an observation record, not proof. P2 contains no evidence producer
and no automatic confidence or detection claim. Results can carry multiple
evidence items.

## Session model

`DetectionSession` binds a UUID session ID to a target UUID and detection ID.
It records creation time, immutable result snapshots, and evidence. Session
states are:

```text
NEW -> STARTING -> RUNNING -> COMPLETING -> COMPLETED
                         |-> CANCELLED
                         |-> TIMED_OUT
                         |-> FAILED
```

Transitions are synchronized and invalid transitions throw. Terminal sessions
cannot accept more evidence or responses. Timeout and failure are distinct
from a clean result.

## Target and context

`DetectionTarget` exposes only UUID, display name, and online state. It does
not wrap or expose the Bukkit `Player` API.

`DetectionContext` binds target and session identity and provides the existing
`PlatformContext`, `FoundationConfig`, and Java logger. The module lifecycle
uses the smaller `DetectionModuleContext`; per-session context is separate so
module startup state is not confused with a target scan.

## Platform boundary and scheduler

The detection package imports no Bukkit, Paper, Folia, NMS, or concrete
platform scheduler classes. A boundary test checks this source discipline.

Future modules use the existing `Scheduler` through `PlatformContext`:

- entity work uses `runAtEntity` with an entity target;
- block/world-region work uses `runAtLocation` with a region target;
- unbound server work uses `runGlobal`;
- genuinely thread-safe work uses `runAsync`.

P2 does not add another scheduler or make thread-safety claims for future
feature code.

## Configuration

```yaml
vanticheat:
  enabled: true
  debug: false
detection:
  enabled: true
```

This is the only detection-foundation setting. It controls whether the core
activates the registry; it does not enable a concrete detector.

## Error and concurrency semantics

The registry uses a concurrent map with synchronized lifecycle mutations.
Duplicate IDs are rejected atomically. Sessions serialize transitions and
evidence mutation. Core shutdown stops the registry before shutting down the
platform scheduler.

No static mutable registry or global session map exists.

## Future CheckHacks integration point

CheckHacks is intentionally deferred to P3. A future implementation may be a
`DetectionModule` with generic definitions and session/evidence results, but
P2 contains none of its classes, modes, sign transport, NMS calls, or result
semantics.

**P2 contains no actual anti-cheat detection.**
