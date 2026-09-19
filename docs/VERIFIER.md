# VAntiCheat 0.1.0 — Client verifier

The client verifier is **separate** from the Velocity plugin. It is not
bundled in `vanticheat-0.1.0.jar` and nothing in `/tmp`, test harnesses,
or disposable test mods ships with the release.

## Supported environment (live-tested)

- Minecraft **1.21.11** (protocol 774)
- Fabric Loader **0.19.5**
- Wire protocol v1 on login channel `vanticheat:verify`

## Installation location

Install the verifier mod JAR into the player's client `mods/` folder
(standard Fabric installation). No server-side installation exists: do
not put it on Velocity or on the backend.

## Login-phase behavior

1. During login, before any backend connection, Velocity sends a binary
   challenge on `vanticheat:verify`.
2. The verifier answers with a binary RESPONSE built from the actual
   loaded Fabric mod list: per mod, identifier, name, version, loader,
   plus `metadataSha256` (SHA-256 of `fabric.mod.json`) and `jarSha256`
   (SHA-256 of the mod file) where resolvable.
3. Velocity checks the report against its exact-match blocklist and
   allows or denies the login. Only allowed logins reach the backend.

## Strict verification behavior

0.1.0 is strict-only. A login without a valid verifier answer is denied
before any backend contact: unanswered challenges time out
(`timeout-ms`) and are denied; misunderstood or empty answers are
rejected immediately as protocol errors.

## Compatibility notes

- The verifier must speak wire protocol v1; mismatched version bytes
  are rejected.
- Client-reported mod lists and hashes are **untrusted input**. A
  compromised client can report a false-clean list; VAntiCheat
  guarantees bounded parsing, deterministic exact matching, and
  fail-closed admission — not client honesty. There is no remote
  attestation.
