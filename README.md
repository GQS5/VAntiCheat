# VAntiCheat — Professional Minecraft Client Security + Anti-Cheat

VAntiCheat layers **client security, behavior correlation and safe enforcement**
on top of the server's own protections. It runs natively on **Folia 1.21.11**
(and Paper), needs **Java 21**, and stays cheap for legitimate players by
escalating depth only when evidence justifies it.

It does **not** promise 100% detection, client file inspection, or magical AI.
What it detects — and what it honestly cannot — is listed under
[Known limitations](#known-limitations).

## Requirements

- Folia or Paper **1.21.11**, Java **21**
- Optional: ViaVersion (only if you serve older clients)
- Paper's built-in ore obfuscation enabled in `paper-global.yml`
  (`anti-xray.enabled: true`) — VAntiCheat scores mining *behavior*; hiding
  ores from chunk packets is the engine's job

## Installation

1. Download `vanticheat-1.3.0.jar` from [Releases](../../releases) into `plugins/`.
2. Start the server. `plugins/VAntiCheat/config.yml` and `messages.yml` generate.
3. Run `/vac status` — every module should show **Enabled**.
4. Give staff `vanti.cheat.alerts`. Exemptions use `vanti.bypass`.
5. Upgrading from 1.2.x? Just replace the jar: policies, trust, evidence and
   `config.yml` migrate automatically (see console migration summary).

## First setup (recommended profile)

```yaml
# config.yml — most servers start here:
security:
  profile: standard   # lenient | standard | hardcore | paranoid
```

- `lenient` — quiet small servers, maximum false-positive protection
- `standard` — balanced production default
- `hardcore` — aggressive SMP: firm when evidence is strong
- `paranoid` — maximum investigation; severe punishment still needs proof

Then `/vac reload` (evidence and trust are preserved).

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/vac` | Grouped help menu | `vanti.command` |
| `/vac status` | Module + profile dashboard | `vanti.command` |
| `/vac diagnostics` | Plain-language health + recommendations | `vanti.command` |
| `/vac inspect <player>` | Verdict, client, detection families, evidence | `vanti.inspect` |
| `/vac evidence <player>` | Recent evidence records | `vanti.inspect` |
| `/vac alerts` | Where staff alerts go | `vanti.command` |
| `/vac trust <p>` / `/vac untrust <p>` | Per-category trusted player | `vanti.trust` |
| `/vac punish` | Explains evidence-driven enforcement | `vanti.punish` |
| `/vac test <player>` | Raw brand + channels (support use) | `vanti.inspect` |
| `/vac debug …` | `performance`, `player`, `client`, `check` detail | `vanti.command` |
| `/vac reload` | Safe reload (no listener/state loss) | `vanti.command` |

## Permissions

`vanti.admin` (all), `vanti.command`, `vanti.inspect`, `vanti.punish`,
`vanti.trust`, `vanti.cheat.alerts` (receives detections), `vanti.bypass`
(exempt from enforcement). Defaults: staff nodes → op, `vanti.bypass` → off.

## Configuration

Full annotated reference: [docs/CONFIGURATION.md](docs/CONFIGURATION.md).
Rule of thumb: change `security.profile` first; touch thresholds only when
`/vac diagnostics` or evidence tells you to. Invalid values never crash —
startup/reload prints `Setting | Invalid value | Using` warnings with safe
fallbacks.

## Messages

Every staff/player-visible string lives in `messages.yml` (see
[docs/CONFIGURATION.md](docs/CONFIGURATION.md#messages)): `&` color codes,
`%player% %check% %confidence% %vl% %action% %client% %mod%
%server_version% %platform%` placeholders (both `%x%` and `{x}` work).
Kick screens, warnings, alerts, status and inspection labels are all covered.
New keys added in updates fall back to built-ins, so old files keep working.

## Mod policy

Forbidden by default (exact channel match + optional brand corroboration):
Xaero's Minimap / World Map, JourneyMap, VoxelMap, Freecam, Baritone, X-Ray
mods, Litematica, Tweakeroo. Always allowed: Sodium, Lithium, Iris,
FerriteCore, ImmediatelyFast, EntityCulling. Bare `xaero` alone never matches.

## Anti-Xray / Anti-ESP

Statistics over ore exposure, path geometry, rare-ore density and discovery
timing vs server/dimension/player baselines (≥2 independent signals; lucky
miners, Fortune III, beacons and branch mining are explicitly suppressed).
Hidden-player yaw correlation over a rolling 8s window; one glance is never
evidence. Neither module hides vanilla information.

## Client compatibility

Vanilla, Fabric, Sodium, Lithium, Iris: safe by default. Identifiable cheat
clients: flagged by brand/channel fingerprint. Unknown launchers: left alone
until behavior says otherwise. Ghost clients (Vape/Raven and similar): no
server-visible fingerprint exists — caught through combat/movement/ESP
behavior, never by name.

## Performance

Hot path is O(1) state + counters (movement ≈0.14µs p50 headless); statistics,
correlation and disk IO run async and batched; everything bounded
(`performance.*`). Profiling is sampled and off by default. See
[docs/BENCHMARK-1.3.0.md](docs/BENCHMARK-1.3.0.md).

## Troubleshooting

- `Paper anti-xray is disabled` reminder at startup → enable it in
  `paper-global.yml`; VAntiCheat only scores behavior.
- `Configuration warning` lines → fix the named setting, `/vac reload`.
- `ViaVersion … Limited` → install ViaVersion only for older clients.
- No alerts? Check `alerts.min-confidence` and the `vanti.cheat.alerts` perm.
- Testing punishments safely → `qa.punishment.dry-run: true` logs instead of
  kicking/banning.

## Known limitations

- No server plugin can read client mod folders; silent mods/ghost cheats are
  channel-invisible by design (behavioral coverage instead).
- Brand/channels arrive after login; pre-join rejection is best-effort.
- Entity ESP can't be hidden server-side without breaking vanilla.
- Ore hiding is Paper's engine feature, not this plugin's.

## API / developers

`VAntiCheatAPI#getPlayerProfile/getConfidence/getEvidence/isTrusted/isDetected`
(read-only). Build: `mvn -q -DskipTests package` (JDK 21, Paper repo).
Tests: `mvn test` (headless bot harness, no server needed). License: MIT.
Changelog: [CHANGELOG.md](CHANGELOG.md).
