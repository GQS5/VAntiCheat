# VAntiCheat 0.1.4

## Fix

- Fixed translation probes with empty fallbacks being incorrectly classified
  as `CLEAN` for every non-empty response.
- Xaero World Map and Litematica responses are now evaluated normally when the
  probes are enabled and selected.

The bundled policy keeps `xaeros-worldmap` and `litematica` enabled and
`xaeros-minimap` disabled. Existing server configuration files remain
preserved and must contain the enabled probes and automatic probe entries.

## Validation

- 105 automated tests pass.
- `mvn clean test`, `mvn clean package`, and `git diff --check` pass.
