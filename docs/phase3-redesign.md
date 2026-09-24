# Phase 3 — Redesign after the device spike

Status: **specification, not a plan of record.** It contains decisions the owner has to make
before anyone writes code, and it says plainly which ones those are.

Inputs: `docs/phase3-device-spike.md` §0 (H1 and H2 both PASSED on a Samsung SM-F711U, Android 15,
AT&T, 2026-09-23), the Phase 3 section of `jrelay-handoff.md` (partly invalidated by that result),
and `docs/audit-2026-09.md` for the constraints the existing code already fails to meet.

---

## 1. What the spike actually changed

The spike was run to answer "can a non-default SMS app send one MMS to nine people and read the
replies". It answered yes to both. It also returned a third fact nobody asked for, and that third
fact is the one that matters:

> An inbound group-MMS row carries a `type=137` row for the sender **and a `type=151` row for every
> other recipient.** The message was delivered to all of them by the carrier before jRelay saw it.

Two consequences, one useful and one structural.

**Useful:** jRelay can identify which sub-group a message came from by matching the participant
address set against its own `subgroup_id` assignments. It therefore knows exactly who has already
been served and can avoid double-delivering. That is the missing piece the old routing section
hand-waved.

**Structural:** within a sub-group, the conversation is genuinely peer-to-peer. When Alice replies
in her thread, her eight neighbours receive it **from Alice, with her number, at carrier speed,
with jRelay sending nothing and able to do nothing about it.**

The existing handoff already contains this sentence, under Routing:

> "a message sent *inside* a group thread is delivered to that thread's other 9 members by the
> carrier, before jRelay ever sees it. It cannot be intercepted, suppressed or undone."

That sentence is correct, and the rest of the section does not take it seriously enough. It treats
peer delivery as a rounding error on a relay design. It is not a rounding error. It means jRelay
stops being a relay for 9% of the group and becomes a **bridge between twelve small group chats.**
Everything below follows from that.

---

## 2. The revised model, in plain language

The group stops being one hundred-person broadcast list and becomes **twelve neighbourhoods of
about nine people each, with jRelay standing in all twelve of them.**

- Inside your neighbourhood you are in an ordinary group text. You see your eight neighbours'
  names and numbers. They see yours. Messages arrive instantly, from the person who sent them.
  jRelay spends nothing on this and controls none of it.
- jRelay is the eleventh participant in every thread. When something is said in one neighbourhood,
  jRelay repeats it into the other eleven, prefixed with the speaker's nickname, from the relay
  number. To those eleven neighbourhoods it looks exactly like today's relay traffic does.
- So every member sees the group in two registers: **their own nine neighbours, direct and
  attributed to real people**, and **the other ninety, funnelled through one number and one name
  prefix.** That split is permanent and visible, and members will notice it.

A post therefore costs **eleven sends instead of ninety-nine.** A reply inside your neighbourhood
costs **zero** if it is not bridged onward, and eleven if it is.

### Is the two-register split confusing?

Somewhat, and less than it sounds. The prefixed form is what the group already lives with today —
every message currently arrives as `Alice: text` from the relay number. What is new is that
*some* messages now arrive unprefixed from a real contact. A member's honest mental model becomes
"the group, plus the nine of us who can talk directly". That is an intelligible thing, and on a
flip phone it is arguably an improvement: nine people in a thread is legible, a hundred-person
firehose from one number is not.

The failure case is **double attribution confusion**: a member in neighbourhood A sees Alice's
message from Alice, and must not also receive the bridged `Alice: ...` copy. The `type=151`
participant list is what prevents that, and it must be got right or every message is seen twice by
the people closest to it.

### Inside a sub-group, nothing is prefixed with a name — and that is not fixable

The section above says members "see your eight neighbours' names and numbers". That is only true
**if each member has saved those eight contacts.** They have not. Today every member has exactly
one number saved — the relay's — and every message arrives as `Alice: text`. Inside a sub-group
thread jRelay is not in the path at all, so there is no opportunity to prefix anything: the
message goes peer-to-peer and arrives attributed to whatever the recipient's phone knows about
that number. On a flip phone with no contact saved, that is a raw string of digits.

So a member's experience splits into `Alice: bins out tonight` from the relay, and
`+18482074564` from the person standing next door. The second is worse than what they have today,
on exactly the device class this deployment exists for.

**This is confirmed, not predicted.** The spike's inbound reply carried two `type=151` rows —
the reply went to the relay AND to the other participant directly. jRelay never saw it in time to
touch it, and could not have.

There is no technical fix. jRelay cannot inject a prefix into a message it does not carry, and it
cannot prevent in-thread replies — the thread exists on the handsets and reply-all is simply what
the phone does. Re-sending a prefixed copy would deliver the message twice to the people closest
to it. The realistic responses, in order of how well they actually work:

- **(a) Compose sub-groups from people who already know each other** — the same street, the same
  building, the same shul. Then the numbers are mostly already saved, a nine-person thread is an
  ordinary thing to be in, and the problem largely dissolves instead of being managed. This makes
  sub-group composition a social decision, not a load-balancing one, which argues strongly for
  **stable, admin-assigned** sub-groups (§5.2) over anything automatic or rotating.
- **(b) Send a roster when a sub-group is formed**, so members can save the eight contacts. One
  send per sub-group. But it deliberately publishes nine numbers to nine people — §4's privacy
  question in a sharper form — and the flip-phone users least served by (a) are also the least
  likely to go and save nine contacts.
- **(c) Accept it and say so at join time.** Members learn their eight neighbours over a few
  weeks, as they would in any group text.

(a) and (c) together are the honest answer. (b) is worth offering as an admin action rather than
doing automatically.

Worth stating plainly: this is a real loss against today's behaviour, and it is the strongest
argument against Phase 3 that does not involve carrier metering. It should be weighed against the
saving, not waved past.

### Replying back to the original author

Three routes, and the design must pick a default:

1. **Bridge everything (In-thread replies = Everyone).** Bob in B replies in his thread. B's eight
   see it free; jRelay bridges it to A..L, so Alice in A sees `Bob: ...`. Conversationally
   complete, costs eleven sends per reply, and replies are the dominant traffic — this is the
   expensive option and it is what the old spec defaults to.
2. **Poster only.** jRelay sends Bob's reply to Alice alone, one SMS. Costs one. But note what it
   does to the shape of the conversation: B's eight neighbours saw Bob's reply, A's eight
   neighbours did not, and Alice answers into A where Bob cannot see it unless it is bridged. The
   thread fragments along neighbourhood lines. It is cheap and it works; it is not a group
   conversation.
3. **`#to Alice ...`**, explicit, one send, unchanged from Phase 1.

There is no fourth option where a reply reaches the original author's neighbourhood only. It could
be built (bridge to the author's sub-group, 1 MMS, 9 people) and it is arguably the best value on
this list: one send, and the reply lands where the question was asked, visible to the eight people
most likely to care. **This is worth adding as a third setting value: "Poster's group only".**

---

## 3. What changes from the handoff spec, and why

| Handoff says | Revised | Why |
|---|---|---|
| "12 sends instead of 100" | **11 sends per member post**, 12 only for an admin post originated in the app | The poster's own sub-group already has it. The spike's own §0 says 12; it is 11 for the dominant case. |
| Sub-group membership is an internal routing detail | Sub-group membership is a **social and privacy decision** exposed to members | Members see each other's numbers. See §4. |
| `#stop` / removal works as today | **Removal is no longer enforceable inside a sub-group thread** | See §5. This is the largest single consequence and it is not addressed anywhere in the current spec. |
| Announcement Mode "still reaches their own sub-group — that cannot be prevented" | Announcement Mode should be **refused** in `GROUP_MMS`, or the mode selector should warn that it no longer means what it says | A mode whose entire purpose is "only admins may post to the group" cannot be honoured when every member can post to nine people unmediated. Shipping it as a half-enforced mode is worse than not offering it. |
| Mute is a per-member control | Mute no longer silences a member **within** their own sub-group | Same root cause. The UI must say so. |
| Per-line counter counts a group MMS as its recipient count | **Unknown, and it decides whether this phase is worth building** | See §6. |
| Routing rules stated as a list | Routing must first **classify the inbound thread**, and only then route | An inbound MMS whose participant set does not match a known sub-group is not group traffic. See §5.4. |
| Ingest reads `content://mms` | Ingest must **verify `READ_SMS` actually returns rows on first run** and surface a plain-language failure if not | The spike's reads were `adb shell content query`, i.e. shell privileges. `READ_SMS` is not reserved to the default SMS app and jRelay already holds it, so this is expected to work — but "expected" is what the spike exists to stop us relying on. First run should query `content://mms` with `LIMIT 1` and, on `SecurityException` or a null cursor, refuse to enable `GROUP_MMS` and tell the admin why. |

Also carried forward unchanged and still correct: the delivery-mode selector and `#delivery`
command, the shared-method rule so Settings and the text command cannot drift, `subgroup_id`
persistence across a mode round-trip, the per-member "Deliver individually" flag, "never rebalance
except on explicit admin command", and the refusal to take the default-SMS-app role.

---

## 4. The privacy change nobody has consented to

Today every member knows exactly one number: the relay's. The handoff's §1 says so explicitly —
"One saved contact per member". Under this design **every member's phone number becomes visible to
eight neighbours**, permanently, the moment the first sub-group MMS is sent. It cannot be undone:
the numbers are on their handsets.

This is a larger change to the members' position than anything else in the roadmap, and it is not
mentioned in the current spec. The audit already treats number exposure as a first-class harm
(2.9, backups sending the roster to a Google account, listed under "Harm to the people in the
group"). Sending the same numbers to ninety-nine neighbours by design deserves at least the same
scrutiny.

It is not obviously wrong — this is a neighbourhood group; many of them may already have each
other's numbers, and a nine-person thread is a normal thing to be in. But it is **the owner's call
and the members', not the implementation's.** At minimum:

- switching to `GROUP_MMS` must state, in the confirmation, that members will see each other's
  numbers;
- the notice sent to every member on the switch must say it too, in all three locales, before the
  first sub-group MMS goes out;
- a member must be able to ask to stay on 1:1 SMS — the "Deliver individually" flag already exists
  and should be reachable by a member command, not only by an admin in the app.

---

## 5. Consequences that need decisions, not implementations

### 5.1 `#stop` stops jRelay. It does not stop the neighbours.

Today `softRemove` sets `active=0` and the member stops receiving, because every message came from
jRelay. (Imperfectly — audit 2.6 notes already-queued rows still deliver. That is a bug with a
fix.) Under this design, a removed member is still a participant in a live group thread on eight
handsets. Anyone replying in that thread reaches them. jRelay cannot prevent it, cannot detect it
reliably, and cannot apologise for it.

"Continuing to text after an explicit STOP is the one compliance rule with no grey area" — audit
2.6. This design creates a channel where that rule cannot be enforced by the app.

Options, all imperfect:

- **(a) Rebuild the sub-group on removal.** jRelay starts a new thread with the remaining eight.
  Cost: one MMS plus a notice. Problem: the *old* thread still exists on every handset and still
  works. Members reply where their phone last showed the conversation. You are relying on
  eight people reading a notice and changing habit.
- **(b) Put the affected sub-group back on individual SMS** after a removal, permanently or for a
  cooling-off period. Honest, costs 8 sends per post for that group, and the old thread is still
  live regardless.
- **(c) Accept and disclose.** Tell members at join time that the group thread is visible to its
  participants and that leaving the group means asking those neighbours directly. This is what
  actually happens in every real group text; it is also an explicit downgrade of the app's
  compliance posture.
- **(d) Don't use group MMS for the sub-group a removal touched** and let it heal on the next
  admin-commanded rebalance.

**This needs the owner's decision.** My recommendation is (a) + (c) together — rebuild the thread
*and* disclose at join — with the removal notice to the old thread worded as a request, because a
request is all it is. But I will not pretend that makes removal enforceable.

The same problem, smaller, applies to **mute**.

### 5.2 Stable or rotating sub-groups

**Recommend stable**, and the handoff's "never rebalance except on an explicit admin command"
already says stable. The spike's result strengthens that:

- Rotating means the recipient set changes, which means every handset starts a **new thread** every
  rotation. On a flip phone, old threads do not disappear; the member accumulates dead threads and
  has no reliable way to tell which is current. This is actively bad for exactly the device class
  the deployment exists for.
- Rotating spreads exposure of everyone's number to everyone, rather than to eight people. That
  makes §4 strictly worse.
- Stable means the peer channel is a stable social object — "my nine neighbours" — which is the
  only thing about this design that is unambiguously nicer than what exists today.

The cost of stable is that the free peer channel always benefits the same eight people. That is a
fairness question, not a technical one, and it is small.

**Open question for the owner:** if sub-groups are stable and socially visible, **who is in them
matters.** Random assignment will put people who have never met in a nine-person thread. Assigning
by street or block would make the threads useful. The code has no data to do that — it would need
an admin-editable sub-group assignment in Member Detail. Worth deciding before the first
assignment runs, because reassignment is a thread rebuild for everyone touched.

### 5.3 Join, leave, rebuild — what it costs

- **Join.** New member is assigned to the smallest sub-group. That group's recipient set changes →
  new thread. Cost: 1 MMS to the new set, plus the existing welcome SMS to the joiner, plus
  (optionally) 1 notice into the retired thread. Roughly **3 sends**, against 1 today.
- **Leave / removal.** Same shape, plus whichever of §5.1's options is chosen. **2–10 sends.**
- **Rebalance** (admin command only): 12 new threads, 12 MMS, plus a group-wide notice if notices
  are on. **12–24 sends.** Cheap in sends; expensive in confusion, because every member's phone
  shows a brand-new thread and an orphaned old one on the same day. Rebalance should carry a
  confirmation that says so.

Note this interacts with 5.6's Group Notices work, which turned five of six notices **off** by
default to save the line. Sub-group rebuild notices are a **new** category and should default to
**on**, because unlike a nickname change they explain a visible structural event on the member's
handset.

### 5.4 An inbound group MMS is not necessarily group traffic

This is a new attack and accident surface that 1:1 SMS did not have.

If someone starts an ordinary group text with four neighbours and the host happens to be one of
them, jRelay sees an inbound MMS from a member, with a participant list. If routing is
"member + MMS ⇒ bridge it", a private conversation among five people is broadcast to a hundred.
This is the Phase 6 `TAGGED_ONLY` hazard arriving early, by default, on a dedicated device.

**The ingest path must classify before it routes:**

1. Read the participant set (`type=137` sender + all `type=151`).
2. Remove the host's own number.
3. If the remaining set **exactly equals** a known `subgroup_id`'s member set → sub-group traffic,
   route per §2.
4. If it does not match → **not group traffic.** Do not bridge. Log it, and reply once to the
   sender explaining that only the group thread relays. Never silently broadcast an unmatched
   thread.
5. Rows with `msg_box != 1` (the host's own sent MMS) must be skipped, or jRelay bridges its own
   bridge.

Exact-set matching is deliberate and strict. A thread that is a *subset* of a sub-group is someone
messaging a few neighbours, not the group.

### 5.5 What jRelay stops controlling

Peer traffic never passes through `CommandProcessor.enqueue`, so for the eight people nearest you
it bypasses, all at once: the daily limits, coalescing, salting, `sanitizeRelayBody` /
`sanitizeNicknameForRender` (audit 2.3, the `[Admin]:` forgery guard), Announcement Mode, mute, and
pause. Pause in particular now means "jRelay stops bridging", not "the group stops".

None of these is fatal. All of them make the Settings screen's current wording untrue, and the
strings need revising rather than the features removing.

jRelay should still **log** ingested peer traffic to `message_log` even when it sends nothing, so
the host's activity feed reflects the conversation that is actually happening. Otherwise the
dashboard shows a quiet group that is in fact busy.

---

## 6. The message-count estimate — and the number that decides the phase

Assumptions: 100 active members, 9 per sub-group → 12 sub-groups, host is the 10th participant in
each. Traffic model from the handoff: ~10 posts/day, ~30 replies/day.

### Per event

| Event | Today (Group mode) | Today (Reply mode) | Revised model |
|---|---|---|---|
| Member post | 99 SMS | 99 SMS | **11 MMS** (own sub-group free) |
| Admin post from the app | 99 SMS | 99 SMS | **12 MMS** |
| Reply — bridged to everyone | 99 SMS | — | **11 MMS** |
| Reply — poster's group only *(proposed)* | — | — | **1 MMS** |
| Reply — poster only | — | 1 SMS | **1 SMS** |
| Reply — not bridged | — | — | **0** |
| Member joins | 1 | 1 | ~3 |
| Member removed | ~100 (notice on) / 1 (notice off, 5.6 default) | same | 2–10 |

### Per day

| Configuration | Sends/day |
|---|---|
| Today, Group mode | 10×99 + 30×99 = **3,960** |
| Today, Reply mode | 10×99 + 30×1 = **1,020** |
| Revised, replies bridged to everyone | 10×11 + 30×11 = **440** |
| Revised, replies to poster's group only | 10×11 + 30×1 = **140** |
| Revised, replies to poster only | 10×11 + 30×1 = **140** |

Against a ~1,000/day CTIA ceiling, that is the difference between a group that is permanently at
the edge and one with 2–7× headroom.

### The caveat that could erase all of it

Every figure in that table counts **messages handed to the radio.** It does not count recipients.
The handoff's own Outbound section says:

> "The per-line daily send counter counts each group MMS as **its recipient count**, not as 1."

If the carrier meters the same way, the revised model sends 10×99 + 30×99 = 3,960
recipient-deliveries in the bridge-everything configuration — **identical to today's Group mode**,
and the entire saving is illusory except for the peer traffic (which is genuinely free either way,
about 9% of deliveries) and the drain time (12 radio calls instead of 99 is a real, large win on
pacing and on the 2m43s fan-out).

**This is the single most important unknown in the phase, and it is not answerable from the
codebase.** It needs either a carrier answer or an empirical one — send a known number of
multi-recipient MMS on a line with a known allowance and watch what the account reports.

The honest framing: the *certain* wins are radio-call count, fan-out latency, and the free peer
channel. The *uncertain* win — the 8x headroom the whole phase was justified on — depends on how
the carrier counts. **Do not schedule the build on the strength of the 8x number until that is
settled.**

---

## 7. Failure modes

### 7.1 One failed MMS is nine undelivered people, and you cannot tell which

`sendMultimediaMessage` returns **one** `sentIntent` result for the whole PDU. There is no
per-recipient outcome. Today a `RESULT_ERROR_*` costs one person; here it costs nine, and a retry
re-sends to all nine — so the ones who already received it get it twice. Phase 5a's sent-status
work becomes materially coarser under this design and its spec should be revised to say so.

Required behaviour, per outbox row:
- On MMS failure, **fall back to individual SMS to that sub-group's members** rather than retrying
  the MMS. Cost reverts to 9 for that group only, delivery is preserved, and duplicates are traded
  for the certainty that everyone got it once. (Accept: someone may get it twice anyway if the MMS
  partially succeeded. There is no way to know. Document it.)
- After *k* consecutive MMS failures across sub-groups (suggest 3), trip a circuit breaker: revert
  the whole group to `INDIVIDUAL_SMS`, alert admins, and do not auto-revert. Data off, roaming, a
  carrier that refuses MMS, or an MMSC outage all look identical from here.
- **Reverting does not close the peer channel.** The threads exist on members' handsets and they
  will keep replying into them. jRelay must keep ingesting `content://mms` even in
  `INDIVIDUAL_SMS` mode once any sub-group thread has ever existed, or those replies silently
  vanish from the group. This is not in the current spec and it needs to be.

### 7.2 Size

Text-only MMS is nowhere near any size ceiling; a coalesced three-segment body is a few hundred
bytes against a carrier limit measured in hundreds of kilobytes. Size is a non-issue. **Recipient
count** is the real limit — AT&T's ~10-participant cap is what set the default of 9, and other
carriers differ. The per-sub-group size must stay a setting, and the default must stay
conservative.

### 7.3 Coalescing and timing asymmetry

Coalescing (Phase 2) still applies per outbox row and still works. But note the asymmetry it
creates: the poster's own neighbourhood sees three posts arrive individually and instantly, while
the other eleven see one merged blob 45 seconds later. Not a defect; worth knowing before someone
reports it as one.

### 7.4 Daily cap

The audit's 1.2 stands and gets slightly worse: the cap counts `RELAYED` log rows (posts), and peer
traffic produces no such row at all. Under this model a busy day can be genuinely busy and the cap
will report near-zero usage. The audit's planned fix — re-denominate the cap in segments and move
the check into `enqueue` — remains the right fix, and `enqueue` for an `MMS_GROUP` row must charge
whatever §6's metering decision concludes.

---

## 8. Risks to test before anyone writes code

The spike proved one host, one carrier, two recipients. These are the things that can still sink
the design, roughly in order of how badly:

1. **Does a flip phone's in-thread reply actually reach the other participants, or only the
   sender?** Some feature phones offer "reply" and "reply all" and default to the former; some
   silently downgrade a group reply to a 1:1 to the last sender. **If flip-phone replies go only to
   the host, the free peer channel does not exist for the population this app was built for**, and
   the model collapses back to bridging everything — at which point the phase is worth only its
   radio-call saving. The spike's reply was sent *from the smartphone*. This is the single most
   important untested assumption.
2. **Does a 10-participant MMS work on the other carriers the members are actually on?** One host
   on AT&T is not a sample for a 100-member deployment.
3. **What does a membership change do to the thread on a flip phone?** Does removing a recipient
   create a new thread; does the old one remain replyable; is there any visible distinction. This
   determines whether §5.1's option (a) is real or theatrical.
4. **Does the host's stock app, or any recipient's, upgrade a sub-group thread to RCS?** The spike
   ran with RCS deliberately off everywhere. Production will not be that tidy, and an RCS-upgraded
   thread is a different transport with a different idea of group membership.
5. **`READ_SMS` from the app, not from `adb shell`.** Query `content://mms` from a debug build of
   jRelay itself and confirm the same rows come back.
6. **Ingest hygiene:** own sent rows (`msg_box=2`) skipped; the `application/smil` part not
   mistaken for the body; the high-water mark on `_id` surviving the stock app deleting rows; no
   re-bridge of an already-bridged message.
7. **Rate.** Twelve MMS in quick succession through one MMSC. MMS has its own throttling behaviour
   distinct from SMS, and the existing pacing settings were tuned for SMS.
8. **Auto-download reliability over time**, not once. An `m_type=130` that never becomes `132` is a
   message that silently never reaches the other eleven neighbourhoods.
9. **Latency of the ingest path** if `WAP_PUSH_RECEIVED` does not fire (the spike's §8 question was
   not recorded as settled). `ContentObserver` plus catch-up scan is the real mechanism; measure
   what it costs in seconds before a reply reaches the rest of the group.

---

## 9. Questions for the owner

Ordered by how much downstream work they block. None of these can be settled from the code.

1. **Do members consent to their numbers being visible to eight neighbours?** (§4) Irreversible,
   and it is the one thing here that changes the members' position rather than the app's. Blocking.
2. **How does the carrier meter a 9-recipient MMS — one message or nine?** (§6) Decides whether
   this phase delivers 8× headroom or ~1×. Blocking for the *justification*, not for the design.
3. **How do we handle removal, given that it is no longer enforceable inside a thread?** (§5.1)
   Pick from (a)–(d), or combine.
4. **Is `1:1` peer visibility acceptable for flip-phone members specifically**, given they cannot
   easily mute or leave a thread?
5. **Stable sub-groups: assigned randomly, or by the admin with local knowledge?** (§5.2) Changing
   this later means rebuilding threads for everyone touched.
6. **Default for In-thread replies** — and should "Poster's group only" be added as the third
   option? I would make it the default: one send, and the reply lands where the question was asked.
7. **Should Announcement Mode be refused in `GROUP_MMS`?** (§3) It cannot be honoured. Offering a
   half-enforced version is worse than declining.
8. **Should a member be able to opt themselves onto 1:1 SMS by text command**, not just by an admin
   setting the flag?

---

## 10. What I would do

Not a decision — a recommendation, so there is something concrete to disagree with.

- Build it **stable, 9 per sub-group, admin-assignable**, with "Poster's group only" as the reply
  default.
- Treat §4 (number visibility) and §5.1 (removal) as **release gates**, not as documentation tasks.
- Settle §6 (metering) before the build is scheduled, because it is the phase's stated
  justification and it is currently an assumption.
- Run risk #1 (flip-phone reply behaviour) as a **second short spike** before any of this is
  built. It is one afternoon with two handsets, and it is the difference between a design and a
  rewrite.

The phase is viable. It is a smaller win than the spike's headline number if the carrier meters by
recipient, a larger structural change than the existing spec describes, and it hands nine people
per member a channel jRelay does not control. All three of those are acceptable — but they should
be accepted deliberately.
