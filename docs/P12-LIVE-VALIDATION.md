# P12 Live Validation

P12 was run as a validation and hardening phase. No new detector was added. One concrete client-detection false positive was found and fixed: a historical clean Fabric client with Xaero's Minimap was confirmed as detected and kicked by the `xaeros-minimap` probe. Xaero's Minimap is within the legitimate-mod validation scope, so the unverified probe was disabled and removed from automatic join checks. A regression assertion now protects that policy.

## Environment

- Minecraft: 1.21.11
- Java: Java 26 on the available Paper/Folia fixtures; project compilation remains Java 21 target
- Paper fixture: Paper 1.21.11-67, `/home/shadi/test-server-paper`, port 25571
- Folia fixture: Folia 1.21.11-7, `/home/shadi/test-server-folia`, port 25570
- Client evidence: historical clean Fabric client `CleanP39` and prior controlled client/probe runs
- Current P12 artifact: `target/vanticheat-0.1.0-SNAPSHOT.jar`

## Live Smoke Results

### Folia

A fresh Folia startup using the current P12 artifact completed plugin remapping, loaded VAntiCheat, reported `platform=FOLIA`, reached `Done`, and shut down cleanly after timeout. No VAntiCheat exception or async-access violation was observed. No player client was connected, so player movement, combat, placement, join, reconnect, and region-separated tests remain unverified.

### Paper

The existing Paper fixture is running an older deployed artifact and was not replaced or restarted during validation. Historical Paper 1.21.11 logs show VAntiCheat startup, clean-player automatic join detection, probe completion, disconnect cleanup, and clean results. A fresh isolated Paper launch was attempted but could not initialize because the minimal copied fixture did not include Paper's expected bootstrap dependency layout. No current-artifact Paper player result is claimed.

## Detector Matrix

| Detector | Paper Legit | Paper Positive | Folia Legit | Folia Positive | Status |
|---|---|---|---|---|---|
| Reach | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| KillAura | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| AutoClicker | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Fly | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| NoFall | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Speed | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Scaffold | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |

Synthetic tests are not recorded as live positives. Physical mouse input remains unobservable.

## Client Detection Matrix

| Client | Detection | Confirmation | Enforcement | Status |
|---|---|---|---|---|
| Clean Fabric client (`CleanP39`) | CLEAN | N/A | NONE | PARTIAL, historical Paper run |
| Xaero's Minimap client | DETECTED | `xaeros-minimap` | KICK | FAILED, concrete false positive fixed by disabling probe |
| Controlled positive clients | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |

## Trusted Player Matrix

| Scenario | Expected | Actual | Status |
|---|---|---|---|
| Trusted player with live confirmed enforcement event | NONE | Not run in current live pass | UNVERIFIED |
| Trust persistence across restart/reconnect | Preserved | Persistence is covered by unit tests; live restart not run | PARTIAL |
| Trusted player still produces detector evidence | Yes | Covered by architecture/tests; live not run | PARTIAL |

## Automatic Join Matrix

| Scenario | Expected | Actual | Status |
|---|---|---|---|
| Clean join | One automatic check, no punishment | Historical Paper log shows scheduled/start and CLEAN completion | PARTIAL |
| Immediate disconnect | Session cleanup, no punishment | Historical disconnect paths observed; current artifact not run | PARTIAL |
| Multiple simultaneous players | Isolated sessions | Not run | UNVERIFIED |
| Positive client join | Confirm then policy | Historical positive flow exists; controlled current run unavailable | PARTIAL |

## Concurrency Matrix

| Players | Expected | Actual | Status |
|---:|---|---|---|
| 1 | One session | Historical single-player runs | PARTIAL |
| 2 | Independent sessions | Not run | UNVERIFIED |
| 4 | Independent sessions | Not run | UNVERIFIED |
| 8 | Independent sessions | Not run | UNVERIFIED |
| 16 | Independent sessions | Not run | UNVERIFIED |

## Evidence and Enforcement Audit

- No behavior detector was connected to `EnforcementService`.
- No normal-event disk or network writes were added.
- Existing evidence remains immutable and player-attributed.
- Trusted-player behavior was not changed.
- The only production change was the concrete false-positive configuration fix for Xaero's Minimap.

## False-Positive Audit

| Scenario | Detector | Observed result | Expected result | False positive? | Action |
|---|---|---|---|---|---|
| Clean Fabric client with Xaero's Minimap | Client probe | Confirmed detection and kick | No cheat enforcement | Yes | Disabled unverified probe and automatic check entry |
| Clean client join | Automatic client detection | CLEAN in historical Paper run | No punishment | No observed | Retained |
| Legitimate behavior detectors | Reach/KillAura/Fly/NoFall/Speed/Scaffold/AutoClicker | No live current-artifact result | Observe-only | Unverified | Requires controlled clients |

## Known Limitations

- No controlled Paper client session using the current P12 artifact was available.
- No Folia player session or multi-region client session was available.
- No controlled Meteor, LiquidBounce, Wurst, KillAura, Fly, Speed, Scaffold, or AutoClicker positive was reproduced.
- No live Trusted Players enforcement-boundary test was run.
- Historical Paper logs contain unrelated fixture plugin errors and are not clean evidence for full-system validation.
- Java 26 was used by the server fixtures; project build target remains Java 21.
- The Paper fixture needs a proper isolated bootstrap/library setup before current-artifact live testing.

## Validation Status

P12 is `PARTIAL`. The current artifact passed the full regression/build checks and a fresh Folia startup/shutdown smoke test. The Xaero's Minimap false positive was concretely identified and fixed with a regression test. The remaining live matrix requires controllable client sessions and a properly isolated Paper/Folia test harness.
