# Client Map Policy

## Xaero Minimap

`xaeros-minimap` is currently disabled in the bundled and disposable Folia
server configuration. It is therefore not selected for either `/vac check` or
automatic join checks. The manual and automatic paths share the same probe
definitions, enabled filtering, evaluator, double-check rules, and
`EnforcementService`.

The current configuration must not be treated as proof that Xaero Minimap is a
verified prohibited-client signal. A real Xaero client session is still needed
to establish the exact probe response, confirmation result, and kick behavior.

## Xaero World Map

`xaeros-worldmap` is enabled in the current configuration and is selected for
automatic checks. Its response and enforcement behavior have not been
validated with a real Xaero client. Minimap and World Map are separate probes;
one does not prove the other.

## Lunar

Lunar Client is allowed. Lunar Minimap is controlled separately through the
official Apollo runtime and `ModMinimap.ENABLED`; it is not a Xaero detector and
does not produce a kick.

## Runtime Trace

With debug enabled, VAntiCheat records the trigger, UUID, selected probe IDs
and display names, each initial/confirmation pass, response outcome, final
result, and centralized enforcement decision. This is required before marking
a real Xaero signal as verified.
