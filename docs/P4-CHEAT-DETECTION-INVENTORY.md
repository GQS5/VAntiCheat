# P4 - Cheat Detection Inventory and Priority Audit

**Status: COMPLETE**

This is an audit only. No detection logic, production configuration, or
enforcement behavior was changed.

## 1. Executive Summary

VAntiCheat should not treat a client name as proof of cheating. The current
sign-probe module can establish that a client or module exposes a translation
key, but that only proves an installed/usable client signal. It does not prove
that a capability is active.

The next implementation priority should therefore be:

1. **P0:** server-observable active combat and movement automation: KillAura,
   autoclicking, reach, scaffold, fly, speed, and impossible movement/action
   combinations.
2. **P1:** a small, confirmation-only identity layer for current full cheat
   clients, beginning with Meteor, LiquidBounce, and Wurst. Use it for evidence
   and triage first, not unconditional kicks.
3. **P1:** Baritone and automation behavior where it materially affects the
   server, rather than treating Baritone installation as a violation.
4. **P2:** AutoFish, mining automation, and world-download activity where the
   server can observe the action or where the server policy explicitly forbids
   it.

Freecam, ESP, X-Ray, shaders, performance mods, Litematica, Item Scroller,
Xaero's maps, SeedCrackerX, and similar client-local features should not be
   enforced by the current sign-probe architecture. Their presence is not
   equivalent to active abuse, and most have no reliable vanilla-server signal.

## 2. Detection Philosophy

### Identity

Identity asks: "Does this client/module expose a recognizable client signal?"
The answer may be useful evidence, but it is not proof that a feature is being
used. Identity probes are vulnerable to renamed keys, forks, custom builds,
missing translations, and deliberate spoofing.

### Capability

Capability asks: "Could this client perform a forbidden action?" A capability
may be installed but inactive. A sign probe can at most indicate availability;
it cannot establish use.

### Behavior

Behavior asks: "Did the player produce server-observable actions that are
consistent with the forbidden capability?" This is the correct basis for
enforcement, with multiple observations and confirmation. It is also the part
that requires a new server-side behavior module and is intentionally out of
this audit implementation.

The operational states must remain distinct:

| State | Meaning |
| --- | --- |
| `INSTALLED` | A client-side artifact appears present. |
| `ACTIVE` | The player appears to have enabled the feature. |
| `USABLE` | The feature could affect play in the current context. |
| `OBSERVABLE` | The server has a meaningful signal. |
| `CONFIRMED` | Independent evidence is sufficient for enforcement. |

The current rule remains `INITIAL DETECTED -> CONFIRMATION -> CONFIRMED POSITIVE
-> KICK`. Uncertain identity or capability evidence must not kick.

## 3. Current Repository Map

- One active detection module: `client-detection.checkhacks`.
- One transport: Paper sign placement plus 1.21.11 NMS packets.
- One evaluator: `CheckHacksResponseEvaluator`.
- Automatic join checks and confirmation-only enforcement are present.
- Paper and Folia scheduler abstractions are present.
- There is no active Freecam, ESP, X-Ray behavior, combat, movement, packet,
  or inventory behavior detector.
- There is no mandatory client mod, Velocity verifier, or old verifier package.
- The configured inventory contains 28 probes, all currently marked
  `UNVERIFIED` in `client-detection.yml`.

## 4. Full Cheat Client Inventory

The table separates relevance from proof. "Identity" means a probe may identify
an exposed key; it does not mean the server can prove a feature is active.

| Client | Type | Modern Relevance | Server Detectability | False Positive Risk | Proposed Priority |
| --- | --- | --- | --- | --- | --- |
| Meteor | Fabric utility/cheat client | Maintained and explicitly aimed at anarchy servers; current repository is active, but Minecraft-version compatibility must be pinned per release | Identity: possible. Behavior: only through observed actions | Medium for identity, low for behavior with independent evidence | P1 |
| LiquidBounce | Fabric hacked client | Maintained and actively developed; official repository describes it as a hacked client | Identity: possible. Behavior: possible through actions | Medium for identity; behavior depends on signal quality | P1 |
| Wurst | Fabric cheat client | Credible modern evidence includes an official `v7.55.1-MC1.21.11` tag | Identity: possible. Behavior: possible through actions | Medium for identity; low-to-high for individual behavior | P1 |
| Baritone | Automation/pathfinding library and client component | Maintained modern branches include 1.21.x support; used both independently and inside cheat clients | Installation: weak. Automated movement/mining: observable over time | High if installation is treated as cheating; lower for repeated automation | P1 behavior, P3 identity |
| BleachHack | Older Fabric cheat client | Official repository evidence found is centered on older 1.18/1.19-era branches, not credible current 1.21 maintenance | Identity: weak and version-sensitive | High due stale keys/forks | P3 |
| Aristois | Multi-version utility/cheat client | Current 1.21 maintenance and public server impact are not established by this audit | Identity: weak | High | P3 |
| Coffee Client | Client-specific cheat identity | Current 1.21 relevance not established | Identity: weak | High | OUT |
| Glazed | Client/add-on identity | Current 1.21 relevance and semantics not established | Identity: weak | High | OUT |
| Trouser Streak family | Exploit/utility add-ons | Niche and version-sensitive; several entries are add-on-specific keys | Identity: weak | High | OUT |

### Research basis

- [Meteor Client repository](https://github.com/MeteorDevelopment/meteor-client)
  describes Meteor as a Fabric utility mod for anarchy servers and shows active
  development.
- [LiquidBounce repository](https://github.com/CCBlueX/LiquidBounce) describes
  LiquidBounce as a Fabric hacked client using mixin injection.
- [Wurst 1.21.11 tag](https://github.com/Wurst-Imperium/Wurst7/tree/v7.55.1-MC1.21.11)
  is direct evidence of a current-version build line and identifies Wurst as a
  cheat client.
- [Baritone features](https://github.com/cabaletta/baritone/blob/1.21.4/FEATURES.md)
  documents pathing, block breaking, block placing, mining goals, and automated
  movement. Those actions matter more than installation identity.
- The absence of current-version evidence for BleachHack, Aristois, Coffee,
  Glazed, and the Trouser Streak entries is intentional; popularity claims are
  not being used as evidence.

## 5. Individual Cheat and Capability Inventory

| Capability | Type | Server Detectability | Confirmation Potential | False Positive Risk | Proposed Priority |
| --- | --- | --- | --- | --- | --- |
| KillAura / automated targeting | CHEAT | Attack timing, target selection, rotation/action coupling, and impossible target transitions can be observed | High only with multiple combat observations; never from one click | Medium | P0 |
| AutoClicker | CHEAT | Click interval distribution and combat context can be observed, but legitimate high CPS and network effects complicate it | Medium with long-window evidence | Medium-high | P0 |
| Reach | CHEAT | Server can measure attack origin, target distance, latency, and geometry | High when repeated and latency-adjusted | Medium | P0 |
| Scaffold | CHEAT | Block placement sequence, rotation, movement, and timing are observable | Medium-high with repeated placements | Medium | P0 |
| Fly / Speed / NoFall | CHEAT | Movement and fall-damage invariants are server-observable | High for repeated impossible states; server/platform physics must be respected | Medium | P0 |
| Packet manipulation / blink / disablers | CHEAT | Packet ordering and movement/action consistency may be observable, but requires protocol-aware analysis | Medium | High | P1 |
| Inventory automation / ChestStealer | CHEAT | Inventory click cadence and container transfer patterns can be observed | Medium; legitimate macros and accessibility are concerns | Medium-high | P1 |
| Baritone automated movement/mining | AMBIGUOUS | Long-window pathing, repetitive mining, and action cadence may be observable | Medium; require context and repetition | Medium | P1 |
| Mining automation / Nuker | CHEAT | Block-break timing and target selection can be observed | Medium-high with world/context checks | Medium | P1 |
| AutoFish | CHEAT | Repeated fishing interactions and reaction timing can be observed | Medium over a long sample | Medium | P2 |
| World download / map extraction | AMBIGUOUS | Server can observe chunk requests only imperfectly; downloaded data is local | Low without server-specific policy/telemetry | Low for ordinary clients | P2 policy, otherwise OUT |
| X-Ray | CHEAT | Installed/rendered state is not visible; mining choices may provide indirect evidence | Low-to-medium behavioral evidence only | High in ordinary mining | P2 |
| Freecam | CHEAT | Usually render/camera-local; server sees no camera state | Low; only secondary interaction evidence | High | P3 / OUT current architecture |
| ESP / entity highlighting | CHEAT | Render-only in the usual implementation | None directly | High | OUT |
| Chest/container ESP | CHEAT | Render-only unless it changes search/actions | Low | High | OUT |
| Fly camera / spectator-like camera | CHEAT | Camera state is local; actual movement remains observable | Low for camera-only use | High | OUT current architecture |
| Jesus | CHEAT | Water movement can be observed, but vanilla mechanics and lag create edge cases | Medium with repeated samples | Medium-high | P2 |
| Auto-sprint / movement convenience | AMBIGUOUS | Often indistinguishable from ordinary input | Low | High | OUT |

### Strategy conclusions

| Candidate | Realistic strategy |
| --- | --- |
| Combat, reach, scaffold, fly, speed, NoFall | Strategy C/D: server behavior plus independent evidence |
| Baritone, mining, inventory automation | Strategy C/D: long-window behavior, optionally identity as supporting evidence |
| Current full-client identity | Strategy A for triage/evidence; never sufficient alone for a behavior kick |
| X-Ray, Freecam, ESP, container ESP | Strategy E in the current architecture; only indirect behavior is realistic |
| World download | Strategy D only if the server establishes an explicit policy and observable protocol signal |
| Render/performance mods | No detection target; allow by default |

## 6. Legitimate Client Mod Inventory

These must not be automatically treated as cheating:

| Mod | Classification | Reason it must remain allowed |
| --- | --- | --- |
| Xaero's Minimap | UTILITY | Navigation/minimap functionality; P3 live evidence proves the current probe can identify it, not that it is malicious |
| Xaero's World Map | UTILITY | Client map rendering and exploration aid; no direct proof of cheating |
| Litematica | UTILITY / AMBIGUOUS | Schematic planning and placement assistance; some servers may regulate printer-like features, but installation alone is not proof |
| Item Scroller | UTILITY | Inventory/UI convenience; no automatic cheating conclusion |
| Sodium | UTILITY | Rendering and performance optimization; official project describes it as a rendering engine replacement |
| Lithium | UTILITY | Performance optimization that explicitly does not change gameplay functionality |
| Iris | UTILITY | Shader loader/rendering mod; official project describes shader-pack compatibility and rendering goals |
| Fabric Loader/API | UTILITY | Mod-loader infrastructure, not a cheat capability |
| AutoSwitch | UTILITY / AMBIGUOUS | Item-selection convenience; behavior policy may differ, but identity alone is insufficient |
| AntiAFK | AMBIGUOUS | May violate an idle policy, but is not a combat cheat and should be a server-policy decision |
| SeedCrackerX | UTILITY / AMBIGUOUS | World-seed recovery can be disallowed on some servers, but does not prove combat or movement cheating |
| Baritone | AMBIGUOUS | Can automate travel/mining and is also used as a library; assess active behavior and server policy |

Sources: [Sodium](https://github.com/CaffeineMC/sodium),
[Lithium](https://github.com/CaffeineMC/lithium),
[Iris](https://github.com/IrisShaders/Iris), and the existing P3 live Xaero
validation. Performance/rendering mods have no useful vanilla-server identity
signal that justifies enforcement.

## 7. Existing 28-Probe Audit

Reliability describes the probe as currently designed, not the reputation of
the named project. All current config entries remain `UNVERIFIED` in the
repository; this is a proposed future disposition only.

| Probe | Current Purpose | Category | Reliability | Proposed disposition |
| --- | --- | --- | --- | --- |
| `meteor-client` | Meteor translation/key identity | Client identity | Medium-low; version/key dependent | REVIEW |
| `liquidbounce` | LiquidBounce module translation identity | Client identity | Medium-low; version/key dependent | REVIEW |
| `freecam` | Freecam keybind identity | Capability identity | Low; camera is local and legitimate freecam variants differ | REMOVE |
| `wurst` | Wurst keybind identity | Client identity | Medium-low; current key may not match all builds | REVIEW |
| `xray-fabric` | X-Ray keybind identity | Capability identity | Low; installed state is not active use | REVIEW |
| `chestesp` | Chest ESP keybind identity | Capability identity | Low; render-only and ambiguous | REMOVE |
| `killaura-fabric` | KillAura keybind identity | Capability identity | Low as identity; high-value behavior target | REPLACE |
| `autofish` | AutoFish keybind identity | Capability identity | Low as identity; behavior can be sampled | REPLACE |
| `lumina` | Lumina click-GUI identity | Client identity | Low; current relevance and key semantics unclear | REMOVE |
| `autoswitch` | Item-switch keybind identity | Utility identity | Low; legitimate utility | REMOVE |
| `bleachhack` | BleachHack translation identity | Client identity | Low; stale current-version evidence | REMOVE |
| `aristois` | Aristois translation identity | Client identity | Low; version/current-use evidence weak | REMOVE |
| `coffee` | Coffee Client translation identity | Client identity | Low; current semantics unverified | REMOVE |
| `world-downloader` | World Downloader translation identity | Ambiguous capability | Low as identity; policy/activity may be separate | REVIEW |
| `autoclicker-fabric` | AutoClicker translation identity | Capability identity | Low as identity; behavior is high value | REPLACE |
| `antiafk` | AntiAFK translation identity | Utility/policy | Low; not inherently a cheat | REMOVE |
| `auto-clicker-mc` | AutoClicker keybind identity | Capability identity | Low as identity; behavior is high value | REPLACE |
| `trouser-streak` | Add-on translation identity | Client/add-on identity | Low; niche and version-sensitive | REMOVE |
| `ui-utils` | UI Utils translation identity | Utility identity | Low; legitimate utility | REMOVE |
| `seeedcrackerx` | SeedCrackerX translation identity | Utility/ambiguous | Low; policy-specific, not proof of abuse | REMOVE |
| `simple-world-downloader` | World downloader translation identity | Ambiguous capability | Low as identity; policy/activity may be separate | REVIEW |
| `trouser-streak-newchunks` | Add-on translation identity | Client/add-on identity | Low; niche and version-sensitive | REMOVE |
| `trouser-streak-anhero` | Add-on translation identity | Client/add-on identity | Low; niche and version-sensitive | REMOVE |
| `glazed` | Add-on/client translation identity | Client identity | Low; current semantics unclear | REMOVE |
| `litematica` | Litematica menu identity | Utility/ambiguous | Low as enforcement evidence; legitimate use is common | REMOVE |
| `itemscroller` | Item Scroller menu identity | Utility identity | Low; legitimate use is common | REMOVE |
| `xaeros-minimap` | Xaero's Minimap translation identity | Utility identity | It is live-detectable but is a false-positive enforcement target | REMOVE |
| `baritone` | Baritone setting translation identity | Ambiguous automation | Low as installation identity; active behavior is meaningful | REPLACE |

`REMOVE` means remove from future enforcement inventory, not delete it during
this audit. `REVIEW` means retain only as non-enforcing evidence until its
version and semantics are verified. `REPLACE` means replace the identity probe
with a future behavior detector, not add a second arbitrary identity probe.

## 8. Recommended VAntiCheat Priority Map

### P0

- Combat automation: KillAura/aim assistance, reach, and autoclicking, using
  server-observable repeated evidence.
- Movement/build automation: fly, speed, NoFall, and scaffold, using physics,
  placement, and action consistency.
- Confirmation must be multi-observation and latency-aware. A single unusual
  packet, click, rotation, or movement sample must not kick.

These have the highest practical impact on PvP, survival, and economy and are
the capabilities a vanilla server can actually observe.

### P1

- Meteor, LiquidBounce, and Wurst identity as supporting evidence and triage,
  with pinned version fixtures and no identity-only kick.
- Baritone active pathing/mining behavior, not Baritone installation.
- Inventory automation and ChestStealer-like repeated container actions.
- Packet manipulation/disabler patterns when they produce an observable
  protocol or gameplay invariant violation.
- Mining automation/Nuker when repeated block actions distinguish it from
  ordinary mining.

### P2

- AutoFish behavior over a long sample.
- Jesus and other water-movement invariants.
- X-Ray only through indirect mining/resource-selection evidence; no client
  identity kick.
- World download only as a separately configured server-policy signal with
  actual observable evidence.

### P3

- Legacy/outdated full-client identity probes such as BleachHack and Aristois.
- Niche add-on identities such as Coffee, Glazed, and Trouser Streak.
- Freecam capability identity where only camera state is involved.
- Low-impact utility/policy capabilities such as AntiAFK, provided they are
  handled as explicit server policy rather than cheat proof.

### OUT

- Xaero's Minimap/World Map, Sodium, Lithium, Iris, Fabric API/Loader,
  Item Scroller, ordinary Litematica use, and ordinary UI/accessibility mods.
- Render-only ESP and chest highlighting.
- Camera-only Freecam in the current server architecture.
- Any mandatory client installation or client-side verifier.
- Reintroduction of Velocity verification or legacy CheckHacks packages.

## 9. Next Implementation Phase

The next implementation phase should be a narrowly scoped **P0 behavior
foundation**, not a larger probe inventory:

1. Define server-observable evidence models for movement, attack distance,
   attack timing, rotation/action coupling, block placement, and click cadence.
2. Add deterministic unit tests for latency, tick boundaries, legitimate edge
   cases, and confirmation state transitions.
3. Implement only one P0 detector first, preferably reach or an invariant-based
   movement check, behind the existing detection/session/enforcement contracts.
4. Keep identity probes informational until version-pinned fixtures show a
   stable signal and false-positive policy is explicit.
5. Do not add Freecam, ESP, or other render-only detectors unless a new
   server-observable signal is demonstrated first.

No item in this section is implemented by P4.

## 10. Research Sources and Limits

Primary sources used for current claims:

- [Meteor Client](https://github.com/MeteorDevelopment/meteor-client)
- [LiquidBounce](https://github.com/CCBlueX/LiquidBounce)
- [Wurst 1.21.11 tag](https://github.com/Wurst-Imperium/Wurst7/tree/v7.55.1-MC1.21.11)
- [Baritone 1.21.4 features](https://github.com/cabaletta/baritone/blob/1.21.4/FEATURES.md)
- [Sodium](https://github.com/CaffeineMC/sodium)
- [Lithium](https://github.com/CaffeineMC/lithium)
- [Iris](https://github.com/IrisShaders/Iris)

Repository activity and feature documentation establish project intent and
version evidence; they do not establish prevalence on Hexa or prove that a
client feature is active. Prevalence, exact Hexa policy, and server-specific
packet behavior remain unresolved research questions.

## 11. Audit Decision

P4 audit is **COMPLETE**. The locked priority is behavior-first:

```text
P0: active combat/movement invariants
P1: full-client identity as supporting evidence plus automation behavior
P2: lower-frequency automation and indirect X-Ray/world-download signals
P3: niche or stale identity signals
OUT: legitimate/render-only/local-only features and mandatory client checks
```
