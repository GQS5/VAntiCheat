# VAntiCheat 0.1.1

## Highlights

VAntiCheat 0.1.1 is the Paper/Folia 1.21.11 release for Java 21. It combines
configured client/mod probes with conservative server-observable behavior
telemetry. It does not claim complete cheat coverage or perfect accuracy.

## Detection

- Client/mod probes with automatic join checks, confirmation passes, bounded
  sessions, manual checks, and explicit result reporting.
- Observe-only Reach, KillAura, Fly, NoFall, Speed, Scaffold, and AutoClicker
  behavior modules.
- Meteor remains enabled with key `key.meteor-client.open-gui`.
- Xaero Minimap and Xaero World Map probes remain disabled and are not selected
  automatically.

## Enforcement

- Confirmed client-detection results use centralized enforcement.
- Behavior modules remain observe-only and do not kick or ban players.
- Trusted Players are UUID-based; detection still runs, trusted players are not
  punished, and removing trust restores normal enforcement.

## Administration

Administrators with `vanticheat.admin` can use:

```text
/vac trust <player>
/vac trust add <player>
/vac trust remove <player>
/vac trust list
/vac check <player>
/vac reload
```

Configuration validation rejects invalid boolean types with file/path/type
details. Failed reloads preserve the last known-good runtime.

## Lunar Client

Lunar Client itself is allowed. With the official Apollo plugin, VAntiCheat
disables the Lunar Minimap through Apollo while leaving the player connected.
Apollo does not control Xaero or arbitrary Fabric minimap mods.

## Compatibility

```text
Minecraft: 1.21.11
Java: 21
Server: Paper / Folia
```

## Validation

```text
102 automated tests passing
mvn clean test: PASS
mvn clean package: PASS
git diff --check: PASS
Production JAR inspection: PASS
```

These are automated and artifact validations, not live cheat-client proof.

## Known Limitations

- Live Paper player validation is unavailable.
- Live Folia player validation is unavailable.
- Real Lunar Client GUI validation is unavailable.
- Controlled positive behavior-client validation is incomplete.
- Meteor live positive validation remains unverified.
