# P0 - VAntiCheat Clean Rebuild

> Historical P0 record. P1 moved the runtime target from Velocity to a
> Paper/Folia server plugin; see `docs/P1-CORE-PLATFORM.md` for the active
> architecture.

## A. Repository audit

The reset was performed on branch `dev/protocol-v2`, whose last committed
history contains the previous VAntiCheat releases. Before cleanup, the active
tree contained the Velocity v1/v2 verification system, client report and hash
models, blocklist/policy code, enforcement and command code, generated v2
protocol additions, and a separate Fabric verifier. The previous worktree also
contained uncommitted changes and untracked files; those old systems were
explicitly removed by this reset rather than mixed into the new foundation.

Classification:

| Area | Classification | Reason |
| --- | --- | --- |
| `VAntiCheatPlugin` lifecycle | REBUILD | Replaced with foundation-only bootstrap |
| v1/v2 connection and protocol packages | REMOVE | Feature implementation, not foundation |
| client reports, fingerprints, mod records | REMOVE | Old client scanner models |
| blocklist, policy, enforcement, commands | REMOVE | Old verification product |
| old tests | REMOVE | Coupled to removed architecture |
| `verifier-fabric` | REMOVE | Separate old client verifier |
| `docs/archive-1.3.x` | ARCHIVE | Historical Paper/Folia documentation retained only as history |
| Maven compiler, Velocity API, JUnit | KEEP | Required by the foundation and tests |
| SnakeYAML and shading | REMOVE | No longer required by the minimal config loader |

No CheckHacks code was integrated. No detection implementation remains active.

## B. Removed systems

The cleanup removed the old Velocity verification/blocklist implementation,
including v1/v2 protocol codecs and validators, login listeners, session
managers, blocklist/policy classes, client report/hash classes, enforcement,
commands, old configuration/message resources, and their coupled tests.

It also removed the untracked v2 additions and the Fabric verifier source from
the worktree. No Freecam, ESP, XRay, SPMT, behavioral, packet-anomaly, or
CheckHacks detection engine was recreated.

## C. Archived systems

`docs/archive-1.3.x/` remains as historical documentation only. It is not
compiled, loaded, or referenced by the foundation. The old source remains
recoverable through git history; no runtime archive copy was added to avoid
leaving dead code in the active project.

## D. New package structure

```text
site.vackstudio.vanticheat
├── VAntiCheatPlugin.java       Velocity plugin lifecycle entry point
├── config
│   ├── ConfigurationLoader.java
│   └── FoundationConfig.java
├── core
│   └── VAntiCheatCore.java     Foundation ownership and lifecycle
├── lifecycle
│   └── LifecycleState.java
└── platform
    └── Platform.java           Explicit current runtime boundary
```

Only packages with real classes exist. There are no speculative feature
packages or empty manager placeholders.

## E. Remaining classes

- `VAntiCheatPlugin`: receives Velocity initialize/shutdown events and owns the
  runtime instance.
- `VAntiCheatCore`: owns immutable configuration/platform context and an atomic
  `NEW -> RUNNING -> STOPPED` lifecycle.
- `FoundationConfig`: contains only `enabled` and `debug` foundation settings.
- `ConfigurationLoader`: loads the small, deterministic foundation config.
- `Platform`: records that the current runtime is Velocity.
- `LifecycleState`: explicit lifecycle state model.

There is no detection, networking, storage, command, scheduler, NMS, Bukkit,
Paper, or Folia implementation in P0.

## F. Dependency changes

| Dependency | P0 decision |
| --- | --- |
| Velocity API 3.4.0 | KEEP - plugin lifecycle API |
| JUnit Jupiter 5.11.4 | KEEP - focused foundation tests |
| SnakeYAML 2.3 | REMOVE - no feature config schema or runtime YAML dependency |
| Maven Shade Plugin | REMOVE - no runtime library requires bundling |

The resulting runtime dependency footprint is the Velocity API supplied by the
host. Java 21 remains the compiler/runtime baseline.

## G. Configuration changes

The active configuration is now only:

```yaml
vanticheat:
  enabled: true
  debug: false
```

All verification, blocklist, mod, protocol, Freecam, ESP, XRay, SPMT, and
legacy settings were removed. Unknown sections are ignored by the deliberately
small loader; invalid known booleans fall back to deterministic defaults when
loaded from disk.

## H. Test changes

The feature-coupled test suite was removed with its implementation. Three
focused test classes were added, containing five tests total:

- configuration defaults, known values, and unknown-section behavior;
- core lifecycle and retained context;
- current platform identity.

These tests prove structure, not anti-cheat behavior.

## I. Lifecycle

```text
Velocity ProxyInitializeEvent
  -> ConfigurationLoader
  -> FoundationConfig
  -> VAntiCheatCore
  -> core.start()
  -> runtime
Velocity ProxyShutdownEvent
  -> core.shutdown()
  -> STOPPED
```

Shutdown is idempotent. Starting after shutdown is rejected. No static global
service registry or background thread is created.

## J. Future integration points

Future modules attach to `VAntiCheatCore` through explicit services once their
requirements are known. The platform boundary must be extended before any
server-side Minecraft feature is implemented. Paper/Folia code must not be
placed in this Velocity artifact.

## K. Deferred systems

The following are intentionally **not implemented**:

- CheckHacks integration;
- Sign Translation detection;
- Freecam defense;
- ESP defense;
- XRay defense;
- SPMT;
- behavioral detection;
- advanced protocol verification;
- client verifier and pre-login scanner;
- Bukkit/Paper/Folia/NMS bridge.

## L. Roadmap

```text
P0 - Clean Rebuild
  -> P1 - Core Platform / Lifecycle
  -> P2 - Client Detection Foundation
  -> P3 - CheckHacks / Sign Translation Engine
  -> P4 - Protocol Verification
  -> P5 - Freecam Defense
  -> P6 - ESP / XRay Defense
  -> P7 - Behavioral Detection
  -> P8 - Performance / Folia Hardening
  -> P9 - Full QA / Bypass Laboratory
```

This roadmap is descriptive only. No later phase was started.
