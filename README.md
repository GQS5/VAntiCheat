# VAntiCheat 0.1.0 — Velocity pre-backend verification firewall

VAntiCheat 0.1.0 is a minimal admission-control plugin for **Velocity 3.4.x**.
During login — after authentication, before any backend connection — it sends a
challenge over the `vanticheat:verify` login channel, validates the client's
binary response (session, challenge, mod list), checks the mod list against an
exact-match blocklist, and sets the login result to ALLOW or DENY. Only ALLOW
reaches the backend.

VAntiCheat verifies and enforces information supplied through its verification
protocol. It does not independently prove the internal truthfulness of a
compromised client. A hostile client can report a false-clean mod list; the
plugin guarantees bounded parsing, deterministic exact matching, and
fail-closed admission — not client honesty. It provides no universal client
integrity guarantee and detects nothing beyond its configured blocklist.

## Requirements

- Velocity **3.4.x** (proxy), Java **21**
- A backend (Paper/Folia) reachable by Velocity; Minecraft 1.21.x clients
- Live-validated with Minecraft **1.21.11** (protocol 774), Fabric Loader
  **0.19.5**, and the separate Fabric verifier speaking wire protocol v1

VAntiCheat is **Velocity-only**. It is installed on the proxy. It does not run on Paper/Folia.

## Installation

1. Copy `vanticheat-0.1.0.jar` into the Velocity `plugins/` directory.
2. Start Velocity. The plugin initializes and reads `plugins/VAntiCheat/config.yml`
   (defaults are used when the file is absent).
3. Point players at the Velocity address. Keep backend ports private (see below).

## Client verifier

Strict mod verification requires the separate Fabric VAntiCheat Client Verifier
mod on the client. 0.1.0 is strict-only: any login without a valid verifier
response is denied before any backend connection. An unanswered challenge
times out (`timeout-ms`) and is denied; a misunderstood or empty answer is
rejected immediately as a protocol error. The `require-client-verifier` key is
reserved for forward compatibility and currently changes nothing — there is no
optional/permissive mode. The verifier is a separate artifact and is not
bundled in this JAR.

Verification sessions are bound to the login's TCP connection (remote IP +
port), which is unique per concurrent connection and stable across the login
phases; the username is enforced as an ownership check after lookup, never as
a lookup key, so concurrent logins under one username cannot resolve each
other's sessions.

## Backend protection

Velocity must be the only public entry point. Bind backends to localhost or
firewall their ports; otherwise clients can connect directly to Paper/Folia and
bypass Velocity — and VAntiCheat — entirely. VAntiCheat does not solve
network-level access control. Enable Velocity player-info forwarding in
production.

## Configuration

```yaml
general:
  enabled: true
verification:
  enabled: true
  require-client-verifier: true
  timeout-ms: 3000
  max-report-bytes: 65535
  max-session-count: 1000
  protocol-version: 1
mods:
  freecam:
    enabled: true
    action: kick
    identifiers:
      - freecam
    names:
      - Freecam
    versions: []
    loaders:
      - Fabric
    jarSha256: []        # exact JAR SHA-256 hex values; legacy key "fingerprints" also loads
```

To forbid a mod, add its exact identifier (or name/version/loader) or its exact
JAR SHA-256 to `jarSha256`. Matching is exact only: `freecam-helper`,
`not-freecam`, and `freecam2` do not match a `freecam` rule unless explicitly
configured. `metadataSha256` and `jarSha256` are distinct fields; only the
configured field is matched. No internal protocol details are needed.

Commands: `/vac status`, `/vac mods`, `/vac reload` (permissions
`vanti.command`, `vanti.admin`, `vanti.mods`). Reload applies new blocklist and
timeouts to new logins; in-flight verifications finish under the previous
snapshot.

## Limitations

- No verifier response means denial before any backend connection (unanswered
  challenges time out; misunderstood answers are rejected as protocol errors).
- Client-reported mod lists and hashes are untrusted input; exact matching only.
- Compromised clients can lie; there is no remote attestation.
- No gameplay, movement, combat, X-Ray, ESP, or behavioral detection in 0.1.0.

## Building and testing

Build with JDK 21: `mvn clean package`. Tests: `mvn clean test` (85 tests:
protocol, fuzz, gate, isolation, identity-binding, config). License: MIT.
