# P10 Scaffold Detection

Scaffold detection is an observe-only behavior module. It does not cancel block placement, alter movement, or invoke enforcement.

The Paper adapter records immutable placement snapshots at the block-place boundary. The platform-neutral detector keeps bounded per-player state and correlates placement timing, adjacent support blocks, placement geometry, recent rotation, and recent movement.

Signals are emitted only when evidence is repeated. Fast placement, diagonal movement, crouching, stair-like placement, or legitimate bridging are not sufficient by themselves. Missing, uncertain, delayed, special-movement, or reset-sensitive samples break the sequence and suppress high-confidence output.

Configuration:

```yaml
behavior-detection:
  movement:
    scaffold:
      enabled: true
      mode: observe
```

Evidence is delivered through `BehaviorModuleContext.scaffoldEvidenceSink()` and remains separate from enforcement. Trusted players still generate telemetry and evidence; Trusted Players only affect enforcement.
