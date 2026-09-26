# VAntiCheat 0.1.2

## Hotfix

- Fixed `NoClassDefFoundError` during startup when Apollo is not installed or
  exposes an incompatible API. Apollo is optional; VAntiCheat now continues
  normally with Lunar integration unavailable.
- Added a regression test for missing optional bridge loading.

## Unchanged Policy

- Lunar Client itself remains allowed.
- When supported by the official Apollo integration, only the Lunar Minimap is
  disabled through Apollo.
- Xaero Minimap and Xaero World Map probes remain disabled.
- Trusted Players, detection, and enforcement behavior are unchanged.

## Compatibility

```text
Minecraft: 1.21.11
Java: 21
Server: Paper / Folia
```

## Validation

```text
103 automated tests passing
mvn clean test: PASS
mvn clean package: PASS
git diff --check: PASS
Production JAR inspection: PASS
```

Live Paper/Folia player validation, controlled positive behavior-client
validation, real Lunar GUI validation, and Meteor live positive validation
remain unavailable or incomplete.
