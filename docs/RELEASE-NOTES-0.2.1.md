# VAntiCheat 0.2.1

Detection correctness, gameplay safety, and the first passive detection path.

Paper and Folia 1.21.11, Java 21.

> ## Read this before upgrading from 0.2.0
>
> This release deliberately reduces what automatic detection does, on purpose.
>
> **0.2.0 opened a sign editor on joining players.** That editor is a client screen: it
> captured the player's input and stopped them moving, repeatedly, for every probe in
> the automatic set. With 27 automatic probes that was up to nine sign editors opened
> on a player's screen as they joined.
>
> In 0.2.1 interactive probes do not run unattended. Automatic join detection now
> covers **1 of 42 probes** instead of 27. The other 41 are available through
> `/vac check`, where an operator accepts the UI cost deliberately.
>
> Two further changes operators should know:
>
> - A confirmed **passive** detection does **not** kick by default. See
>   [Passive identity does not enforce](#passive-identity-does-not-enforce).
> - `CLEAN` now means "we asked and got an answer that identified nothing". A client
>   that resolved a probe's key but rendered it in another language is reported
>   `UNCERTAIN`, not `CLEAN`.

---

## Contents

- [What changed and why](#what-changed-and-why)
- [The gameplay-safety fix](#the-gameplay-safety-fix)
- [Passive detection](#passive-detection)
- [Passive identity does not enforce](#passive-identity-does-not-enforce)
- [Detection coverage](#detection-coverage)
- [What a scan can conclude](#what-a-scan-can-conclude)
- [Locale-independent detection](#locale-independent-detection)
- [What is never evidence](#what-is-never-evidence)
- [Breaking changes](#breaking-changes)
- [Configuration reference](#configuration-reference)
- [Commands and permissions](#commands-and-permissions)
- [Client probe health](#client-probe-health)
- [Security posture](#security-posture)
- [Validation](#validation)
- [Known limitations](#known-limitations)

---

## What changed and why

Two real product problems were reported: detection effectiveness felt close to zero,
and players could effectively not move while a probe ran. Both had the same root
cause, found by auditing the whole detection path and by disassembling the real client
mods and the real Paper 1.21.11 server:

**Everything ran through one interactive mechanism, unattended, on every join.**

The server writes a translatable component onto a temporary sign, opens that sign's
editor on the client, and reads back the text the client submits. The client can only
answer by opening a screen and submitting it, and the server cannot dismiss that
screen. Running that 27 times on join is the movement problem. The same mechanism is
why detection felt weak: a real player who ignores the editor times out, and one who
closes it without the target's text is reported clean or uncertain.

The fix was to declare each probe's transport honestly, stop running interactive
probes unattended, and then go looking for a genuinely non-interactive signal.

## The gameplay-safety fix

Every probe now declares its transport in configuration. It is never inferred from the
probe id.

```yaml
transport: PASSIVE | INTERACTIVE
```

- `PASSIVE` reads an already-available signal. No client UI, no input capture, and
  the only kind allowed to run unattended.
- `INTERACTIVE` needs the client to open and submit UI.

**An absent declaration fails closed to `INTERACTIVE`**, so a newly added probe can
never silently join the automatic path. Admission is enforced in the registry:

```text
automatic-eligible = enabled && automatic && (transport == PASSIVE || auto-check.interactive)
```

No fake fix was applied. There is no movement cancellation, velocity change,
teleport, slowness, freeze, spectator, or movement lock anywhere in the plugin, and
there never was. The problem was hidden by configuration, and configuration was the
correct place to fix it.

## Passive detection

Passive detection was investigated properly before it was built, against real
artifacts rather than assumption. Every configured client was disassembled:

| Client | Passive server-bound signal | Evidence |
| --- | --- | --- |
| Vanilla | `minecraft:brand` only | `BrandPayload` in the server jar |
| Clean Fabric | `minecraft:brand` only | Fabric Loader contains no client-to-server networking; **it sends no mod list** |
| Meteor | **none** | no `registerGlobalReceiver`, no send; `PacketUtils` only maps packet class names |
| AppleSkin | **none** | three `registerGlobalReceiver` calls, all server-to-client |
| Jade | **`jade:client_handshake` on join** | `ClientPlayConnectionEvents.JOIN` handler sends it unconditionally |
| Litematica | `servux:litematics` exists, not join-triggered | no join hook in the jar |
| Forwarded, Cezar, Nova | unknown | no build available |

So there is no generic mod list to read, and none was invented. But Jade proved a real
signal exists, and it proved the delivery path is supported.

The full chain, every link verified against the real Jade jar and the real Paper
1.21.11 server jar:

```text
Jade: ClientPlayConnectionEvents.JOIN
  -> ClientPlayNetworking.send(new ClientHandshakePacket("9"))
  -> inbound custom payload on channel "jade:client_handshake"
  -> server has no codec for that channel, so it decodes as DiscardedPayload,
     which preserves the channel id and the raw bytes
  -> ServerCommonPacketListenerImpl.handleCustomPayload
  -> CraftServer.getMessenger().dispatchIncomingMessage(conn, channel, data)
  -> StandardMessenger dispatches to listeners registered for that exact channel
  -> jade-network-handshake probe: STRONG evidence, automatic, zero UI
```

This uses only the supported Bukkit/Paper plugin-messaging API. No NMS, no Netty, no
packet rewriting, no new dependency, and nothing is ever sent to the client, so it
cannot prompt the client to do anything either.

**Its absence proves nothing.** A client that does not send the channel is `CLEAN`, so
this cannot produce a false positive.

**It is `UNVERIFIED`.** It is proven from bytecode on both sides, not from a live
client session, and must not be promoted without one.

## Passive identity does not enforce

`client-detection.passive.enforce` is `false`.

A confirmed passive detection is reported in `/vac detections` and `/vac status`, but
does not reach enforcement. The only channel proven so far belongs to Jade, a
legitimate utility mod, so kicking players for it automatically would be a false
positive - exactly the class of problem this release exists to remove.

Operators who want passive identity actionable can set the flag, and should understand
that it will then kick Jade users.

## Detection coverage

```text
42 probe definitions
3   VERIFIED     live-validated against a real client on Minecraft 1.21.11
39  UNVERIFIED   no live evidence
5   DISABLED     intentionally not executed, with a recorded reason
1   PASSIVE      answered by an observed protocol channel, no client UI
41  INTERACTIVE  answered through the sign editor, manual verification only
37  manual-eligible
1   automatic-eligible   (28 only with auto-check.interactive: true)
4   DETECTED-capable
```

`VERIFIED` remains 3. It was not inflated.

**What percentage of the catalog is detectable automatically without opening a client
UI? 1 of 42, about 2.4%.** That number is not optimized. Most client mods transmit
nothing about themselves, and the ones that do are legitimate utilities.

VERIFIED, with the exact versions recorded in each probe's notes:

| Probe | Version | Observable evidence |
| --- | --- | --- |
| `meteor-client` | Meteor Client 1.21.11-86 | client resolves `key.meteor-client.open-gui` |
| `apple-skin` | AppleSkin 3.0.8 | client resolves `text.autoconfig.appleskin.title` |
| `jade-config-screen` | Jade 21.1.6 | client resolves `gui.jade.configuration` |

Detection is verified only for those exact mod and Minecraft versions. A newer build
of the same mod is not covered by that evidence.

Five disabled probes, each with a recorded reason:

| Probe | Reason |
| --- | --- |
| `xaeros-minimap` | known legitimate-client false positive |
| `itemscroller` | identifier matches a fabricated template with no confirmable artifact |
| `cezar-client` | requested, but no build available to confirm an identifier |
| `nova-client` | requested, but no build available to confirm an identifier |
| `forwarded` | requested, but no build available to confirm an identifier |

The three requested clients use a visibly marked placeholder key. They are disabled on
purpose: a placeholder key resolves to nothing on a real client, so an enabled probe
would report `CLEAN` forever and give false assurance that the client was being
checked.

## What a scan can conclude

| Conclusion | Meaning |
| --- | --- |
| `DETECTED` | Exact structured identity, an exact configured response, or a client resolved a key only the target defines |
| `CLEAN` | The client answered and nothing indicated a probed target |
| `UNCERTAIN` | The client rendered translated text with no identity attached |
| `PROTECTED` | The response channel was neutralized |
| `TIMEOUT` | No answer inside the deadline; never clean, never detected |
| `UNSUPPORTED` | The transport cannot carry this probe |
| `ERROR` | Transport, scheduling, or processing failure; never a detection |
| `SKIPPED` | Policy or eligibility stop |

Scans no longer add detections up numerically. A detection requires authoritative
evidence for a specific probe. Multiple weak signals are reported `UNCERTAIN` together
with the reason they were not promoted, and a `DETECTED` that arrives without
authoritative evidence is refused defensively.

## Locale-independent detection

A probe that declares `identity-resolution: true` accepts **any** non-sentinel
resolution of its own target-specific key, not only the recorded default-locale
string.

This is a real false-negative fix. In 0.2.0 the three verified probes matched an exact
en_us string, so a Meteor user running a French or Japanese client was reported
`CLEAN`. The sentinel check still guarantees a client without the key is never
detected.

## What is never evidence

None of these can produce a detection, by design and by test:

- substring, prefix, or partial matches
- generic visible text or arbitrary localized text
- a guessed or merely plausible mod identifier, including the disabled placeholders
- client brand, including Fabric brand - a Fabric client running Meteor and one
  running only Sodium present the same brand
- timing, latency, or absence of a response
- a probe id or category that merely looks right
- a channel name that is merely similar to a declared one

`/vac probe <id>` shows, per probe, the evidence quality, the observable artifact, the
expected clean and target behaviour, and whether the probe can reach `DETECTED` at all.

## Breaking changes

1. **Interactive probes no longer run automatically.** With the default
   `auto-check.interactive: false`, an automatic join scan opens no client UI and
   mutates no world. Use `/vac check <player>` for interactive verification.
2. **`transport` is required metadata** and defaults to `INTERACTIVE`.
3. **A resolved-but-unmatched response is now `UNCERTAIN`, not `CLEAN`.**
4. **A `PASSIVE` probe may not declare `expected-response` or `identity-resolution`**,
   and an `INTERACTIVE` probe may not declare a `passive-channel`.
5. **`VERIFIED` requires evidence metadata** on any probe, including passive ones.
6. **Passive-only detections do not enforce** unless `passive.enforce: true`.

### Migration

- The legacy `auto-check.probes` list syntax is still parsed into per-probe
  `automatic` policy. It cannot bypass the transport gate.
- Existing `manual`, `automatic`, `verification`, `category`, `source`, `notes`, and
  `expected-response` fields are preserved as authored.
- New keys default safely when absent: `transport` to `INTERACTIVE`,
  `identity-resolution` to `false`, `passive.enforce` to `false`.
- Reload is pre-validated; a rejected configuration leaves the last known-good
  registry active.
- **If you want the old unattended behaviour back**, set
  `client-detection.auto-check.interactive: true`, after reading the caveat in
  `client-detection.yml`. It will repeatedly open a sign editor on joining players and
  block their movement while it waits.

## Configuration reference

New keys in `client-detection.yml`:

```yaml
client-detection:
  passive:
    enforce: false        # may a passive-only detection reach enforcement
  auto-check:
    interactive: false    # may INTERACTIVE probes run unattended (captures player input)
  probes:
    <id>:
      transport: PASSIVE | INTERACTIVE   # required; defaults to INTERACTIVE
      passive-channel: "mod:channel"      # required for PASSIVE, rejected for INTERACTIVE
      identity-resolution: true           # key is defined only by this target
```

`/vac status` reports `Probes passive/interactive`, `Automatic-eligible`,
`Blocked-by-transport`, passive observer state, and per-channel observation counts.
`/vac status verbose` adds the eligibility breakdown, evidence strengths, and the
conclusion reason for every scan. No raw payloads are shown or stored.

## Commands and permissions

Unchanged in 0.2.1, and now the primary way to run interactive detection:

| Command | Permission | Purpose |
| --- | --- | --- |
| `/vac check <player>` | `vanticheat.probe` | manual interactive scan |
| `/vac check <player> <probe-ids>` | `vanticheat.probe` | manual scan of specific probes |
| `/vacprobe <player>` | `vanticheat.probe` | configured manual probes |
| `/vac status [verbose]` | `vanticheat.status` | runtime health and coverage |
| `/vac probes` | `vanticheat.admin` | catalog with per-probe status |
| `/vac probe <probe-id>` | `vanticheat.admin` | full validity row for one probe |
| `/vac detections` | `vanticheat.status` | recent confirmed detections |
| `/vac reload` | `vanticheat.reload` | pre-validated atomic reload |
| `/vac lunar <player>` | `vanticheat.admin` | Lunar/Apollo policy state |
| `/vac trust add\|remove\|list` | `vanticheat.admin` | trusted-player management |

## Client probe health

Separate from detection, and never a detection: `READY`, `RESPONSIVE`, `UNSUPPORTED`,
`UNRESPONSIVE`, `TIMED_OUT`, `INTERRUPTED`. This is transport and client readiness
only. It says nothing about a player's hardware, and `ClientProbeHealth` exposes a
`detectionSignal()` that is `false` for every value, asserted by test.

## Security posture

```text
gameplay-freeze mechanisms              0
direct scheduler use            only PaperFoliaScheduler
NMS/Netty access added                  0
anything sent to a client               0
raw payload retention or logging        0
network I/O                             0
embedded secrets                        0
path traversal                          0
```

Optional integrations (Apollo, Floodgate, Geyser) are `softdepend` and resolved
through the plugin manager; absence degrades to a documented state.

## Validation

```text
artifact  vanticheat-0.2.1.jar
size      334308 bytes
sha256    777caea21a6503c216103cbc184fea729f90a691fef149c82c11fa75f783dc5d

332 tests, 0 failures, 0 errors, 0 skipped
stable across three consecutive full mvn clean test runs
mvn package successful
git diff --check clean
```

Coverage includes Bedrock zero-probe, `UNKNOWN` fail-closed, every result status, the
lifecycle and exactly-once invariants, the P31.1 cleanup-target behavior, the
transport gate, passive evidence binding and rejection, reconnect and disconnect
isolation, memory bounds, brand-is-context-only, and fourteen false-positive attack
cases.

## Known limitations

- **Automatic coverage is 1 of 42 probes.** Interactive verification is the only route
  for the rest.
- `jade-network-handshake` is `UNVERIFIED`, proven from bytecode only.
- Passive signals are client-asserted; a modified client can suppress them.
- **Meteor, the primary cheat target, has no passive signal** and remains manual only.
- The three requested clients are disabled with placeholder keys.
- `xaeros-minimap` stays disabled for its false-positive history.
- Server-observable movement and combat behavior detection is still not part of the
  product, and this release did not add any.
- Bedrock isolation, the Apollo integration, and the three verified interactive
  results were not re-validated live in this release.
