# jRelay — External Briefing

Self-contained context for getting a second opinion on this project from another model. Paste this whole file.

## What jRelay is

An Android SMS relay app. A dedicated Android phone hosts a text "group": members text the host's number, and the host re-sends each message to every other member, prefixed with the sender's nickname. There is no server and no app for members — everyone participates over plain SMS, which is the point: many members are on flip phones.

It is vanilla Android Java + XML by deliberate policy. **No Kotlin, no Jetpack, no androidx, no third-party libraries.** UI is `ScrollView` over vertical `LinearLayout`, fully DPAD-navigable, no cards or overlays, built to look right on very small screens. Storage is raw SQLite via `SQLiteOpenHelper`; schema changes are strictly additive (`ALTER TABLE ... ADD COLUMN`) because older bundled SQLite builds can't `DROP COLUMN`.

Upstream is the public repo `sh7411usa/jRelay`. This working copy is a fork of `main` at v4.1, developed locally; the remote here is named `upstream`, and `origin` is intentionally unused so a fork can be attached later.

## The constraint that shapes every decision

Carrier (CTIA) person-to-person guidelines treat roughly 15–60 messages/minute, ~1,000 messages/day, ~100 unique recipients, and a near 1:1 in:out ratio as "human" traffic for one phone number. The live deployment is a neighborhood group of ~100 members on a single consumer line. One post relayed to 100 members is **100 SMS** on that line. Exceeding the guidance risks silent carrier filtering — messages that appear sent but never arrive.

A subtlety worth knowing: jRelay's existing daily limits count **relayed posts, not deliveries**. One post to 100 members counts as 1 against the group limit but costs 100 SMS on the line.

Everything in the roadmap is about reducing sends per post without paying for an A2P/10DLC route.

## Roadmap

`jrelay-handoff.md` at the repo root is the authoritative plan. Phases in brief:

- **Phase 1 → 5.0 (DONE)** — Reply Mode + bare keywords. See below.
- **Phase 2 → 5.1** — Coalescing window: several posts arriving within N seconds merge into one SMS per recipient. Moves salting to send time.
- **Phase 3 → 6.0** — *Respecified mid-session; the doc is authoritative, ignore any older description.* Text-only **group MMS**: members are auto-assigned to sub-groups of at most 9, and each relay becomes one text-only MMS per sub-group via `SmsManager.sendMultimediaMessage` (needs only `SEND_SMS`). 100 members → 12 sends instead of 100. jRelay deliberately stays a **non-default** SMS app; the stock messaging app remains default and downloads everything, and jRelay reads inbound from `content://mms`. No photos, no attachments, no default-SMS-app role. **Gated on a required on-device spike** proving multi-recipient MMS from a non-default app arrives as one group thread, including on a flip phone.
- **Phase 4 → 7.0** — Multiple SIMs on one device, one drain loop per line.
- **Phase 5a → 7.1** — Real sent status via `PendingIntent` (today a send that doesn't throw is assumed delivered).
- **Phase 5b → 8.0** — HTTP failover transport, off by default.
- **Phase 6 (proposed, unscheduled)** — Personal-phone mode via `Prefs.RelayScope`, so the app can run on an everyday phone and relay only explicitly group-addressed messages.

## Open questions worth a second opinion

1. **Is the Phase 3 group-MMS bet sound?** It hinges on multi-recipient `sendMultimediaMessage` succeeding from a non-default SMS app and landing as a single group thread on recipients' phones, including flip phones. If that fails there is no fallback in the plan other than stopping.
2. **Reply Mode's unmetered replies.** Replies deliberately don't count against daily limits, so the quota dashboard under-reports real line usage. The failure mode is carrier filtering while the UI still shows headroom.
3. **Personal-phone mode (Phase 6)** — is "only relay explicitly tagged messages" enough isolation to run this on someone's real phone, or is a dedicated device the only defensible answer?

---

# Run Log

### Run: /build — 2026-09-22
Task:               Phase 1 of the jRelay fork — Reply Mode + bare keywords (v4.1 → 5.0).
Outcome:            APPROVED (Reviewer verdict: APPROVED WITH RECOMMENDATIONS)
Iterations:         2 build iterations + 2 Reviewer cycles, of a 10 cap.
Files:              New `sms/MessageIntent.java` + `MessageIntentTest.java`; modified `CommandProcessor`, `Prefs`, `DbHelper`, `MemberRepository`, `MessageRepository`, `Member`, `MainActivity`, `SettingsActivity`, 3× `strings.xml`, `arrays.xml`, 2 layouts, 1 menu, `build.gradle.kts`, `README.md`, `VERSION.md`.
Key decisions:      Cloned upstream with the remote named `upstream`, leaving `origin` free for a later fork (not under the `yissr` account). Reply target stored as a `message_log` row id (`members.last_post_received_id`) rather than a denormalized author/timestamp pair, so targeting and quota read one source of truth. Routing parser extracted to a pure-Java class with no Android imports specifically so it could be covered by real JVM unit tests — the project already ships plain JUnit, so this cost no new dependency. Bare keywords apply in **all** modes (carrier opt-out convention), which is a deliberate change to Group/Announcement behavior. Reply-target writes batched into one chunked `UPDATE ... id IN (...)` per fan-out instead of one per recipient.
Issues encountered: (1) The initial FILES list missed `res/values/arrays.xml`, which positionally backs the Group Mode spinner — Reply Mode would have been unselectable in Settings. Caught by a Coder reporting a cross-unit gap rather than silently reaching outside its files. (2) **Reviewer caught a real bug that all seven automated checks passed:** the app's own "Send to Group" broadcast and admin DM left `last_post_received_id` stale, so in Reply Mode a member replying to an admin message was silently routed to an unrelated member and the admin never saw it. Fixed by clearing the pointer on both paths, plus on `reactivate`. (3) `/simplify` found that CSV-imported members never received the Reply-Mode `#all` hint that `#add`/`#join` members got — a spec-compliance gap, now fixed via a shared `withReplyHint` helper.
Open items:         No device was available this run, so **all behavioral acceptance is unverified** — every automated check is compile/static/unit-level. The Reviewer's 6-check on-device smoke list (in particular: reply-after-admin-broadcast and reply-after-admin-DM, which exercise the bug that was fixed) must be run before this reaches the live ~100-member group. Also unverified by any automated test: the CSV-import welcome-hint change. Logged as non-blocking for later: surface unmetered reply/`#to` volume in the limits UI; `getRecent` doesn't filter `OUT/REPLY`, so replies appear twice in Recent Activity (pre-existing behavior shared with `#admin`, not a regression).
Config changed mid-run: **`~/.claude/commands/build.md` was edited during this run** at the user's request — `/simplify` now runs after *every* stage that produced production code (once per phase on multi-phase work), before that stage's commit and version bump, with a mandatory Tester re-run afterward. Previously it ran once, at final delivery.
