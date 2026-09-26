# VAntiCheat 0.1.3

## Change

- Enabled the `xaeros-worldmap` probe and automatic selection in the bundled
  `client-detection.yml`.
- `xaeros-minimap` remains disabled.

Existing server configuration files are preserved. Add `xaeros-worldmap` to
`client-detection.auto-check.probes` manually, then run `/vac reload` or
restart the server.

The Xaero World Map probe remains unverified and may not identify every client
version reliably.
