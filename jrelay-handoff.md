# jRelay Fork — Claude Code Handoff

Place this file at the repo root next to `CLAUDE.md`. Read `CLAUDE.md`, `README.md`, and `VERSION.md` before touching anything. `CLAUDE.md` governs coding style, UI rules, versioning, and commit discipline for every phase below.

## 0. Ground rules

- Fork from `main` (currently **4.1**, `versionCode 5`). Not the `v3.0` tag; `main` is two releases ahead.
- **Keep every existing option, command, setting, and default exactly as it is. This includes Salting** (timestamp / hex / zero-width space) and everything under Send Pacing, Daily Limits, Join Requests, Member Reporting, Pause Service, Announcement Mode, Localization, Theming. Nothing is removed, renamed, or re-defaulted.
- Vanilla Android Java + XML. No Kotlin, no Jetpack, no androidx, no new library dependencies. Where a phase would normally reach for a library (MMS PDU handling, `FileProvider`), the phase says what to do instead.
- Every new user-facing string goes into all three of `values/strings.xml`, `values-iw/strings.xml`, `values-yi/strings.xml`. Keep the three files' string counts equal. Yiddish is best-effort, same as today.
- Every new screen or section follows the CLAUDE.md UI rules: `ScrollView` root over a vertical `LinearLayout`, DPAD-navigable, `clickable`/`focusable` on interactive views, no cards, no overlays, no emojis, works on very small screens.
- Schema changes are additive (`ALTER TABLE ... ADD COLUMN`), bump `DbHelper.DB_VERSION`, never drop or rename columns.
- Per CLAUDE.md: git commit before starting each phase, document every change as bullets in `VERSION.md`, bump the version. Suggested plan: Phase 1 → 5.0, Phase 2 → 5.1, Phase 3 → 6.0, Phase 4 → 7.0, Phase 5a → 7.1, Phase 5b → 8.0. If CLAUDE.md's major/minor rule says otherwise, CLAUDE.md wins.
- Update `README.md` at the end of each phase (new commands in the commands table, new settings in the Settings section, new behavior sections).
- Windows dev machine. Build with `.\gradlew.bat assembleDebug`. Device commands: `adb install -r app\build\outputs\apk\debug\app-debug.apk`, `adb shell pm grant com.sh7411usa.jrelay android.permission.WRITE_SECURE_SETTINGS`.
- Phases 1 and 2 both touch `CommandProcessor` and the outbox and must run sequentially. Phase 3 is mostly additive (a wake receiver, an ingest service, a vendored PDU package, a content provider) and can be developed on its own branch in a second session in parallel, then merged after 1 and 2 land. Phases 4 and 5 come after 3.
- **Phase 3 is gated on a device spike** (see that phase). Do not build Phase 3 until the spike passes on the target device; if it fails, stop and report rather than falling back to the default-SMS-app role.

## 1. Deployment context (why these phases exist)

- Neighborhood group, ~100 members, under 10 posts/day to everyone. Replies are expected to be more frequent than posts.
- Many members are on flip phones. All can receive MMS.
- Reliability matters more than "group thread" feel. One saved contact per member, name-prefixed messages.
- Host is a dedicated Android device on a consumer line. No server.
- Carrier constraint that shapes everything: CTIA person-to-person guidelines treat roughly 15–60 messages/minute, ~1,000 messages/day, ~100 unique recipients, and a near 1:1 in:out ratio as "human" traffic per phone number. One line at 100 members is at the edge on a busy day; the phases below are how the app stays under it without a paid A2P route.
- Important interpretation: jRelay's existing **daily limits count relayed posts, not deliveries**. One post to 100 members is 1 toward the group limit but 100 SMS on the line. With one line and 100 members, a Group Daily Limit of 8–10 keeps the line near ~1,000 sends/day worst case. The per-line delivery cap in Phase 4 is what maps directly to the carrier guideline.

## 2. Map of the code you will touch

| File | Role | Touched in |
|---|---|---|
| `sms/CommandProcessor.java` | `handleIncoming` → `handleCommand` / `relayPlainMessage` → `broadcastExcept` → `enqueue` | 1, 2, 3 |
| `sms/SmsReceiver.java` | `SMS_RECEIVED_ACTION` → `CommandProcessor.handleIncoming(e164, body)` | 4 |
| `sms/SmsSendService.java` | `drainAll` → `sendBurst` → `sendOne`; uses `SmsManager.getDefault()`, `sendTextMessage` / `sendMultipartTextMessage` with null intents | 2, 3, 4, 5 |
| `db/DbHelper.java` | `DB_VERSION = 3`; tables `members`, `message_log`, `outbox` | 1, 2, 3, 4, 5 |
| `db/OutboxRepository.java` | `enqueue(memberId, phoneE164, body)`, `takeBurst(limit, shuffle)`, `markSent/markFailed/requeueForRetry` | 2, 3, 4, 5 |
| `db/MessageRepository.java` | `log(memberId, direction, category, body)`; categories in use: `RELAY`, `RELAYED`, `COMMAND`, `SYSTEM`, `ADMIN`, `DM`, `FAILED` | 1, 3, 5 |
| `util/Prefs.java` | enums incl. `GroupMode { GROUP, ANNOUNCEMENT }`, `JoinPolicy`, `BurstMode`, salt enums | 1, 2, 3, 4, 5 |
| `util/MessageSalt.java` | `applyAll(prefs, senderE164, body)` — currently applied in `CommandProcessor` before enqueue | 2, 3 |
| `util/DailyLimitManager.java` | group pool + per-member caps from `message_log` `RELAYED` rows | 1, 3 |
| `SettingsActivity.java` | single scrollable settings screen, sectioned | all |
| `MainActivity.java` | dashboard: Options menu, mode badge, stat tiles, next-burst countdown | 1, 2, 4 |
| `MemberDetailActivity.java` | per-member actions and overrides | 4 |
| `AndroidManifest.xml` | permissions, `SmsReceiver`, `SmsSendService` | 3, 4, 5 |

---

## Phase 0 — Baseline (no behavior change)

1. Fork `main`, build, install on the dedicated device, grant `WRITE_SECURE_SETTINGS` over ADB, raise the Android outgoing-SMS check (`sms_outgoing_check_max_count`, default 30 per 30 minutes) from Settings.
2. Add three test members, confirm a plain relay and `#stop` round-trip.
3. Commit. Do not change anything in this phase.

---

## Phase 1 — Reply Mode + bare keywords (→ 5.0)

### Goal
A third group mode where anyone can post to everyone, but a plain reply goes only to the person who posted. This is the volume lever: replies stop multiplying by 100.

### Mode wiring
- `Prefs.GroupMode` gains `REPLY`. Default stays `GROUP`.
- `#mode reply` (admins only) alongside the existing `#mode group` / `#mode announcement`. `#mode` alone reports the current mode including Reply.
- Settings → Group Mode dropdown gets the third entry.
- Dashboard Options menu: replace the two-way toggle with entries for the two modes that are not currently active ("Switch to Group Mode", "Switch to Announcement Mode", "Switch to Reply Mode" — show two, hide the active one).
- Dashboard badge "Reply Mode" next to the group name, same treatment as the Announcement badge.
- Mode-switch broadcast notice and reply-to-admin status text, same pattern as `setGroupMode` today.

### Behavior in REPLY mode (plain-text message from an active, unmuted member)
1. **Post to everyone**: message starts with `#all ` (canonical) or `all:` (case-insensitive, the word "all" immediately followed by a colon). Strip the prefix, then run exactly the existing GROUP-mode path in `relayPlainMessage`: group/individual daily-limit checks, `tpl_relay_prefix`, `MessageSalt.applyAll`, `RELAYED` log, `broadcastExcept`. Applies to admins and members alike; admins also have in-app Send to Group.
2. **Reply** (no prefix): target = author of the post recorded in `members.last_post_received_id` for the sender. Valid if that post exists, its author is active, is not the sender, and the post is within the reply window. Then enqueue **to the target only** with a new template `tpl_reply_prefix` (English: `%1$s (reply): %2$s`), log category `REPLY`, no daily-limit charge (mirrors `#admin` and Announcement-mode routing). If **Copy replies to admins** is on, also enqueue to admins as `@admin reply from <nickname> to <target>: <text>`.
3. **No valid reply target** (no post yet, expired window, author removed, or the sender authored the last post): route to admins via the existing `sendToAdminsOnly`, and reply to the sender with `tpl_reply_no_target` (English: `Sent to the admins. To post to everyone, start your message with #all.`). No daily-limit charge.
4. `#to <nickname> <text>` (REPLY mode only; in other modes reply "Only available in Reply Mode"): private reply to a named active member using the same tolerant nickname/number matching as `#remove`. Same template, log, and no-charge rules as (2). Exists so a member can answer the right person when two posts arrived close together.
5. `#commands` output in REPLY mode includes `#all` and `#to`. The welcome texts (`tpl_added_you`, `tpl_added_you_self`) get a mode-aware second sentence in REPLY mode: `Start a message with #all to post to everyone. A plain reply goes only to the person who posted.`
6. Muted members: unchanged — nothing they send is relayed, commands still work.

### Bare keywords (all modes)
- Whole-message match only, trimmed, case-insensitive: `STOP`, `UNSUBSCRIBE`, `CANCEL`, `QUIT`, `END` → `#stop`; `HELP` → `#commands`; `MUTE` / `UNMUTE` → `#mute` / `#unmute`. "stop by later" is not a command.
- Setting **Accept bare keywords** (on by default) in a new Settings section **Commands**. Rationale: carrier opt-out convention; people type STOP without `#`.

### Tracking the last post
- `members.last_post_received_id INTEGER` (nullable). Set inside `broadcastExcept` when a `RELAY`-category row is enqueued for a member: the id of the `RELAYED` `message_log` row for that post. `MessageRepository.log` must return the inserted row id so the post id exists before the fan-out loop.
- `DbHelper.DB_VERSION = 4`, additive migration.

### Settings additions
| Section | Setting | Default |
|---|---|---|
| Group Mode | third dropdown entry: Reply Mode | Group |
| Reply Mode (new) | Reply window (hours; 0 = no limit) | 24 |
| Reply Mode | Copy replies to admins | off |
| Commands (new) | Accept bare keywords | on |

### Acceptance
- In REPLY mode: `#all hi` from A reaches everyone except A, counts against limits. Plain `hi` from B reaches A only, formatted `B (reply): hi`, does not count. Plain text from A (last poster) goes to admins with the hint. After 25h with a 24h window, B's plain text goes to admins with the hint. `#to A hello` from C reaches A only.
- In GROUP and ANNOUNCEMENT modes nothing changes; `#all` in GROUP mode simply posts (prefix stripped) so members who learned the habit are not punished.
- `STOP` alone removes the member; `stop by later` relays as text.
- All three strings files have identical counts; `.\gradlew.bat assembleDebug` clean.

---

## Phase 2 — Coalescing window (→ 5.1)

### Goal
When several posts arrive within a short window, each recipient gets one SMS containing all of them instead of one SMS per post. Cuts sends during bursts; a person composing one longer text is what carriers expect to see.

### Behavior
- Setting **Coalesce window (seconds; 0 = off)**, default **45**, in the Send Pacing section. Setting **Max segments per merged message**, default **3**.
- Applies only to `RELAY`-category outbox rows (posts). Never to `COMMAND`, `SYSTEM`, `ADMIN`, `DM`, `REPLY`, or (Phase 3) MMS rows.
- On enqueue of a `RELAY` row, set `outbox.hold_until = now + window`. `takeBurst` skips rows whose `hold_until` is in the future.
- Before a burst is drawn, a merge step groups released `RELAY` rows by `phone_e164`, concatenates bodies with `\n` in enqueue order, and collapses them into the first row (others marked `MERGED`, never sent). If adding the next line would push the merged body past the max-segments setting (use `SmsManager.divideMessage(...).size()`), close the current merged row and start another.
- Salting moves to send time for `RELAY` rows so a merged message is salted once: store the formatted-but-unsalted body in `outbox`, add `outbox.apply_salt INTEGER` (1 for `RELAY` rows), and call `MessageSalt` timestamp/hex/zwsp in `sendOne`. Keep "strip phone numbers" and "append sender number" at enqueue time — they are per-original-message transforms. Behavior with the window set to 0 must be byte-for-byte identical to today's salting. Document this refactor in `VERSION.md`.
- Scheduling: there is no background alarm in this app (see the Pause Service notes in README). When `SmsSendService.drainAll` finds only held rows, it sleeps until the earliest `hold_until` and loops, on the same worker thread it already uses for inter-burst waits. Same limitation as today: if the process is killed while holding, the backlog goes out on the next natural trigger.
- Dashboard: the existing "Next burst in Xs (N messages)" line also reports "holding N" while rows are within their window.
- `outbox` gains `category TEXT`, `hold_until INTEGER NOT NULL DEFAULT 0`, `apply_salt INTEGER NOT NULL DEFAULT 0`. `DB_VERSION = 5`.

### Acceptance
- Three `#all` posts within 45s → each recipient receives one SMS with three lines in order, salted once, and the dashboard showed "holding" during the window. Group Daily Limit still counts 3.
- A `COMMAND` reply during the window goes out immediately.
- Window 0 → behavior identical to 5.0, including salting output.

---

## Phase 3 — Text-only group MMS delivery, opt-in (→ 6.0)

> **This section was replaced wholesale.** The earlier design (photo relay via the default-SMS-app role) is withdrawn. Ignore any memory of it; what follows is authoritative.

### Goal
Cut sends per post by delivering to sub-groups instead of to individuals: one text-only group MMS per sub-group, rather than one SMS per member. 100 members at 9 per sub-group becomes 12 sends instead of 100. No photos, no attachments, and jRelay never becomes the default SMS app.

### Explicitly out of scope
- Do **not** request the default-SMS-app role.
- No `SMS_DELIVER` receiver, no `WAP_PUSH_DELIVER` receiver, no `SENDTO` activity, no `RESPOND_VIA_MESSAGE` service, no inbound SMS de-duplication.
- No image or attachment handling of any kind: no re-encoding, no max-attachment-KB setting, no media-retention setting, no media storage, no MMS-storage dashboard tile.
- jRelay never calls `downloadMultimediaMessage`.

### Delivery mode
- `Prefs.DeliveryMode { INDIVIDUAL_SMS, GROUP_MMS }`, default `INDIVIDUAL_SMS` — today's behavior, unchanged.
- In `GROUP_MMS`, members are auto-assigned to sub-groups of at most **Members per group** (default **9**; with the host that is 10 participants, AT&T's cap).
- `members.subgroup_id`. Every member of a sub-group must be on the same line — Phase 4 depends on this.
- Per-member **Deliver individually** flag: keeps that member out of every sub-group, on 1:1 SMS.
- **Never rebalance sub-groups** except on an explicit admin command.

### Delivery mode wiring (same pattern as Phase 1's Group Mode)
- Settings gains a new **Delivery** section with the mode selector, alongside **Members per group**. Follows the usual UI rules (`ScrollView`/`LinearLayout`, DPAD-navigable, no cards or overlays).
- Admin command `#delivery sms` / `#delivery mms`; `#delivery` alone reports the current mode. Admin-only, with the standard "Only admins can use this command." reply for non-admins — same shape as `#mode`.
- Switching **either way** sends **every member** a one-line notice.
- Switching back to `INDIVIDUAL_SMS` restores today's behavior **exactly**, and `subgroup_id` assignments stay stored so the next switch to `GROUP_MMS` reuses them rather than re-assigning (consistent with "never rebalance except on an admin command" — a mode round-trip is not a rebalance).
- As with every other phase, both the Settings path and the text-command path must call one shared method (mirror `CommandProcessor.setGroupMode(newMode, changedByLabel, excludeId)`) so the notice logic cannot drift between them.
- New user-facing strings go into all three of `values/`, `values-iw/`, `values-yi/`, keeping the counts equal. `#delivery`, like every `#` token, stays untranslated.

### PDU handling without a library
- Do **not** add `android-smsmms` or similar; they pull androidx. Vendor the AOSP MMS PDU classes (Apache-2.0, `com.google.android.mms.pdu`, commonly vendored as `pdu_alt`) into `com.sh7411usa.jrelay.mms.pdu`: `PduComposer`, `SendReq`, `PduBody`, `PduPart`, `PduHeaders`, `EncodedStringValue`, `ContentType`, `CharacterSets`, and whatever they transitively need — and nothing more. Inbound parsing classes (`PduParser`, `NotificationInd`, `RetrieveConf`) are **not** needed: inbound comes from `content://mms`, not from parsing PDUs. Add `THIRD_PARTY_LICENSES.md` at the repo root with the Apache-2.0 text and attribution, and link it from the in-app License screen.
- For the content URI `SmsManager` reads, implement a minimal `android.content.ContentProvider` subclass (`MmsFileProvider`) serving files from app-private storage via `openFile`, `exported="false"`, `grantUriPermissions="true"`. Do not use androidx `FileProvider`.

### Outbound
- Each outbound relay becomes **one outbox row per sub-group**: `outbox.kind = 'MMS_GROUP'`, `outbox.subgroup_id`.
- One text-only `SendReq` PDU addressed to **all** of that sub-group's members, written to a file, exposed via `MmsFileProvider`, sent with `SmsManager.sendMultimediaMessage` — which needs only `SEND_SMS`.
- Coalescing (Phase 2), salting, pacing, retries, and sent status (Phase 5a) apply **per row**, exactly as they do for SMS rows.
- The per-line daily send counter counts each group MMS as **its recipient count**, not as 1.

### Inbound
- The stock messaging app **stays the default** on the host: MMS auto-download on, RCS/chat features off, notifications muted. It downloads everything.
- Add `RECEIVE_MMS` and `READ_SMS`. Add a manifest receiver for `WAP_PUSH_RECEIVED` (`mimeType application/vnd.wap.mms-message`) used **only** to wake a short-lived `MmsIngestService`.
- `MmsIngestService` polls `content://mms` — a `ContentObserver` while the process is alive, plus a catch-up scan on every existing trigger — for inbox rows (`msg_box = 1`, `m_type = 132`) above a high-water mark `Prefs.lastMmsId`, initialized to the current max when the mode is enabled so history is never relayed.
- Per row, read the sender (`addr` type = 137), the text parts, and `sub_id`. Ignore any non-text parts.
- A message with attachments and no text relays nothing; reply to the sender that attachments are not relayed.

### Routing
- A message arriving in a **group thread** was already delivered to its own sub-group by the carrier → relay it only to the **other** sub-groups and to any Deliver-individually members.
- A **1:1 SMS or 1:1 MMS** to the host relays to **every** sub-group, including the sender's.
- Every group-thread message counts as a post against the daily limits.
- Reply Mode private routing (Phase 1) applies only to **1:1** texts.
- Announcement Mode: a non-admin's group-thread message still reaches their own sub-group — that cannot be prevented — but is otherwise routed to admins only.
- Adding or removing a member changes that sub-group's recipient set, which starts a new thread on members' phones: send that sub-group a one-line notice.

### Device spike — REQUIRED BEFORE BUILDING THE REST
On the target device, confirm:
1. a multi-recipient `sendMultimediaMessage` from a **non-default** app succeeds, and arrives as **one group thread** on members' phones, **including a flip phone**;
2. an inbound group MMS's sender and text are readable from `content://mms`.

If either fails, **stop and report**. Do not fall back to the default-SMS-app role.

Also settle during the spike: whether the `WAP_PUSH_RECEIVED` receiver fires for a non-default app without `RECEIVE_WAP_PUSH` (that broadcast is normally permission-gated). If it never fires, ingestion must rest entirely on the `ContentObserver` plus the catch-up scan — which is why both exist; the receiver is an optimization, not the mechanism.

### Acceptance
- `INDIVIDUAL_SMS` (default): behavior identical to 5.1 in every respect.
- `GROUP_MMS`, 100 members at 9 per group: one post produces **12 outbox rows, not 100**; each sub-group receives one group-thread MMS; the per-line counter increases by 100.
- A group-thread message from a member reaches the other sub-groups and the Deliver-individually members, but **not** the sender's own sub-group.
- A 1:1 `#stop` still removes the sender; a 1:1 text in Reply Mode still routes privately.
- An attachment-only MMS relays nothing, and its sender is told attachments are not relayed.
- Adding a member sends that one sub-group a one-line notice; no other sub-group is touched and nothing is rebalanced.
- `#delivery` from an admin reports the current mode; `#delivery mms` switches and every member gets one notice; a non-admin gets the unauthorized reply. The Settings **Delivery** selector produces the identical notice (shared method, no drift).
- Round-trip `GROUP_MMS` → `INDIVIDUAL_SMS` → `GROUP_MMS`: behavior in `INDIVIDUAL_SMS` is identical to 5.1, and the second switch reuses the stored `subgroup_id` assignments rather than re-assigning them.

---

## Phase 4 — Multiple lines on one device (→ 7.0)

### Scope
Multiple SIM subscriptions on the **same** device only (dual-SIM / eSIM). Multi-device relay is out of scope for this handoff. If only one active subscription exists, this phase is invisible and everything defaults to that line.

### Behavior
- New Settings section **Lines**: lists active subscriptions from `SubscriptionManager.getActiveSubscriptionInfoList()` (add `READ_PHONE_STATE`, runtime prompt) with carrier name, slot, an **Enabled** toggle per line, an admin-entered **Display number** per line (`SubscriptionInfo.getNumber()` is often blank), a **New members go to** chooser (round-robin among enabled lines, or a specific line), and a per-line **Daily send alert** (default 300): admins get an `@system` notice at 80% and 100% of that line's sends for the day. Alert only — never blocks, so members are not stranded.
- `members.line_sub_id INTEGER` (nullable = the device's default subscription). Assigned once, on add/join. Member Detail gains a **Line** dropdown; moving a member sends them `tpl_line_changed` with the new display number ("Your group number is now X. Save this contact.") and nothing else changes for anyone.
- `outbox.sub_id INTEGER` set at enqueue from the recipient's line. `DB_VERSION = 7`.
- Send: `SmsSendService` runs **one drain loop per enabled line**, each applying the global pacing settings independently. This is a deliberate, bounded change from 4.0's "single global loop" (that revert removed one-thread-per-member; this is at most a few threads, one per SIM). Use `SmsManager.getSmsManagerForSubscriptionId(subId)` (API 22–30) or `getSystemService(SmsManager.class).createForSubscriptionId(subId)` (API 31+). MMS likewise.
- Receive: read the subscription id from the incoming intent (`"subscription"` extra; `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX` on API 30+) and log it. A member who texts a line they are not assigned to is still processed normally; replies to them go out on their assigned line.
- Daily limits (posts) stay global. Dashboard: one "Sent today" counter per enabled line.
- CSV import/export: add an optional third column `line` (display number or sub id); absent → assignment rule applies.

### Acceptance
- Two SIMs: 100 members split ~50/50; a post drains on both lines concurrently, each within its own pacing; per-line counters increment; the 80% alert fires at 240 sends on a line. Move a member to the other line → they get one notice; their next reply goes out on the new line.

---

## Phase 5 — Real sent status, then failover transport

### 5a — Sent status (→ 7.1)
Today `sendOne` treats "the API call didn't throw" as success; carrier rejection is invisible. Pass a `PendingIntent` `sentIntent` to `sendTextMessage` / `sendMultipartTextMessage` / `sendMultimediaMessage`, add `sms/SentReceiver`, and drive `markSent` / `requeueForRetry` / `markFailed` from the result code (`Activity.RESULT_OK`, `RESULT_ERROR_GENERIC_FAILURE`, `RESULT_ERROR_RADIO_OFF`, `RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_LIMIT_EXCEEDED`, `RESULT_ERROR_SHORT_CODE_NOT_ALLOWED`, …). Store the code on the outbox row (`last_result INTEGER`) and show it in the member's activity feed for `FAILED` rows. Retry limit, failure counts, and admin alerts keep their current meaning; they just become accurate. `DB_VERSION = 8`. Multipart: mark sent only when every part reports OK.

### 5b — Failover transport (→ 8.0), off by default
- `sms/MessageTransport` interface: `send(OutboxItem)`; `SimTransport` wraps today's path; `HttpTransport` posts to a Twilio-compatible Messages endpoint with `HttpURLConnection` (no OkHttp), form-encoded, Basic auth, 2xx = accepted. Add `INTERNET`.
- Settings section **Failover** (all off/empty by default): Enable; Endpoint URL; Account SID / user; Auth token; From number; **Trigger**: N consecutive sent-failures on a line within M minutes (defaults 5 / 10); **Failover duration** minutes (default 60) after which the line is retried; **Daily failover cap** (default 200) with an `@system` admin alert when hit. SMS only — MMS never fails over; MMS rows wait for the SIM.
- Store the auth token encrypted with an Android Keystore AES key (`android.security.keystore` + `javax.crypto`; no androidx security library). Everything else in `Prefs` as usual.
- Log provider responses to `message_log` category `FAILOVER`; dashboard badge "Failover active" while a line is routed to HTTP.
- Per-line: a line in failover keeps its members; only the transport changes. When the duration expires, the next burst tries the SIM; on success the line leaves failover.

### Acceptance
- With failover disabled, 8.0 behaves identically to 7.1.
- Airplane mode on the host during a post → 5 `RESULT_ERROR_RADIO_OFF` in a row → remaining rows for that line go out over HTTP (stub endpoint in a test), badge shows, cap enforced, line returns to SIM after 60 minutes.

---

## Operating parameters for the pilot (not code)

- One line, 100 members, Group mode for two weeks, then Reply mode.
- Send Pacing: burst 5–8, wait 10–20s, microspacing 350ms → roughly 25–40 sends/minute, inside the CTIA range. A 100-recipient post reaches the last member in ~3–4 minutes on one line; with three lines in Phase 4 it is under a minute.
- Group Daily Limit: 8 on one line, 15 on two, 20 on three.
- Spread lines across two host networks when adding SIMs.
- Register a low-volume 10DLC brand (~$20 one-time, ~$2/month) before Phase 5b so the failover route exists when it is needed.

## Definition of done (every phase)
- Builds clean on Windows with `.\gradlew.bat assembleDebug`.
- All three `strings.xml` files have equal string counts.
- `VERSION.md` has the bullets, the version is bumped, `README.md` reflects the new commands/settings/behavior.
- Every pre-existing option, default, and command behaves as before, verified against the acceptance list of the previous phase.
