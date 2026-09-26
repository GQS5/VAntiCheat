# VAntiCheat 0.1.0-SNAPSHOT

## Highlights

- Paper/Folia 1.21.11 server plugin targeting Java 21.
- Server-observable client/mod detection with automatic join checks and
  confirmation-based enforcement.
- Trusted Players with UUID persistence and centralized enforcement bypass.
- Bounded behavior sessions and immutable structured evidence.

## Detection

Current behavior modules are observe-only:

- Reach
- KillAura-oriented combat analysis
- Fly
- NoFall
- Speed
- Scaffold
- AutoClicker-oriented attack cadence

The server does not directly observe physical mouse clicks. Behavior evidence
must be interpreted as server-observable behavior, not absolute proof of client
intent.

Client probe definitions are configuration-driven and should not be treated as
live-validated merely because they are present. The unverified Xaero's Minimap
probe is disabled and excluded from automatic join checks after a concrete
legitimate-client false positive.

## Enforcement

Confirmed client-detection results use centralized enforcement. Behavior
detectors do not invoke kick or ban actions in this snapshot.

Trusted Players can be managed with:

```text
/vac trust <player>
/vac trust add <player>
/vac trust remove <player>
/vac trust list
```

Permission: `vanticheat.admin`.

Trust is UUID-based and survives restart. Trusted players remain observable and
continue to generate evidence; only enforcement is bypassed. Removing trust
restores normal enforcement policy.

## Administration

The primary configuration files are:

- `config.yml`
- `client-detection.yml`
- `behavior-detection.yml`

The production artifact is generated at
`target/vanticheat-0.1.0-SNAPSHOT.jar` by `mvn clean package`.

## Compatibility

- Minecraft: 1.21.11
- Server: Paper / Folia
- Java: 21

## Validation

- 69 automated tests passing.
- Build passing.
- Production JAR inspection passing.
- `git diff --check` passing.

Live real-client validation is incomplete. Paper and Folia player sessions,
Xaero safe-outcome revalidation, Trusted Players live enforcement, concurrency,
and controlled positive behavior-client tests remain unavailable, unverified,
or require manual client access.

## Known Limitations

- Client-reported probe responses are not remote attestation.
- Behavior detectors only use server-observable events and remain observe-only.
- No claim is made of complete cheat coverage, bypass resistance, or perfect
  detection accuracy.
- The current Maven version is a development snapshot, not a stable release.
