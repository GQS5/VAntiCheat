# VAntiCheat 0.1.4

VAntiCheat is a server-side anti-cheat plugin for Paper and Folia 1.21.11.
It combines client/mod probes with conservative, server-observable behavior
analysis. Behavior detectors produce structured evidence and remain observe-only
in the current release state; confirmed client-detection results use the
centralized enforcement policy.

This release is not a claim of complete live cheat-client coverage.

## Requirements

- Minecraft server: Paper or Folia **1.21.11**
- Java **21**
- A server plugin installation; no proxy or client component is bundled

## Included Detection

Client detection supports configured translation/keybind probes, automatic join
checks, confirmation passes, bounded sessions, and enforcement for confirmed
client-detection results. Probe definitions remain operationally unverified
unless documented as live-validated. Xaero's Minimap remains disabled. Xaero's
World Map and Litematica probes are enabled in the bundled configuration and
selected for automatic checks.

Behavior modules are server-observable and observe-only:

- Reach geometry
- KillAura-oriented combat sequences
- Fly movement patterns
- NoFall fall/damage correlation
- Speed movement patterns
- Scaffold placement sequences
- AutoClicker-oriented attack cadence

These modules do not kick or ban players. Their evidence is not proof of a
physical mouse action or client intent.

## Lunar Client Policy

Lunar Client itself is allowed. When the official Apollo plugin recognizes a
Lunar player, VAntiCheat disables the Lunar Minimap through Apollo while the
player remains connected. This policy does not disable Xaero or arbitrary
Fabric minimap mods. Xaero Minimap remains disabled; Xaero World Map and
Litematica detection are enabled in the bundled probe policy.

## Enforcement And Trust

Confirmed client-detection results are evaluated by the centralized enforcement
service. Trusted Players changes the enforcement outcome to `NONE`; it does not
disable detection or evidence collection.

Administrators with `vanticheat.admin` can use:

```text
/vac help
/vac reload
/vac check <player>
/vac lunar <player>
/vac trust <player>
/vac trust add <player>
/vac trust remove <player>
/vac trust list
```

Trust is UUID-based and persists in
`plugins/VAntiCheat/data/trusted-players.yml`. Removing trust restores normal
enforcement for subsequent confirmed results. See
[`docs/TRUSTED-PLAYERS.md`](docs/TRUSTED-PLAYERS.md).

`/vac reload` safely reinitializes VAntiCheat and applies updated plugin,
client-detection, behavior, enforcement, and trusted-player configuration. It
does not invoke Bukkit's global `/reload` command.

`/vac check <player>` runs the same client probe as `/vacprobe <player>`.

## Installation

Build the exact production artifact with:

```bash
mvn clean package
```

Copy `target/vanticheat-0.1.4.jar` to the server `plugins/` directory,
then start Paper or Folia. The plugin creates or loads:

- `config.yml` for foundation, detection, enforcement, and Lunar policy settings
- `client-detection.yml` for client/mod probes and automatic join checks
- `behavior-detection.yml` for observe-only behavior modules
- `messages.yml` for configurable command, probe, and kick messages using `&`
  colors and `%placeholders%`

Do not enable an unverified client probe solely because it exists in the
configuration. `xaeros-minimap` remains disabled. The bundled policy explicitly
enables `xaeros-worldmap` and `litematica`; live positive validation remains
unverified.

## Validation

Current automated validation:

- 102 tests passing
- `mvn clean package` passing
- Production JAR inspection passing
- `git diff --check` passing

Live validation remains incomplete. Paper player validation, Folia player
validation, Trusted Players live enforcement tests, concurrency tests, and
controlled positive behavior-client tests require real client sessions. See
[`docs/P12-LIVE-VALIDATION.md`](docs/P12-LIVE-VALIDATION.md) and
[`docs/P12.2-VALIDATION-READINESS.md`](docs/P12.2-VALIDATION-READINESS.md).

## Configuration References

- [`src/main/resources/config.yml`](src/main/resources/config.yml)
- [`src/main/resources/client-detection.yml`](src/main/resources/client-detection.yml)
- [`src/main/resources/behavior-detection.yml`](src/main/resources/behavior-detection.yml)
- [`docs/P11-AUTOCLICKER-DETECTION.md`](docs/P11-AUTOCLICKER-DETECTION.md)

License: MIT.
