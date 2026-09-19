# Changelog

## [0.1.0] — Velocity-only rewrite: pre-backend login verification firewall

Complete rewrite. The 1.3.x Paper/Folia behavioral product is gone; 0.1.0 is
a minimal admission-control plugin for **Velocity 3.4.x** (Java 21) that
verifies clients during login, before any backend connection.

- Login-phase verification: challenge over the `vanticheat:verify` login
  channel, binary RESPONSE validated (session, challenge, mod list), single
  authoritative ALLOW/DENY in `LoginEvent`. Only ALLOW reaches the backend.
- Binary verification protocol (wire v1): bounded parsing, strict UTF-8,
  duplicate and trailing-byte rejection, 64 KiB payload/report caps.
- Strict fail-closed admission: no valid verifier response means denial
  (unanswered challenges time out; misunderstood answers are rejected as
  protocol errors). There is no permissive mode.
- Exact mod/hash blocklist: identifier, name, version, loader, or exact
  JAR SHA-256 (`jarSha256`; legacy `fingerprints` key still loads). No
  fuzzy or prefix matching. `metadataSha256` and `jarSha256` stay distinct.
- Session isolation: sessions bound to the login TCP connection
  (IP + port); username enforced as ownership check, never as lookup key.
  Concurrent same-username logins cannot resolve each other's sessions.
- Replay protection: challenge and session ID must match the issued values;
  terminal sessions are single-transition and consumed on use.
- Malformed-input handling: attacker-triggered garbage yields one concise
  log line and a denial; no stack-trace spam, no backend contact.
- Bounded resources: session capacity cap, per-login pending-state caps,
  timeout + value-checked reaper, single scheduler thread.
- Backend isolation requirement: Velocity must be the only public entry;
  backends stay on localhost/firewalled or the gate is bypassed entirely.
- Production validation: real Velocity 3.4 + Paper 1.21.11 + Minecraft
  1.21.11/Fabric Loader 0.19.5 client runs (clean allow, forbidden-mod
  deny, missing-verifier deny, forbidden/modified-hash cases), live
  reload/restart proof, 32-minute soak (151 attempts, 0 errors, exact
  join accounting), 85/85 tests.
- Security limitations: client-reported data is untrusted input; a
  compromised client can lie and there is no remote attestation. 0.1.0
  detects nothing beyond its configured blocklist.

## Archive: 1.3.x Paper/Folia product line (not current)

## [1.3.1] — Anti-Freecam replacement release

### Changed (replacement, not additive)
- Deleted the old `freecam` information-firewall implementation entirely
  (14 classes, old packet adapter, old tests, old config tree, old doc).
- New first-class `antifreecam` system: behavior-based camera/body
  divergence detection (`DivergenceAnalyzer`), server-authoritative
  sightline validation (`Sightline`), body-relative exposure prevention
  (`ExposureGuard`: depth shadow + distance gate, FPAntiFreeCam concept
  rebuilt without the static floor and without client fingerprinting),
  single PacketEvents listener, standard evidence pipeline.
- Config: `anti-freecam:` replaces `freecam:` (migration v5 carries the
  master switch; old keys ignored). Docs: `docs/ANTI-FREECAM.md`.

### Security
- Freecam cameras no longer receive deep-block, tile-entity, distant-entity,
  or auxiliary-packet information outside body context; detached-camera
  look sweeps + out-of-reach interactions produce corroborated evidence.

## [1.3.0] — Commercial hardening + admin UX release

### Added
- Message CMS: full `messages.yml` (general, commands, status, inspect,
  alerts, diagnostics, client-security, punishment, errors) with `&` color
  codes and `%placeholder%`/`{placeholder}` support, pre-parsed and cached.
- Grouped `/vac` help menu (General / Security / Inspection / Debug /
  Administration) and colored status, inspection, evidence and diagnostics
  screens; severity-colored staff alerts.
- Plain-language `/vac diagnostics` with integration recommendations.
- QA punishment dry-run mode (`qa.punishment.dry-run`): logs instead of
  kicking/banning during testing.
- `docs/CONFIGURATION.md` and machine-readable `docs/BENCHMARK-1.3.0.md`.

### Improved
- Config validation now prints `Setting | Invalid value | Using` warnings.
- Migration prints a one-line summary (`previous vX → current v4`,
  preserved items); v4 adds the QA dry-run key to old configs.
- `config.yml` rewritten around presets with per-section admin guidance.

### Performance
- No hot-path changes by design; movement hot-path re-measured:
  p50 0.14µs / p95 ~0.3µs / p99 ~0.4µs (headless, 120k events).

### Security
- Fixed channel-namespace detection hole: `ClientFingerprint` split the
  namespace after normalizing, so `meteor:settings`-style channels never
  matched (also broke the vanilla+cheat AntiSpoof verdict).
- Fixed `xaerominimap_fair` channel never resolving (un-normalized map key).

### Fixed
- `MessageFormat` parser infinite loop on trailing `&`/malformed
  placeholders (would have OOM'd the server on first colored message).
- Startup WARN for legacy `MC|Brand` channel (now debug-level).
- Double `[VAntiCheat]` log prefixes.
- Raw `&a` codes leaking into `/vac status` rows and staff alerts.
- `PunishmentPolicy.diversityAllows` config-read NPE fallback (safe defaults).

### Compatibility
- Fully compatible with 1.2.x configs, evidence, trust and mod policies.
  Upgraders without the new `messages.yml` keys get built-in defaults.

## [1.2.1] — Hardening + extreme QA release

- Headless bot harness (proxy players): false-positive suite, adversarial
  suite, perf/lifecycle stress, client matrix, mod-channel resolution.
- Pure combat core (`assessReach`/`assessAura`/`assessClick`) decoupled from
  Bukkit events.
- `Messages` facade; kick/warn texts configurable; `/vac test` permission
  fix; debug tab-completion; malformed-config reload survival verified live.

## [1.2.0] — Commercial product architecture

- Platform/version/simulation layers, investigation states, replay buffer,
  feature flags, licensing stub, diagnostics, config migration v3.
