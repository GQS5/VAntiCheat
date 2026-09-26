# Trusted Players

## Commands

Administrators with `vanticheat.admin` may use:

```text
/vac trust PlayerName
/vac trust add PlayerName
/vac trust remove PlayerName
/vac trust list
```

Console is always authorized. In-game players require the dedicated permission; ordinary players cannot modify the list. The command provides online-player completion and trusted-name completion for removal.

## Persistence and identity

Trusted players are stored in `plugins/VAntiCheat/data/trusted-players.yml`. UUID is authoritative, while the current name is retained for readable listings. Known offline players can be trusted through the server's cached offline-player lookup; no network lookup occurs from the command or enforcement path. Entries survive restart and are written with an atomic temporary-file replacement where supported.

## Enforcement policy

The trusted check is centralized in `EnforcementService`. Every current enforcement caller, including manual client probes and automatic join checks, still performs detection and produces evidence. If the target UUID is trusted, the enforcement decision is changed to `NONE` and the executor is not called. The result remains detected/confirmed rather than being relabeled clean.

Removing trust updates the in-memory set immediately, so subsequent enforcement follows the normal policy without restart. Trusted status is not invisibility; it means only that VAntiCheat does not punish the player.
