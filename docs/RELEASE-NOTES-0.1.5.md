# VAntiCheat 0.1.5

## Simpler Configuration

Automatic checks now use every probe with `enabled: true`. To enable or
disable a probe, change only its `enabled` value in `client-detection.yml`.
The separate `auto-check.probes` ID list is no longer required.

Current bundled policy:

```text
xaeros-worldmap: enabled
litematica: enabled
xaeros-minimap: disabled
```

Existing configuration files are preserved. Remove the old ID list if desired;
the plugin will use each probe's `enabled` value automatically.
