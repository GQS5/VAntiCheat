# VAntiCheat 0.1.0 — Installation

## Requirements

- Velocity **3.4.x** proxy, Java **21**
- A backend server (Paper/Folia) that Velocity can reach
- The separate Fabric client verifier on player clients (see
  `docs/VERIFIER.md`)

## Layout

```text
Velocity/
  velocity.jar
  velocity.toml
  plugins/
    vanticheat-0.1.0.jar
    VAntiCheat/
      config.yml      (created with defaults on first start)

Backend/
  Paper / Folia server (VAntiCheat is NOT installed here)
```

## Steps

1. Copy `vanticheat-0.1.0.jar` into the Velocity `plugins/` directory.
2. Start Velocity. The plugin initializes and reads
   `plugins/VAntiCheat/config.yml` (defaults are used when absent).
3. Point players at the Velocity address (`<proxy-host>:25565`).

## Backend must remain private

```text
The backend must not be directly reachable by players.
Players must connect through Velocity.
```

Bind backend ports to localhost or firewall them. Any player that
connects directly to Paper/Folia bypasses Velocity — and VAntiCheat —
entirely. VAntiCheat does not solve network-level access control.
Enable Velocity player-info forwarding in production.

## Verify the install

- Console/log shows `Loaded plugin vanticheat 0.1.0` and
  `VAntiCheat 0.1.0 initialized. Enabled: true`.
- `/vac status` reports the version and state.
- A login without the verifier mod is denied before any backend
  connection; a login with the verifier and no forbidden mods joins.
