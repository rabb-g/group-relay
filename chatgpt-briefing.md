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

### Run: /build — 2026-09-22 (Phase 2)
Task:               Phase 2 of the jRelay fork — coalescing window (v5.0 → 5.1).
Outcome:            APPROVED (Reviewer verdict: APPROVED WITH RECOMMENDATIONS)
Iterations:         4 Reviewer cycles; 11 agents dispatched in parallel for the build wave, 4 more for `/simplify`.
Files:              New `sms/MessageMerger.java` + tests and `docs/phase3-device-spike.md`; modified `DbHelper`, `OutboxRepository`, `MessageSalt`, `Prefs`, `SmsSendService`, `CommandProcessor`, `SettingsActivity`, `MainActivity`, `activity_settings.xml`, 3× `strings.xml`, `build.gradle.kts`, `README.md`, `VERSION.md`. Suite 35 → 51 tests.
Key decisions:      Merging is **per recipient, not per post** — which is what makes a post folded into an earlier post's undelivered tail work at all. Segment cap measured on a *salt-representative* body, because the zero-width-space salt forces UCS-2 (70 chars/segment instead of 160) and counting the unsalted body would undercount by ~2×. `MessageSalt.applyAll` was re-expressed AS the composition of its two halves rather than mirrored, so the transform order cannot drift. Merge logic isolated in an Android-free class with an injected `SegmentCounter`, purely so it could carry real JVM tests.
Issues encountered: The Reviewer found four things no green test suite could have caught. (1) **Send-rate multiplication** — the hold loop keeps a drain alive while rows are held, so without a guard every inbound post would have added another concurrent burst loop; an active group could have run at many times the configured rate, which is the precise carrier-filtering failure the pacing engine exists to prevent. (2) A **non-transactional merge** racing concurrent drains could double-deliver and clobber a sent row's status. (3) The **segment cap under-counted ~2×** (the ZWSP/UCS-2 issue above). (4) Then **two defects in the guard that fixed (1)** — a suppressed start called `stopSelf` against the most recent id, destroying the service under a live drain (the common case, since coalescing exists for posts that cluster), and the re-entry it recommended used `startService` from a background thread, which throws on API 26+ and would have killed the relay. Re-entry is now in-thread. Separately, moving salting to send time changed behavior even at window=0; the owner decided per-recipient salting is correct (the old behavior sent 100 byte-identical bodies, defeating the point of salting) and it is documented as a deliberate change rather than reverted.
Open items:         **Nothing behavioral is verified** — no device was available, and the lifecycle fixes in particular are invisible to JVM tests. The 8-item on-device checklist below must be run before this touches the live group. Deferred, non-blocking: reset orphaned `SENDING` rows at startup (a mid-drain process kill strands them); wrap the drain in a `catch (Throwable)` so an unexpected DB error logs instead of killing the relay process; a residual few-instruction window where `stopSelf` can target a newly-started drain's id (costs process priority, not messages); one test asserting `applyAll` equals its composition.

---

## On-device checklist before the live ~100-member group

Nothing in CI substitutes for these. Items 1 and 2 are the feature's own happy path; 3–8 are the failure modes found in review.

1. **Three posts inside one window, ~100 members** — each member receives ONE text containing all three, in order, salted once. This is the primary acceptance test for the phase.
2. **A post made during an active fan-out** — post A to ~100 members, let it drain partway, then post B. Verify all three of these, in this order of importance:
   - **Every** member receives B's content. Merging must never cost a recipient — it changes packaging only.
   - Members already served A receive B as a **separate** text.
   - Members still queued receive **one** text containing A then B, in that order.
   This is the case that matters most in practice, because replies arrive while the previous post is still going out. Check the queue count reaches 0 afterwards with nothing stuck in `SENDING`.
3. **A command during a hold** — text `HELP` while posts are held; the reply must arrive promptly rather than waiting out the window.
4. **Two posts during one drain** — the service stays alive and the second post's messages go out.
5. **Full fan-out with the screen off and the app backgrounded** — watch logcat for `IllegalStateException` and for the service being stopped mid-drain. Closest thing to real operating conditions.
6. **Upgrade in place from the installed 5.0 build with messages already queued** — the backlog drains and those bodies go out unchanged. The 4→5 migration has never run against a real database.
7. **Pause pressed mid-fan-out** — sending stops within one burst.
8. **Coalesce window set to 0** — one post behaves as 5.0 did, allowing for the three documented deviations (per-recipient salting, pause honoured mid-drain, single-drainer guard).
9. **A post crossing the segment cap** — recipients get two texts, nothing lost or duplicated.

After 1, 2 and 5, confirm the dashboard queue count returns to 0 and no rows are left in `SENDING` — the cheapest check for the stranded-row risk under real conditions.
