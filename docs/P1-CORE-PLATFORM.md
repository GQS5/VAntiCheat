# P1 - VAntiCheat Core + Paper/Folia Foundation

## A. Architecture

```text
VAntiCheatPlugin (Paper JavaPlugin)
          |
          v
VAntiCheatCore (platform-neutral lifecycle/config ownership)
          |
          v
PlatformContext -> Platform + Scheduler
          |
          v
PaperFoliaScheduler (Paper/Folia API boundary)
```

The active artifact is a server-side plugin. The core package contains no
Bukkit, Paper, or Folia imports. Platform-specific behavior is isolated under
`platform` and `PaperFoliaScheduler`.

## B. Lifecycle

`VAntiCheatPlugin` implements `onLoad`, `onEnable`, and `onDisable`.

```text
NEW -> INITIALIZING -> RUNNING -> STOPPING -> STOPPED
```

Configuration is loaded during `onEnable`, the runtime platform is detected,
the scheduler bridge is created, and the core starts. Disabled configuration
stops before core startup. Shutdown is idempotent and cancels plugin-owned
scheduler tasks.

No gameplay or detection listeners are registered.

## C. Platform detection

`PlatformDetector` receives the Bukkit `Server` only at the platform boundary.
It checks for Paper's `io.papermc.paper.threadedregions.RegionizedServer` marker
using the server class loader:

- marker present: `FOLIA`;
- server present without marker: `PAPER`;
- null server: `UNKNOWN`.

This is runtime identification logic, not a claim of live support. Paper and
Folia live validation status is recorded in the final P1 report.

## D. Scheduler model

`Scheduler` exposes only four operations:

| Operation | Paper | Folia |
| --- | --- | --- |
| `runAtEntity` | Bukkit main scheduler | Entity scheduler |
| `runAtLocation` | Bukkit main scheduler | Region scheduler at world/chunk |
| `runGlobal` | Bukkit main scheduler | Global region scheduler |
| `runAsync` | Bukkit async scheduler | Folia async scheduler |

Entity and region targets are opaque platform boundary records. The core does
not receive Bukkit entities or worlds. `PaperFoliaScheduler` validates native
target types before dispatching. Feature code must choose an execution method
that matches the operation; no generic cross-context executor is provided.

## E. Configuration

The only active settings are:

```yaml
vanticheat:
  enabled: true
  debug: false
```

No feature, combat, movement, packet, client, protocol, Freecam, ESP, XRay, or
SPMT configuration exists.

## F. Dependencies

- Paper API `1.21.11-R0.1-SNAPSHOT`, `provided`: server plugin and Paper/Folia
  scheduler contracts.
- JUnit Jupiter `5.11.4`, test scope: foundation tests.
- Java 21: compiler release and runtime baseline.

No Velocity API, PacketEvents, ProtocolLib, ViaVersion, Geyser, Floodgate,
SnakeYAML, or shading dependency is used by the project.

## G. Future extension point

Future features must be added as separate modules that depend on core contracts
and platform interfaces. A future feature may use `PlatformContext.scheduler()`
and platform adapters, but must not add Bukkit imports to `core`.

```text
VAntiCheat
├── core
├── platform/paper-folia
└── features                 (future only; absent in P1)
    ├── client-detection
    ├── protocol
    ├── freecam
    ├── esp
    ├── xray
    └── behavioral
```

## H. Explicitly deferred

P1 contains zero detections and does not implement CheckHacks, Sign
Translation, client probing, client verification, Freecam, ESP, XRay, SPMT,
movement, combat, packet anomaly, or behavioral analysis.
