# Lunar Client Policy (Apollo)

```text
Lunar Client != Cheat
Lunar Minimap != Lunar Client itself
Apollo control != detection of arbitrary Fabric mods
```

## Policy

- Lunar Client itself is **allowed**. It is never kicked and never classified
  as a cheat by VAntiCheat.
- The Lunar Minimap is **prohibited** on this server. For players Apollo
  recognizes, VAntiCheat sends a server override disabling
  `ModMinimap.ENABLED`. The player remains connected; Lunar notifies them
  that the server disabled the mod.
- Correct policy result: `PROHIBITED MOD DISABLED` — never `CHEAT DETECTED`.

## Apollo Integration

Official Apollo API (`com.lunarclient:apollo-api:1.2.9`, `provided` scope,
repository `https://repo.lunarclient.dev`; Apollo itself is **not** shaded
into the VAntiCheat JAR). Requires the official Apollo plugin
(Bukkit/Folia) installed on the server; without it the integration reports
unavailable and VAntiCheat otherwise operates normally.
The `apollo-api` dependency is only the compile-time API; it is not the Apollo
server plugin and does not provide the Apollo server runtime.

Architecture boundary: all Apollo types live in
`platform/lunar/ApolloLunarBridge`, loaded only when the Apollo API is
present and functional. `lunar/*` (service, config, state) never links
against Apollo classes, so a missing Apollo installation cannot crash the
plugin.

Lifecycle (per official docs — never `PlayerJoinEvent`):

```text
ApolloRegisterPlayerEvent
  -> LunarClientService.handleRegistration(UUID)
  -> hasSupport(UUID) authoritative check (no brand guessing, no sign probes)
  -> getPlayer(UUID) ApolloPlayer lookup
  -> ModSettingModule.getOptions().set(player, ModMinimap.ENABLED, false)
  -> player stays online
```

Reconnect re-registers through the same path; per-UUID state is cleared on
Apollo unregister and on Bukkit quit, so no stale state survives a session.
Only `ModMinimap.ENABLED` is ever touched — no other Lunar mod is affected.

## Paper/Folia

Apollo callbacks in this integration touch UUIDs and Apollo data only. No
Bukkit player, location, or entity objects cross the bridge, no world/region
access occurs, and Apollo manages its own packet threading — therefore the
`BukkitApollo`/`FoliaApollo` helpers are unnecessary here by construction.
This was a deliberate minimal-footprint decision, not an omission.

## Configuration

`config.yml`:

```yaml
lunar:
  enabled: true
  minimap:
    enabled: true
    action: DISABLE
```

- `lunar.enabled: false` disables the whole integration.
- `lunar.minimap.enabled: false` registers Lunar players without any override.
- `action` accepts only `DISABLE`; anything else fails startup parsing with
  a path-aware error. There is intentionally no kick action.
- Invalid `lunar` section disables the integration with a warning instead of
  breaking the rest of the plugin.

## Diagnostic Command

```text
/vac lunar <player>   (permission: vanticheat.admin)
```

Reports `Lunar support: YES/NO`, `Minimap policy: ENABLED/DISABLED`, and the
known Apollo state (`UNKNOWN`, `REGISTERED`, `MINIMAP_DISABLED`). Diagnostic
only; exposes no protocol internals.

## Validation Status

- Unit/integration-boundary tests: PASS (13 new Lunar tests; full suite green).
- Runtime startup diagnostics report `DISABLED`, `READY`, or
  `UNAVAILABLE reason=APOLLO_NOT_PRESENT` once at startup.
- Disposable Folia runtime: Apollo-Folia `1.2.9` loaded successfully on Folia
  `1.21.11-7-ver` with Java `26.0.2.1`; VAntiCheat reported `READY`.
- Real Lunar Client session: **UNVERIFIED** — requires a live Lunar client to
  visually confirm the Minimap is disabled and re-applied on reconnect.
- Non-Lunar control expectation: Apollo integration does nothing; normal
  VAntiCheat behavior unchanged.

## Live Validation Matrix

| Test | Expected | Actual | Status |
|---|---|---|---|
| Apollo server startup | PASS | Apollo-Folia 1.2.9 loaded | VERIFIED |
| Lunar registration | YES | No real Lunar client session | UNAVAILABLE |
| `hasSupport(UUID)` | TRUE | No real Lunar client session | UNAVAILABLE |
| ApolloPlayer lookup | PRESENT | No real Lunar client session | UNAVAILABLE |
| Minimap policy sent | YES | No real Lunar client session | UNAVAILABLE |
| Minimap visually disabled | YES | No GUI session | UNAVAILABLE |
| Player remains connected | YES | No GUI session | UNAVAILABLE |
| Reconnect reapplies policy | YES | No GUI session | UNAVAILABLE |
| Vanilla unaffected | YES | No client session | UNAVAILABLE |

The runtime startup result proves the required Apollo server implementation is
installed and compatible with this test server. It does not prove the client
handshake or visual Minimap result.

## Limitation

Apollo controls Lunar Client features only. It does **not** disable Xaero's
Minimap or arbitrary Fabric mods. `xaeros-minimap` remains disabled;
`xaeros-worldmap` is a separate enabled probe in the bundled policy.
