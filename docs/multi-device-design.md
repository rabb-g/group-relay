# Multi-device relay — design study

Status: **specification and recommendation, not a plan of record.** No code is proposed for
writing. Several sections end in a question for the owner rather than an answer, and those are
marked. The concluding recommendation is that a **different, already-specified phase probably gets
most of the benefit for a fraction of the risk** — §12 makes that case plainly so it can be
disagreed with.

Inputs: `jrelay-handoff.md` (§1 deployment context, Phase 4), `docs/phase3-redesign.md` (the
sub-group model, partly built), `docs/audit-2026-09.md` (§1 carrier line, §2 harm to members, §5
verified-clean list), and the current source under `app/src/main/java/com/sh7411usa/jrelay/`.

---

## 1. The central tension, with numbers

The proposal is: several phones, each hosting some of the members (or, under Phase 3, some of the
sub-groups), so that N phones means N carrier lines and N × ~1,000 messages/day of ceiling.

The objection is that coordination is not free. A post arriving at phone A must reach members
hosted by B, C and D. If relays coordinate over SMS, that coordination **spends the very resource
the split exists to conserve**, and it scales with N.

So: quantify it.

### Traffic model

From the handoff §1: ~100 members, ~10 posts/day, ~30 replies/day. Today, on one line, in Reply
mode:

```
posts    10 × 99 =   990
replies  30 ×  1 =    30
                  -------
                   1,020 sends/day on one line, against a ~1,000/day ceiling
```

That is the number that makes this proposal interesting. The group is not near the edge; it is
over it.

### Coordination cost, per post

Assume members are split into N pools of 100/N, each pool hosted by one relay, and assume a post
is coordinated **once per relay, not once per recipient** — one relay-to-relay message carries the
post, and the receiving relay fans it out to its own pool. (§1.3 shows what happens if that
assumption is violated. It is the whole ballgame.)

For one post originating at relay X:

| | sends |
|---|---|
| X → each other relay (coordination) | N − 1 |
| each relay → its own pool (delivery) | 99 total across all relays |
| **system-wide total** | **99 + (N − 1)** |

At N = 2 that is 100 sends where one phone needed 99. Coordination costs **1%**.

But system-wide total is the wrong figure — the ceiling is per line. Per line, per day, counting
posts only:

```
delivery   10 posts × (100/N) members            = 1000/N
coordination  (10/N posts originated here) × (N−1) = 10(N−1)/N
```

| N | delivery/line/day | coordination/line/day | total/line/day | coordination share |
|---|---|---|---|---|
| 1 | 1,000 | 0 | **1,000** | 0% |
| 2 | 500 | 5 | **505** | 1.0% |
| 3 | 333 | 6.7 | **340** | 2.0% |
| 4 | 250 | 7.5 | **258** | 2.9% |
| 6 | 167 | 8.3 | **175** | 4.8% |
| 10 | 100 | 9 | **109** | 8.3% |

**Coordination does not eat the gain.** It cannot, at this traffic volume, because one
coordination message buys an entire fan-out of 100/N recipients. Per-line load falls
monotonically and asymptotes at ~10 sends/day — the floor being one coordination message per post
per relay.

This is worth stating clearly because it is the opposite of the intuition the request was built
on. **The argument against many phones is not arithmetic.** It is operational (N devices to keep
charged, patched, awake past Doze, and physically present) and correctness (N copies of the
member list that can disagree). Those are covered in §5 and §7 and they are the real limits.

### 1.1 Where coordination *does* bite: stacked on Phase 3

The overhead *share* grows as N²/100 — it is small only because delivery is expensive. The moment
delivery gets cheap, coordination dominates.

Phase 3 makes delivery cheap: one group MMS per sub-group of 9, so a post costs ~11 sends on one
phone instead of 99. Redo the table with Phase 3 in force (deliveries per relay per post =
11.1/N):

```
per line/day = 10 × (11.1/N)  +  10(N−1)/N   =   101/N + 10
```

| N | delivery/line/day | coordination/line/day | total | coordination share |
|---|---|---|---|---|
| 1 | 111 | 0 | **111** | 0% |
| 2 | 55 | 5 | **60** | 8.3% |
| 3 | 37 | 6.7 | **44** | 15% |
| 4 | 28 | 7.5 | **35** | 21% |
| 6 | 18 | 8.3 | **27** | 31% |

At N = 4, a fifth of each line's budget is phones talking to each other. More importantly, look at
the absolute numbers: **Phase 3 alone on one phone (111/day) already beats four coordinated phones
without it (258/day/line), and beats it on a single line.**

> **Finding: Phase 3 and multi-device are substitutes, not complements.** They compete for the
> same headroom, and Phase 3 takes most of it first. Once Phase 3 is in production at ~111
> sends/day on one line against a ~1,000/day ceiling, there is a **9× margin** and the capacity
> argument for a second phone evaporates. What survives is the redundancy argument (§8), which is
> a different problem with a much cheaper answer (§11 Phase A/B).

This finding should be checked against the caveat in `phase3-redesign.md` §6: if the carrier
meters a 9-recipient MMS as nine deliveries rather than one, Phase 3's headline saving is
illusory, Phase 3's per-line figure reverts to ~1,000/day, and the capacity argument for
multi-device comes straight back. **Whether to build multi-device at all depends on a question
Phase 3 has already flagged as unresolved.** That is the single most useful sentence in this
document.

### 1.2 Replies

Replies are 30/day and cost 1 send each in Reply mode (poster-only) or 1 MMS under Phase 3's
"poster's group only". Under multi-device, a reply from a member hosted by X to a poster hosted by
Y (probability (N−1)/N) costs **2 sends instead of 1** — one coordination hop plus one delivery.
That is a genuine doubling, of a 30/day quantity. 60/day. Ignore it.

The alternative — X texts the poster's number directly, since X knows it — costs 1 send but
delivers from the wrong relay number, which is §6's problem in its sharpest form. **Do not do
this.** A member must only ever hear from their own home relay. The extra 30 sends/day buy that
invariant and it is worth far more than 30 sends.

### 1.3 The assumption everything above rests on

Every figure depends on **coordination being per-post, not per-recipient, and state sync being
per-change, not periodic.** Two designs that violate this and produce zero gain:

- **Per-recipient coordination.** If X sends one relay-to-relay message per foreign member, the
  system-wide cost is 99 coordination + 99 delivery = 198. Worse than one phone, on every line.
- **Periodic full-roster sync.** A 100-member roster is ~100 lines, ~4 segments per 9 members in
  Hebrew/Yiddish at 70 chars/segment — call it 45 segments. Synced hourly to N−1 peers, that is
  more traffic than the group generates. Sync must be **deltas, triggered by change**, and
  the full roster must only ever be sent on initial pairing (§9).

Both are easy mistakes. They belong in the design as explicit prohibitions, not as advice.

---

## 2. Transport — the decision the whole design turns on

### 2.1 SMS between relays

**No new permissions.** `SEND_SMS` and `RECEIVE_SMS` are already held; `SmsReceiver` and the paced
`SmsSendService` drain already exist and every outbound message in the app already goes through
exactly one `SmsManager` call site (audit §1, verified). A relay-to-relay message is an outbox row
with a new category. Phase 5a's `SentReceiver` already gives real delivery outcomes for it.

Costs, honestly:

- It spends the constrained resource. §1 shows this is 1–3% at the N the group would actually
  use. Not a real objection at N = 2–3; a real one if the answer to §1.1's metering question makes
  delivery cheap.
- It is subject to the same carrier filtering the app exists to avoid. But consider the *shape*:
  1:1 traffic between two fixed numbers at 10–40 messages/day is the most human-looking traffic on
  the line. It is a far better filtering profile than the 99-way fan-out that surrounds it. This
  objection is weaker than it first appears.
- **It is unauthenticated.** An inbound SMS's sender number is the only evidence of who sent it,
  and it is not evidence at all — SMS sender identity is trivially spoofable through commercial
  gateways. A relay that trusts "this came from the peer's number" trusts a forgeable claim, and
  the thing being forged is "relay this to your 50 members" or "remove member X". This is the
  serious objection and it has a serious answer (§4.1): a shared secret and a per-message MAC,
  which costs ~12 characters and no permission.
- Latency: seconds to tens of seconds, plus the coalescing hold. A reply reaching the far half of
  the group a minute late is acceptable for this deployment; the existing fan-out already takes
  2m43s (audit §1).
- Size: a coordination message is a post body plus a header. Post bodies are already sized to fit
  SMS. The header eats segment budget and a 3-segment post becomes a 4-segment coordination
  message. Budget for it; it does not change any order of magnitude.

### 2.2 Internet

This is not a checkbox, and the audit says why. From the verified-clean list:

> **No hardcoded secrets, no external-storage writes, no network code, no `INTERNET` permission.**

Confirmed against `AndroidManifest.xml` today: the permission set is `SEND_SMS`, `RECEIVE_SMS`,
`READ_SMS`, `POST_NOTIFICATIONS`, `WRITE_SECURE_SETTINGS`, two foreground-service permissions, and
`RECEIVE_BOOT_COMPLETED`. There is no `INTERNET` and no network code anywhere in the tree.

What that buys today is a property, not a feature: **there is no path by which 100 phone numbers
leave the device.** The audit treats number exposure as a first-class harm (§2.9 — `allowBackup`
was the *only* egress path, and it has since been disabled). Adding `INTERNET` replaces a
structural guarantee with a set of promises about code. The audit's reviewable surface for "can
the roster leave" goes from "grep the manifest" to "audit every call site forever."

It also changes what the app is. The members did not consent to anything; but the deployment's
implicit promise — one dedicated phone, in the host's possession, holding the numbers — is
legible to a non-technical member in a way "a server" is not.

Against a 1–3% arithmetic saving (§1), this is a bad trade.

**If internet anyway — peer-to-peer or server?**

- **Peer-to-peer** between phones on consumer mobile data is not realistic. Carrier-grade NAT,
  no stable address, no inbound reachability, phones asleep. It needs a rendezvous service, at
  which point it is a server with extra steps.
- **A server** means somebody hosts and secures a database of 100 real phone numbers belonging to
  people who were never asked. "Who, and under what terms" is the right question and it has no
  good answer for a neighbourhood group: the host is not an organisation, there is no
  data-processing agreement, no breach-notification path, no one to be accountable to, and the
  host's own hosting account becomes a second thing that can be compromised. A VPS that goes
  unpatched for two years is a worse custodian of those numbers than a phone in a drawer.
  - It would also re-introduce a single point of failure, which is one of the two things
    multi-device was supposed to remove.

### 2.3 The others, and why not

- **Local network / Wi-Fi Direct.** Requires the phones to be co-located. If the phones are in one
  room they can share a SIM tray, and Phase 4 is strictly better (§10). If they are not
  co-located, this does not work. Self-defeating.
- **Shared cloud document** (a synced spreadsheet, a shared note). Needs `INTERNET` with none of a
  purpose-built protocol's guarantees: no ordering, no acknowledgement, no authentication beyond
  the account, no way to express "exactly once". It puts the roster in a third party's cloud,
  which is precisely the harm audit §2.9 fixed. It is the worst of both columns.
- **Manual sync** — the admin retypes membership changes on each phone. Mentioned only to be
  dismissed: it guarantees §5's split-brain as the steady state rather than the failure state,
  and the compliance-critical case (a removal not propagating) is the one a human is most likely
  to defer.
- **Bluetooth.** Same co-location objection as Wi-Fi Direct.

### 2.4 Recommendation

> **SMS, with a shared secret and a per-message MAC.**

Because: coordination is per-post and therefore costs 1–3% of the resource it consumes (§1); it
adds no permission and no new egress path, preserving the one security property the audit
singles out; it reuses the outbox, the paced drain, and Phase 5a's delivery reporting rather than
introducing a second, unexercised send path; and it works wherever the phones already work, with
no third party, no hosting, and nobody to trust.

The cost is that authentication must be built rather than inherited from TLS, and that is ~40
lines of `javax.crypto` HMAC (§4.1) against a documented threat, which is a better trade than
`INTERNET` against a diffuse one.

---

## 3. Topology: single-writer, not peer-to-peer

Before the state model, one structural decision that makes most of §5 disappear.

> **One relay is Primary and owns the member list. Every other relay holds a read replica.
> All membership mutations — add, remove, rename, mute, subgroup assignment, home-relay
> assignment — happen at Primary and propagate outward. Secondaries never mutate the roster.**

Why single-writer:

- **Split-brain becomes impossible by construction**, not by convention. Two relays can be
  *stale*, which is a recoverable ordering problem with a version number. They cannot *disagree*,
  which is not.
- It matches how the group is actually administered: there is a host, and the host has a phone.
- Delivery does **not** depend on Primary. If Primary's phone dies, every secondary keeps
  delivering to its own pool against a frozen roster (§8). The failure is "no membership changes
  today", not "the group is down". That is a much better failure than today's "one dead phone is a
  dead group".

What it costs: membership changes are unavailable while Primary is offline, and a member texting
`#add` or an admin texting `#remove` to a secondary gets a queued or deferred result rather than
an immediate one. §5.3 covers the one case where that is not acceptable.

Peer-to-peer multi-writer is the alternative. It requires either consensus (absurd over SMS at
this scale) or last-writer-wins with clock skew between handsets deciding who is in the group.
Rejected.

---

## 4. The wire: what a relay-to-relay message looks like

Not a specification of bytes — a specification of what each message must carry, because the
required fields are where the correctness arguments live.

Every relay-to-relay message carries:

| Field | Why it must be there |
|---|---|
| **Protocol marker** | So `CommandProcessor.handleIncoming` can classify before it does anything else. Must be recognised *before* member lookup, bare-keyword matching, or relaying — a peer's coordination message must never be treated as a member's post. |
| **Origin relay id** + **sequence number** | Together, the global message identity. §5.4. |
| **Roster version** | Staleness detection, on every message, for free. §5.5. |
| **Message type** | `POST`, `REPLY`, `ROSTER_DELTA`, `ROSTER_FULL`, `ACK`, `PING`, `PROMOTE`. |
| **Payload** | The already-formatted, already-sanitised body for a POST; the delta for a ROSTER_DELTA. |
| **MAC** | §4.1. |

### 4.1 Authentication

A shared secret, generated on Primary, transferred to each secondary **out of band** during
pairing (§9) — displayed on screen and typed in, never sent over SMS. HMAC-SHA-256 over the whole
message, truncated to ~64 bits and base32'd: 13 characters. `javax.crypto.Mac` is in the platform;
no dependency, consistent with the handoff's no-new-libraries rule and with Phase 5b's existing
precedent of using `android.security.keystore` + `javax.crypto` directly.

Store the secret in the Android Keystore, exactly as Phase 5b specifies for the failover auth
token. Note `allowBackup` is already `false`, so it does not leave the device.

A message failing the MAC is **dropped and logged, never relayed and never replied to**. Replying
tells a prober that they found a relay.

Replay protection comes free from the sequence number and the seen-set (§5.4): a replayed message
is an already-seen (origin, seq) and is dropped.

This is not theoretical hardening. Without it, a forged `POST` from a peer's number broadcasts
arbitrary text to 100 people from a trusted-looking channel, and a forged `ROSTER_DELTA` adds an
attacker to the group. The audit already records §2.3 (any member can forge an `[Admin]:`
broadcast) as a live bug in the *existing* single-phone design; multi-device adds a second, more
powerful forgery surface and must not repeat it.

---

## 5. State — what must be shared, what must not, and what happens when they disagree

### 5.1 The member list

Primary holds the authority. Each member row gains a **`home_relay_id`** (nullable; NULL = hosted
by Primary, so every existing row is correct after an additive migration — the same
NULL-is-meaningful pattern `DbHelper` already documents for `members.subgroup_id` at v8 and
`outbox.subgroup_id` at v9).

Secondaries hold a full replica including numbers. This is unavoidable: a relay cannot deliver to
members whose numbers it does not have. It means **N copies of the roster exist on N devices**, and
each device is a full compromise of all 100 numbers. §6 and §9 both turn on this.

Does a secondary need the *whole* roster, or only its own pool? Only its own pool, for delivery.
But it needs the whole roster to (a) recognise an inbound text from any member and route the
command, and (b) be promotable to Primary on failover (§8). Holding only its own pool would mean a
member texting the wrong relay is unrecognisable — see §6. **Full replica, and accept the exposure
as a deliberate cost of the design.**

### 5.2 Which relay owns which member

Ownership is **per member**, not per sub-group — but under Phase 3 a sub-group must be **wholly
within one relay**. That constraint already exists verbatim in the handoff's Phase 4 ("Every
member of a sub-group must be on the same line — Phase 4 depends on this") and it carries over
unchanged, for the same reason: the group MMS is sent by one device on one line, and a sub-group
spanning two lines is not a thing that can exist.

Assignment must be **sticky for life**. See §6 — a member's home relay is the number they have
saved, and changing it is a user-visible event, not a load-balancing decision. Round-robin on
join; never automatic rebalancing; admin move only, with a notice.

### 5.3 Daily-limit accounting — per line or group-wide?

There are two different limits in the codebase and they should be answered differently.

**The per-line delivery budget is per line, and must be.** The CTIA constraint is physical: it
applies to a phone number. Each relay meters its own line against its own ~1,000/day, using local
data only. No coordination, no shared counter, no way for a peer's outage to mis-meter a line.
This is the limit that actually protects the group and it is trivially correct when local.

**The group daily post limit is group-wide, and is enforced at the origin relay only.** The
existing cap is a *policy* — "the group may make 8 posts today" — not a physical constraint;
`DailyLimitManager.groupStatus()` derives it from `countRelayedSince`, i.e. `RELAYED` log rows,
one per post (audit §1.2 notes this counts posts, not deliveries — that is a known bug being
fixed separately, but the *unit* is right for this purpose). The relay that receives a post is
the single place that decides whether it is relayed at all. Secondaries receiving a `POST`
coordination message **never re-check the group cap** — the decision was already made upstream.

That gives a clean rule with no shared counters:

> Policy decisions are made once, at the origin. Physical limits are enforced locally, everywhere.

The imperfection is honest and small: each relay's view of group usage is its own `RELAYED` rows
plus whatever peers told it, so a per-relay drift of a post or two is possible if a coordination
message is lost. A post-count cap of 8–10 does not need to be exact.

**Audit interaction:** §1.2's planned fix re-denominates the cap in *segments* and moves the check
into `enqueue`. A segment-denominated cap is a per-line quantity and would be enforced locally by
each relay — which is *more* correct under multi-device, not less. The two changes agree. Sequence
the audit fix first.

### 5.4 Message identity — exactly-once delivery

The lead is right that this is the highest-stakes part. The codebase has shipped duplicate
delivery more than once (audit §2.11: a second member row for the same human, receiving
everything twice), and under multi-device the mechanisms multiply: retries of a coordination
message, a relay restarting mid-drain, a member's post arriving at two relays, a promoted standby
replaying.

The design:

- Every post gets a **global id = (origin relay id, monotonic local sequence)**, assigned once,
  at the relay that first receives it from a member.
- Every relay keeps a **seen-set** table of global ids it has already fanned out, with a
  timestamp. Ingesting a `POST` whose (origin, seq) is already present is a **no-op that is logged
  and not relayed**. Retention: a fortnight; the table is ~400 rows/day at this traffic.
- The seen-set insert and the outbox enqueue must be **one transaction**. Enqueue-then-record
  duplicates on crash; record-then-enqueue drops on crash. Neither is acceptable and SQLite makes
  the atomic version free.
- Coordination messages are **retried until acknowledged** (Phase 5a's `SentReceiver` gives a real
  send outcome; a peer `ACK` gives a real *receive* outcome). Retries are safe precisely because
  the seen-set makes them idempotent. This is the whole reason the seen-set exists: without it,
  the reliability mechanism *is* the duplication mechanism.
- Coordination messages must never be coalesced (Phase 2's merger applies to `CATEGORY_RELAY`
  only — a new category inherits the correct behaviour, but state it explicitly) and must never be
  salted. Salting mutates the body; the MAC covers the body.

### 5.5 Staleness and split-brain

Under §3's single-writer model there is no true split-brain, only staleness. Handle it explicitly
anyway, because the failure is user-visible and compliance-relevant.

Every roster mutation increments a **roster version** on Primary. Every coordination message of
every type carries the sender's roster version. A relay receiving a version higher than its own
knows it has missed a delta and requests the gap. Detection is therefore free and continuous —
it rides on ordinary post traffic and needs no polling.

**What a member experiences when a relay is stale:**

| Stale state | Member experience | Severity |
|---|---|---|
| New member not yet replicated | Hosted by Primary, so Primary delivers to them. They receive posts. They do not appear in a secondary's `#list`. | Cosmetic |
| Nickname change not replicated | Posts bridged from the stale relay carry the old name prefix | Cosmetic |
| Mute not replicated | A muted member's post is still bridged by the stale relay | Minor |
| **Removal not replicated** | **A member who texted STOP keeps receiving group messages from a relay that has not heard** | **Compliance-critical** |

Removal is the case that matters. Audit §2.6 already records that STOP does not fully stop today
(queued rows still deliver); multi-device would make that worse by adding a second relay that has
not been told. Three mitigations, all required together:

1. **A removal is honoured immediately and locally, by whichever relay receives it**, before any
   coordination. The receiving relay stops delivering to that number at once, whether or not it is
   the member's home relay and whether or not Primary is reachable. This is a local
   never-deliver-to-this-number list, checked at drain time — which is also the right shape for
   audit §2.6's fix, so the two land together.
2. **Removals are the one mutation with mandatory ack-and-retry**, escalating to an admin alert if
   not acked within a short window.
3. **A relay that has not heard from Primary for longer than a threshold stops accepting new
   posts** and tells admins, rather than delivering against a roster it no longer trusts. Failing
   closed on staleness is the right default for the one rule with no grey area.

**Who wins:** Primary, always, on every field. A secondary never mutates. The one exception is the
local never-deliver list, which is additive-only and where "someone told a relay to stop" always
beats "the roster says keep going".

---

## 6. The problem the owner may not have considered: members would see different numbers

This is the section to read twice. It is not a detail and there is no clean fix.

The handoff's §1 lists **"One saved contact per member"** as a feature of the deployment, and
`phase3-redesign.md` §4 already treats changing what members see as "the owner's call and the
members', not the implementation's". Multi-device changes it again:

- A member's group messages arrive from **whichever phone hosts them**. Two neighbours in the same
  group have saved two different numbers as "Neighbourhood Group".
- A member replying to their saved contact reaches their home relay. That is correct — and it is
  also the reason home assignment must be permanent: **the saved contact is the routing.**
- Moving a member between relays changes the number they hear from. Under Phase 3, moving them
  between sub-groups may force a relay move (a sub-group cannot span relays, §5.2), so a
  sub-group rebalance — already expensive in `phase3-redesign.md` §4a — can now also change
  people's group number.
- A member texting the **old** number after a move, or the wrong number by accident, must still be
  handled. This is why every relay holds the full roster (§5.1): any relay can recognise any
  member and route their command.

### What it does to the commands

- **`#stop`** — must work at any relay, be honoured locally and immediately (§5.5 mitigation 1),
  and be forwarded to Primary. This is non-negotiable and is the strongest argument for the full
  replica.
- **`#help` / `#commands`** — work anywhere; they need no roster authority.
- **Admin commands** — see §7.
- **A member who saved the group contact and later has their host changed** will, from their point
  of view, simply stop hearing from the number they saved and start hearing from a new one. If
  they do not read the notice, the group has silently moved and their reply goes to a number that
  is no longer their home. Reuse Phase 4's already-specified `tpl_line_changed` ("Your group
  number is now X. Save this contact.") — the problem is identical and the string exists.

### Mitigations, ranked by whether they are real

**Real:**

1. **Permanent home assignment.** Assign on join, never rebalance automatically, admin move only.
   This does not solve the problem; it bounds it to a rare, deliberate, announced event. It is by
   far the most valuable mitigation and it is free.
2. **Accept commands at any relay, and honour `#stop` locally.** Makes the wrong-number case
   harmless rather than silent.
3. **Fewer relays.** N = 2 means at most one such event per member, ever. §12.

**Cosmetic:**

4. Telling members at join time that "the group may text you from one of a few numbers." True,
   and it does not help a flip-phone user who has one contact saved and replies to it.
5. Naming the relays in the message prefix ("Neighbourhood Group (2)"). Explains the symptom;
   changes nothing about which number their phone will dial.

**Not available:** a single inbound number fronting several outbound lines is a
carrier/A2P feature, not something consumer lines offer, and the project's whole premise is
avoiding a paid A2P route (handoff §1).

### Interaction with the Phase 3 roster decision

`phase3-redesign.md` §2 records the owner's decision that **each sub-group is sent a roster of its
members' numbers**. That decision is compatible with this design and slightly softens it: a member
who has saved their eight neighbours already has multiple group-related contacts, so "the group
has more than one number" is a smaller step than it would be today. The roster must contain
**member** numbers only — never relay numbers, and never another relay's number — or a member will
save the wrong one.

### Is it acceptable?

**This needs the owner's decision (§13 Q1).** My reading: it is acceptable at N = 2 with permanent
assignment, and it degrades quickly with N, because the chance that any given member has been
moved at least once rises with both N and time. It is the strongest single argument for keeping N
small, and combined with §1.1 it is the strongest argument for preferring Phase 4 (§12), where the
problem still exists but is bounded by the number of SIM slots in one device — and where the
handoff already accepted it.

---

## 7. Administration: N admin surfaces

Today there is one dashboard, one Settings screen, one activity feed, one device in the host's
hand. Multi-device gives N of each, and settings that silently differ between them are a whole
class of bug the app does not have yet.

Proposal:

- **Group policy lives on Primary and replicates**: group mode, delivery mode, in-thread reply
  routing, coalesce window, salting, bare keywords, join policy, daily post limit, templates. A
  secondary's Settings shows these **read-only, with the source relay named**. Editing a policy on
  a secondary is how two relays come to disagree about what the group is; do not allow it.
- **Line-local settings stay local and stay editable**: pacing (burst size, waits, microspacing),
  per-line daily send alert, battery/foreground-service setup, theme, locale. These describe a
  physical line and a physical device and have no business being shared.
- **Admin text commands work at any relay.** A policy-changing command received by a secondary is
  forwarded to Primary, applied there, and replicated back; the admin gets one confirmation, after
  it took effect, not before. If Primary is unreachable, the admin is told so plainly rather than
  getting a success reply for a change that did not happen.
- **Which phone does an admin text?** Whichever they have saved. That is the honest answer and it
  is only tolerable because of the forwarding rule above.
- **The dashboard must show the fleet**: each relay, its line, its sends today against its own
  budget, its roster version, and when it was last heard from. A fleet whose health is invisible
  is a fleet that fails silently, which is the failure mode audit §3 is entirely about.

One thing that must not be replicated: the **activity feed**. Each relay logs what it saw.
Merging feeds over SMS would cost more than the group's entire traffic. Accept per-device feeds and
say so in the UI.

---

## 8. Failure: a relay goes offline for a day

Today: one dead phone is a dead group. That does not improve automatically under multi-device —
it changes shape.

**A secondary dies.** Its pool goes dark: those members receive nothing and their posts reach
nobody. Everyone else is unaffected. So instead of 100 people losing the group, 50 do — genuinely
better, but not *good*, and worse in one specific way: **the group does not appear broken to
anyone**, so nobody investigates. The other 50 are having a normal day. Detection must be active:
a periodic `PING`/`ACK` heartbeat between relays (one SMS per relay per few hours, negligible) and
an admin alert when a peer goes quiet.

**Primary dies.** Delivery continues everywhere (§3). Membership changes stop. Removals still work
locally (§5.5). This is a good failure.

**Takeover — and what it costs.** Automatic takeover is where this design would go wrong. If B
decides A is dead and starts delivering to A's pool, and A was merely out of signal, every member
of A's pool gets everything twice — the worst outcome this app can produce, arriving at the exact
moment the fleet is least able to notice. The seen-set (§5.4) is per-relay and does **not** protect
against this, because B's sends are not duplicates of anything B has sent.

So:

> **Takeover is manual, explicit, and admin-commanded. There is no automatic failover.**

And the cost of takeover is exactly §6's cost, in bulk: every member of the dead relay's pool
starts hearing from a different number. It should therefore be reserved for "this phone is not
coming back", not "this phone was off for an afternoon". An afternoon of silence for half the
group is a better outcome than permanently changing 50 people's group number.

The `PROMOTE` path also needs a guard against the dead relay coming back: a promoted relay
increments an **epoch**, and coordination messages from a lower epoch are rejected. Without this,
reviving the old Primary produces two writers, which is the split-brain §3 exists to prevent.

---

## 9. Bootstrapping: how a second phone joins

An unauthenticated join is a stranger inserting themselves into 100 people's messages, and
inheriting the full roster. This is the highest-consequence operation in the design and it should
be deliberately awkward.

Proposed flow:

1. On Primary: **Settings → Relays → Add relay.** Enter the new phone's number. Primary generates
   a fresh shared secret and displays it as a short human-transcribable code, on screen, **once**.
2. On the secondary: enter Primary's number and the code by hand. The code **never travels over
   SMS**, or the channel authenticates itself with something an SMS observer already has.
3. Secondary sends a MAC'd `PAIR` request. Primary verifies the MAC, and **requires an explicit
   confirmation tap on Primary's screen** — physical possession of the Primary device is the
   second factor, and the host has it.
4. Primary sends a `ROSTER_FULL` (the only time a full roster is ever sent — §1.3). ~45 segments
   in Hebrew/Yiddish for 100 members. A one-off cost; budget it, pace it, and do not let it collide
   with a busy hour.
5. Both ends show the pairing in a **Relays** list with the peer's number, roster version, and
   last-heard time.

Properties: the secret is out-of-band; the confirmation requires the Primary handset; a forged
`PAIR` from a spoofed number fails the MAC; and a stolen secondary can be **unpaired from
Primary**, after which its MACs are rejected — though it keeps the roster it already has, which is
unavoidable and should be said out loud when pairing.

The roster arriving over SMS in plaintext is worth noting: those ~45 segments sit in two carriers'
logs. It is a one-off, it is the same information the Phase 3 roster decision already publishes to
members, and encrypting it under the shared secret is cheap if the owner wants it. Flag as a
question rather than deciding it here.

---

## 10. Migration: one phone becomes two, without downtime or duplicates

The group is live. Nobody may receive anything twice.

1. **Pair the second phone (§9).** It holds a full replica and **delivers nothing.** Running in
   this state for a week is the single most valuable de-risking step available — it exercises
   pairing, delta sync, staleness detection, heartbeats and the seen-set with the blast radius of
   a `SELECT`. Nothing it does can reach a member.
2. **Shadow mode.** The secondary computes what it *would* send for every post and logs it. The
   host compares the two devices' logs and confirms the union is exactly the roster, with no
   overlap. **This is the duplicate-delivery test and it must run against real traffic before any
   member's home relay moves.**
3. **Move a handful of members.** Five volunteers, chosen for being contactable by other means.
   They get `tpl_line_changed`, save the new contact, and confirm they receive posts once. Primary
   stops delivering to them in the same transaction that sets their `home_relay_id` — one writer,
   one commit, no window in which both or neither deliver.
4. **Move the rest in batches**, with a day between batches.
5. **Per-line budgets are only lowered after the split is complete**, never before.

Rollback at every step is "set `home_relay_id` back to Primary and send the notice again". It is
cheap, which is the point of batching.

Note the cost this reveals: migrating 100 members to a two-phone fleet spends ~100
`tpl_line_changed` messages and asks 100 people, some on flip phones, to save a new contact. That
is a one-off of roughly one day's entire message budget, plus a social cost. It belongs in the
decision (§12), not in the appendix.

---

## 11. Phased build order — each phase independently useful

**Phase A — Replica.** Relay identity, `relays` table, pairing (§9), MAC, roster deltas, roster
version, heartbeat, Relays UI. The secondary holds a live replica and **sends nothing to members,
ever.**
*Useful on its own:* an off-device, encrypted-at-rest backup of the roster — which the group does
not currently have, since `allowBackup` was correctly disabled (audit §2.9) and the app has no
other egress. It also exercises the whole transport under zero risk.

**Phase B — Manual promotion.** `PROMOTE`, epochs, the "Primary unreachable" admin alerts, and the
member-facing number-change notice. The standby can be promoted by explicit admin action.
*Useful on its own:* removes the single point of failure — the stated second motivation — **without
ever splitting delivery, and therefore without §6's different-numbers problem in normal
operation.** It appears only in a disaster, where it is obviously worth it.

> **Phases A and B together answer the redundancy motivation completely. If §1.1's arithmetic
> holds, they may be the whole project.**

**Phase C — Split delivery, N = 2.** `members.home_relay_id`, ownership-aware routing, per-relay
metering (§5.3), the seen-set (§5.4), local never-deliver lists, shadow mode and the migration
path (§10).
*Useful on its own:* doubles the ceiling. This is the phase where §6 becomes real and permanent.

**Phase D — N > 2.** Only if the group grows past what two lines carry. Re-derive §1's table for
the traffic that actually exists at that point rather than trusting this document's.

**Explicitly not planned:** automatic failover (§8).

Per `CLAUDE.md`, each phase commits first, documents bullets in `VERSION.md`, bumps the version,
adds strings to all three locale files with equal counts, and updates `README.md`. Nothing in this
document has been implemented and no file other than this one has been changed.

---

## 12. The honest conclusion: this may not be the right change

The lead asked for this to be said plainly if true. I think it is.

**Phase 4 (multi-SIM in one device), which the owner deferred on 2026-09-22, dominates
multi-device on almost every axis in this document** — and its spec already exists in the handoff.

| | Multi-SIM (Phase 4) | Multi-device |
|---|---|---|
| Lines / ceiling | 2 (3 with eSIM) | Unbounded |
| Coordination traffic | **Zero** | 1–3% of budget (§1) |
| Roster copies | **One** | N |
| Split-brain / staleness | **Impossible** | Designed around (§5) |
| Admin surfaces | **One** | N (§7) |
| Devices to keep alive, charged, un-Dozed | **One** | N |
| Members see different numbers | Yes — and already accepted in the Phase 4 spec | Yes, plus takeover (§6, §8) |
| Removes single point of failure | **No** | Yes |
| Migration cost | Assign new members to line 2; no mass contact change | ~100 notices, 100 people re-save (§10) |
| Spec status | **Written, reviewed, deferred** | This document |

Multi-SIM buys 2× the ceiling with none of §§4, 5, 7, 8, 9 or 10. Its only genuine losses are the
hard cap on N and the single point of failure. And §1's table shows **N = 2 is the only N the
traffic model needs** — 505 sends/line/day against ~1,000, without Phase 3 at all.

Then stack the §1.1 finding on top: Phase 3 alone, on one phone, one line, lands at ~111
sends/day — a 9× margin — and it is already partly built. If Phase 3's metering question resolves
favourably, **neither multi-SIM nor multi-device is needed for capacity at all**, and the only
remaining motivation is redundancy, which Phases A and B (§11) satisfy without splitting delivery.

So, ordered:

1. **Settle Phase 3's metering question** (`phase3-redesign.md` §6) — a fortnight's pilot watching
   for silent non-delivery. It gates whether a capacity problem exists.
2. **Finish the audit's §1 fixes**, particularly 1.1 (the failure-alert feedback loop, which can
   self-sustain ~1,000 SMS/day) and 1.2/1.3 (the cap counting the wrong unit on 1 of 11 paths).
   **The group's headroom problem is partly self-inflicted, and these are cheap.** Adding a second
   line under a feedback loop that generates its own traffic buys a second line for the loop.
3. **If capacity is still short: Phase 4 (multi-SIM).** Written, cheaper, and safer.
4. **Build multi-device Phases A and B** for redundancy, which is a real and unaddressed problem
   and which these phases solve without touching delivery.
5. **Phase C only if 1–3 leave the group short of capacity.**

If the owner's priority is redundancy rather than capacity, reorder to put 4 first — it is
independently valuable and low-risk regardless of how the capacity question resolves.

---

## 13. Open questions — these need the owner's judgement, not an implementation

1. **Is it acceptable that members see different numbers depending on which phone hosts them?**
   (§6) *Trade-off:* it is the unavoidable cost of any multi-line design, including the already-
   accepted Phase 4. Saying no rules out both, and caps the group at one line's ceiling forever.
   Saying yes is irreversible in practice — 100 people re-save a contact once and will not do it
   cheerfully twice. **Blocking for Phase C. Not blocking for A or B.**

2. **Capacity or redundancy — which is actually the motivation?** *Trade-off:* they have different
   answers. Capacity → Phase 3, then multi-SIM. Redundancy → multi-device Phases A + B, and
   nothing else. The request bundles them; the arithmetic separates them cleanly, and the cheapest
   plan depends entirely on which one is the real driver. **Blocking for everything.**

3. **How many relays, realistically — two, or a fleet?** (§1, §12) *Trade-off:* two is a different
   project from five. Two is Phase 4 with extra steps. Five needs everything in §§4–9 and puts
   five copies of 100 numbers on five handsets. If the honest answer is two, §12's recommendation
   stands unchanged.

4. **Who physically holds the second phone, and is it in the same room?** *Trade-off:* same room
   makes multi-SIM strictly better and makes the redundancy argument weaker (one fire, one
   burglary, one power cut). Different locations is the only configuration where multi-device
   genuinely beats multi-SIM on availability — and it is also the configuration where nobody
   notices the secondary died.

5. **Should the roster be encrypted over the air during pairing?** (§9) *Trade-off:* ~45 segments
   of member numbers otherwise sit in two carriers' message logs in plaintext. Encrypting under
   the pairing secret is a small amount of `javax.crypto`; not encrypting is defensible given the
   Phase 3 roster decision already publishes those numbers to members. A judgement about the
   carrier as an observer, which is a different judgement from the one already made.

6. **Do members need to be told the group runs on more than one phone?** *Trade-off:* the Phase 3
   precedent (`phase3-redesign.md` §4) says structural changes visible to members get a notice
   before the fact. This one is visible — they will see a second number. Telling them costs one
   message each; not telling them means they discover it as a malfunction.

7. **Does a secondary get the full roster or only its own pool?** (§5.1) *Trade-off:* full replica
   makes `#stop` work everywhere and makes promotion possible; pool-only halves the exposure per
   device but means a member texting the wrong relay is unrecognised, which breaks the one
   compliance rule with no grey area. I recommend full, but the exposure is real and it is the
   owner's to accept.

8. **Is manual-only takeover acceptable?** (§8) *Trade-off:* automatic takeover risks
   duplicate delivery to 50 people during a transient outage — the worst outcome the app can
   produce, at the moment it is least observable. Manual means half the group can be dark until
   someone notices. I strongly recommend manual; the question is whether the owner accepts the
   silence.

---

## 14. What I would test before writing any code

Roughly in order of how badly each could sink the design. None requires the feature to exist.

1. **Relay-to-relay SMS reliability over a week.** Two spare handsets, one MAC'd message every
   half hour, logged at both ends. Measure: loss rate, latency distribution, and whether either
   carrier starts filtering a fixed pair exchanging identical-shaped messages. **If a coordination
   message can silently vanish at any material rate, half the group misses posts and the ack-and-
   retry design is load-bearing rather than belt-and-braces.** Everything else assumes this works.
2. **Segment cost of a real coordination message.** Take actual Hebrew and Yiddish post bodies from
   the live group, add the header and a 13-character MAC, and count segments with
   `divideMessage()`. If the header routinely pushes a 3-segment post to 4, §1's arithmetic is
   ~30% optimistic. Cheap to measure, and it is the only input to the whole document that is
   currently a guess. (Note audit §1.7: the existing segment measurement is itself a sample, not a
   bound, because of the random ZWSP draw — measure with salting off.)
3. **The duplicate-delivery harness**, built before the feature. Given two relays' logs for a
   day's traffic, assert every active member appears exactly once per post. This is the test that
   should gate Phase C, and §10's shadow mode is how it gets real data.
4. **Seen-set idempotency under crash**, in plain JUnit, no device: enqueue and seen-set insert in
   one transaction; kill between plausible points; assert exactly-once. Audit §6 notes the outbox
   state machine has **zero** tests today — this is the right place to start paying that down, and
   it pays off whether or not multi-device is ever built.
5. **A member texting the wrong relay.** Does a secondary recognise a member whose home is
   Primary, route `#stop` locally, and forward the rest? Testable against the existing
   `CommandProcessor` with a stubbed roster, before any relay exists.
6. **Classification before anything else.** A coordination message must be recognised by
   `handleIncoming` before member lookup, before bare-keyword matching (audit §2.1: one English
   word removes a member), and before relaying. A peer's `POST` payload containing the word
   "cancel" must not remove anybody. Unit-testable today.
7. **MAC rejection paths.** Wrong secret, truncated message, replayed message, message from an
   unpaired number: all dropped, all logged, none replied to.
8. **The `ROSTER_FULL` transfer at 100 members**, paced, against the outgoing-SMS check and the
   coalescing window. It is the largest single burst the app would ever send and it happens at the
   least-tested moment in the system's life.
9. **Heartbeat sensitivity.** How long before a dead secondary is noticed, and how often does a
   live one get falsely reported during ordinary signal loss? A false "relay down" alert that
   tempts an admin into a manual takeover is how §8's duplicate-delivery scenario actually
   happens.
