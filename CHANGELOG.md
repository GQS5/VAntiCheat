# Changelog

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
