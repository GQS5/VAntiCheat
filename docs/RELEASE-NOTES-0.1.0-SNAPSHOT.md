# VAntiCheat 0.1.0-SNAPSHOT

VAntiCheat is a server-side anti-cheat plugin for Paper and Folia 1.21.11.
This snapshot combines configuration-driven client/mod probes with conservative
server-observable behavior telemetry. It is designed to collect evidence and
enforce only confirmed client-detection results, not to claim complete cheat
coverage.

## Snapshot.1 Hotfix

- Fixed Folia sign-probe cleanup and restoration scheduling. Chunk coordinates
  are now calculated without calling `Location.getChunk()` from the global
  scheduler, and all block access remains on the owning region scheduler.
- Added regression coverage for negative and positive chunk-coordinate mapping.
- Probe signs now try the block directly above the player first, with the
  existing nearby fallback positions retained when that location is occupied.
- Automatic join checks now wait 20 ticks by default so the client has finished
  joining before the first probe sign is opened; manual `/vacprobe` behavior is
  unchanged.

## Highlights

- Paper and Folia 1.21.11 support on Java 21.
- Automatic client checks when players join.
- Manual client checks with `/vacprobe <player>`.
- Two-pass confirmation for client probe results.
- Per-player detection sessions with bounded timeouts and concurrency.
- Centralized enforcement for confirmed client-detection results.
- UUID-based Trusted Players with persistent storage.
- Observe-only combat, movement, placement, and fall telemetry.
- Paper/Folia-aware global, region, entity, and asynchronous scheduling.
- Cleanup on normal completion, timeout, disconnect, shutdown, and session
  reuse.

## Client Detection

VAntiCheat uses a Paper 1.21.11 sign-probe transport and NMS packets to query
client translation keys and keybind-related signals. A probe can indicate that a
client exposes a recognizable signal; it does not prove that a feature is
active, that the player used it, or that the client is honest.

The configured probe inventory contains 28 entries:

- Meteor Client
- LiquidBounce
- Freecam
- Wurst
- XRay (Fabric)
- ChestESP
- KillAura (Fabric)
- AutoFish
- Lumina
- AutoSwitch
- BleachHack
- Aristois
- Coffee Client
- World Downloader
- AutoClicker (Fabric)
- AntiAFK
- Auto Clicker (p1k0chu)
- Trouser Streak
- UI Utils
- SeedCrackerX
- Simple World Downloader
- Trouser Streak NewChunks
- Trouser Streak AnHero
- Glazed
- Litematica
- Item Scroller
- Xaero's Minimap
- Baritone

All configured probes are currently marked `UNVERIFIED`. The 27 enabled probes
other than Xaero's Minimap are eligible for the automatic join check. Xaero's
Minimap is deliberately disabled and excluded from automatic checks because a
legitimate Xaero client produced a false-positive enforcement path during live
validation.

### Automatic Check Flow

When enabled, VAntiCheat:

1. Starts a bounded detection session shortly after every player join.
2. Runs the configured probe batch with a one-tick spacing between probes.
3. Applies a 40-tick response deadline per probe.
4. Performs the configured double-check/confirmation pass.
5. Produces a structured result and immutable evidence for the player.
6. Sends confirmed results through the centralized enforcement service.

Automatic checks are limited to 32 concurrent players by configuration. Player
UUIDs and session IDs isolate concurrent checks. Disconnects cancel active work;
timeouts, failed probes, and shutdowns do not leave a reusable session or probe
handle behind.

### Manual Checks

Administrators can run a configured client probe against an online player:

```text
/vacprobe <player>
```

Permission: `vanticheat.probe` (operator by default).

The command reports the result status and evidence count to the sender and uses
the same enforcement policy as automatic checks.

## Behavior Telemetry

Behavior detection is enabled in `behavior-detection.yml` and is explicitly
`observe` mode in this snapshot. It records structured signals and evidence but
does not kick or ban players.

### Reach

- Measures attack geometry and target distance from server-observable combat
  events.
- Intended to account for entity bounds, attack origin, and context rather than
  treating one unusual hit as proof.

### KillAura-Oriented Combat

- Tracks attack sequences, target selection, rotation/action coupling, target
  transitions, and combat context.
- Produces confidence and signal types over repeated observations.
- Does not infer cheating from one click, rotation, or target transition.

### AutoClicker-Oriented Combat

- Tracks attack cadence and click-interval distribution over a bounded window.
- Records timing quality, combat context, and confidence.
- Does not observe physical mouse clicks and does not treat high CPS alone as
  proof of automation.

### Fly

- Observes movement and air-state patterns that may violate server physics.
- Uses stateful movement evidence rather than a single position sample.

### Speed

- Observes movement distance, timing, and repeated speed signals.
- Keeps platform and movement context in the evidence path.

### NoFall

- Correlates falling state, landing state, and fall-damage outcomes.
- Uses state transitions to distinguish incomplete observations from a signal.

### Scaffold

- Observes block placement sequences alongside movement, rotation, and timing.
- Produces repeated placement evidence without enforcing it in this snapshot.

All behavior modules use bounded per-player sessions, immutable evidence models,
deterministic signal levels, and lifecycle cleanup for quit, teleport, respawn,
world changes, and vehicle transitions.

## Enforcement And Trusted Players

Confirmed client-detection results are passed to one enforcement service. The
current confirmed action is kick according to the configured policy. Behavior
signals never call the enforcement executor in this snapshot.

Trusted Players bypass punishment without becoming invisible to detection:

```text
/vac trust <player>
/vac trust add <player>
/vac trust remove <player>
/vac trust list
```

Permission: `vanticheat.admin`. Console is always authorized. Trust is stored by
UUID in `plugins/VAntiCheat/data/trusted-players.yml`, survives restart, and
uses atomic replacement where supported. Trusted players still receive checks,
produce evidence, and can be marked detected or confirmed; enforcement resolves
to `NONE` for their UUID. Removing trust takes effect immediately for later
enforcement decisions.

## Platform Support

- Detects Paper versus Folia at runtime.
- Routes entity work through entity schedulers.
- Routes region work through region schedulers.
- Routes global work through the global-region scheduler.
- Routes non-world work through the asynchronous scheduler.
- Shuts down detection, behavior sessions, trusted-player persistence, and
  scheduled work cleanly.

## Configuration

The plugin creates or loads these files in `plugins/VAntiCheat/`:

- `config.yml`: plugin enablement, debug logging, detection enablement, and
  confirmed-detection enforcement message.
- `client-detection.yml`: probe definitions, enabled state, timeout, spacing,
  double-checking, automatic join checks, and concurrency limit.
- `behavior-detection.yml`: observe-only behavior module switches and modes.
- `data/trusted-players.yml`: persistent trusted UUIDs and display names.

Build the production artifact with:

```bash
mvn clean package
```

Artifact:
`target/vanticheat-0.1.0-SNAPSHOT.jar`

## Compatibility

- Minecraft: 1.21.11
- Server: Paper / Folia
- Java: 21
- Plugin API: Bukkit/Paper API 1.21

## Validation

- 70 automated tests passing.
- `mvn clean package` passing.
- Production JAR inspection passing.
- JAR contains plugin metadata, all configuration resources, detection,
  enforcement, Trusted Players, behavior, and Paper/Folia classes.
- JAR contains no test classes, source files, or legacy Velocity classes.
- Paper two-player session isolation, disconnect cleanup, session reuse, and
  clean enforcement isolation passed in the available validation matrix.
- Folia clean-player startup, detection completion, scheduler routing, and
  shutdown passed in the available validation matrix.

## Known Limitations

- This is a development snapshot, not a stable release.
- All configured client probes remain marked `UNVERIFIED`; they are not
  remote attestation and can be spoofed, renamed, missing, or version-sensitive.
- Behavior detectors are observe-only and do not punish players.
- Live positive behavior-client validation is incomplete.
- Mixed clean/modded live isolation, 4/8/16-player stress, 33-player
  saturation, Folia positive detection, and direct temporary-world cleanup
  inspection remain unverified.
- No client-side mod, mandatory verifier, proxy component, Bedrock/Geyser
  identification, packet firewall, or universal cheat detection is included.
- Render-only or client-local features such as ESP, ordinary minimap use,
  shaders, performance mods, and camera-only Freecam cannot be reliably proven
  by this server-side architecture.
- Client-reported signals are evidence, not proof of client intent.
