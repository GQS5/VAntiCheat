# VAntiCheat configuration reference

All keys live in `plugins/VAntiCheat/config.yml`. Unknown keys are ignored;
invalid values fall back safely with a console warning. Editing any section
by hand means the `security.profile` preset is customized (shown as your
chosen base profile everywhere).

## security.profile — presets

| Preset | Detection aggression | Punishment | Best for |
|---|---|---|---|
| `lenient` | ×0.7 weights, +15 confidence gate | Cautious | Quiet/private servers |
| `standard` | ×1.0, no gate shift | Balanced | Most servers (start here) |
| `hardcore` | ×1.25, −5 gate | Firm on strong evidence | Aggressive SMP |
| `paranoid` | ×1.1, max collection | Proof-gated, never reckless | Investigation servers |

```yaml
# Normal server
security: { profile: standard }
# Aggressive SMP
security: { profile: hardcore }
# Maximum investigation
security: { profile: paranoid }
```

## Section map

- `general` — master switch, debug logging, op exemption
- `features` — edition gates (core, client-security, behavior, anti-xray,
  anti-esp, advanced-simulation); disable whole modules here
- `client_security` / `modlist` / `antispoof` — brand/channel identification
- `antixray` — sample size, valuable blocks, statistical thresholds
- `anti_esp` — rolling correlation window (`history_seconds`), minimap policy
- `behavior` — per-check toggles, reach limit, violation decay
- `confidence` — signal weights + per-class idle decay (weak/medium/strong)
- `punishment` + `punishments` — global gates + per-category actions
  (`none|alert|warn|kick|tempban|ban`); bans need `minimum-independent-signals`
- `trust.bypass` — per-category staff bypass (no global bypass exists)
- `evidence` / `storage` — history toggle, retention days, file-size rotation
- `performance` — memory bounds; `profiling` enables sampled timings
- `alerts` — staff alerts toggle + silence threshold (`min-confidence`)
- `qa.punishment.dry-run` — log instead of kick/ban (testing only)
- `clients` / `mods` — per-client/mod enable + action overrides

## Messages

`plugins/VAntiCheat/messages.yml` categories: `general`, `commands`,
`status`, `inspect`, `alerts`, `diagnostics`, `client-security`,
`punishment`, `errors`. Placeholders: `%player% %uuid% %ping% %check%
%confidence% %vl% %action% %client% %mod% %profile% %platform%
%server_version%` (`{name}` also works). Colors: `&0-9a-f`, `&l` bold,
`&o` italic, `&n` underline, `&r` reset. Templates are parsed once per
reload; placeholder values can never inject colors. Missing keys use
built-ins, so old files survive updates.
