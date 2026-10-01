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

Architecture boundary: the main plugin reflectively loads
`platform/lunar/ApolloLunarBridge`; Apollo is a `provided` compile-time API and
the server plugin remains optional (`softdepend`). The bridge is instantiated
only if its API classes can be resolved. `lunar/*` never links against Apollo
classes. API present-but-uninitialized is reported as `NOT_READY`, separately
from missing API (`NOT_PRESENT`).

Lifecycle (per official docs — never `PlayerJoinEvent`):

```text
ApolloRegisterPlayerEvent
  -> bridge captures UUID/name + opaque player handle
  -> LunarClientService queues work on that player's entity scheduler
  -> readiness + hasSupport(UUID) check in entity context
  -> ApolloPlayer lookup and exact underlying-player identity check
  -> ModSettingModule status lookup for ModMinimap.ENABLED
  -> if not already false: ModSettingModule options set false
  -> player stays online; Apollo result remains separate from probe result
```

The bridge caches Apollo's player manager and `ModSettingModule` after readiness;
no per-player reflection is used. An already-disabled setting is not written
again. The service retains only UUID state and a weak player reference for
pending identity checks. Reconnect creates a new ticket; quit/unregister remove
only a matching connection, and shutdown cancels pending entity tasks before
unregistering Apollo callbacks. Apollo exceptions become a separate `FAILED`
integration/action state and do not enter the probe evaluator.
Apollo plugin disable cancels pending work and unregisters its event handlers;
an enable event reuses the same bridge and reattaches callbacks without
re-resolving API classes.

The active `/vac reload` path replaces only the immutable client-probe registry;
it does not restart the Lunar service or resolve/reload Apollo objects. If client
detection was unavailable at startup, `/vac reload` is rejected rather than
restarting the plugin and disturbing optional integrations; fix the startup
configuration and restart VAntiCheat.

## Paper/Folia

The bridge carries the registration's opaque player handle but does not access
it as a Bukkit player. `LunarClientService` routes player-specific Apollo reads
and the setting mutation through the platform `Scheduler.runAtEntity` path.
That routes to Paper's main scheduler or Folia's entity scheduler, and avoids
world/region access and Bukkit-only scheduler calls.

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

Reports integration readiness (`AVAILABLE`, `NOT_PRESENT`, `NOT_READY`,
`FAILED`, or `DISABLED`), Lunar support (`YES`, `NO`, or `UNKNOWN`), the minimap policy, per-player action
state (`UNKNOWN`, `REGISTERED`, `MINIMAP_DISABLED`, `FAILED`), and last action.
`/vac status` includes integration readiness and last action. Diagnostics
expose no protocol internals.

## Validation Status

- Unit/stub regression tests cover absent, ready, not-ready, idempotent, failed,
  reconnect, shutdown, and entity-scheduler paths.
- Startup diagnostics report the explicit integration state once; detailed
  API failures are logged at debug level.
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
