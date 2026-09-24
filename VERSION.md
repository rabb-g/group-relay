# Version History

## 5.7 - The Rest of the Audit

The second and largest remediation wave. Thirteen parallel units, and it clears most of what the
September audit found — including the one finding that let any member pretend to be you.

- **A member can no longer forge a message from an admin.** This was the worst thing in the audit.
  Relayed messages are shown as `Name: what they said`, and when several posts are combined into
  one text they are stacked on separate lines. Nothing stopped a member from putting a line break
  inside their own message followed by `[Admin]: ...` — and what arrived on ninety-nine phones was
  indistinguishable from a real notice from you. No warning, no trace, nothing to notice
  afterwards. The same trick worked by simply renaming yourself to `[Admin]`.
  - A relayed message is now always a single line, so the sender's name is always the first thing
    on it and an impostor line can never start one.
  - Nicknames are now checked: no colons, no line breaks, nothing starting with `[`, `@` or `#`,
    and a length limit. The rule about the first character is the important one — it also defeats
    lookalike letters from other alphabets without having to list them.
  - Names already stored by an older version are cleaned when displayed, so this cannot be
    exploited using data that is already there.
- **The last way a message could be sent twice is closed.** There was a brief moment while the
  outcome of a send was being recorded where a second part of the app could decide the message had
  been abandoned and send it again. A message now moves to its final state in a single indivisible
  step, so that moment no longer exists. This was the last of three such paths; the other two were
  closed in 5.5.
- **A crash in the sending thread no longer stops the relay for good.** An unexpected error while
  sending would kill the app mid-delivery, and nothing restarted it — the remaining members simply
  waited until somebody happened to text. The sender now survives an error, records it, and
  carries on. Getting this wrong would have been worse than leaving it: a related piece of
  bookkeeping had to be moved first, or the fix would have turned a loud crash into a relay that
  silently never sent anything again.
- **Two people can no longer be created for one phone number.** If a number was entered using
  Arabic-Indic digits, it was stored differently from the same number typed in ordinary digits.
  The result was two entries for one person, who then received every message twice — while their
  own texts were not recognised as coming from a member at all.
- **`#to` with a phone number now works.** Sending `#to 5551234567 see you at 8` delivered
  nothing and told the sender they had got the syntax wrong. The app was treating the whole line
  as a name. Relatedly, a member can no longer take a nickname that looks like somebody else's
  phone number and have commands aimed at that number reach them instead.
- **Opening the app now restarts a stalled queue.** If sending stopped for any reason, opening
  jRelay did nothing about it — the host could watch a queue of 47 messages sit there. It now
  starts sending again.
- **You will be told if a permission is taken away.** If Android revokes the SMS permission —
  which it does on its own for apps it thinks are unused — the app kept running, looking perfectly
  healthy, and silently relayed nothing. A warning now appears on the dashboard, and tapping it
  goes straight to the setting.
- **Denying a permission at setup is no longer ignored.** The first-run screen asked for
  permissions and then carried on regardless of the answer, leaving a relay that appeared set up
  and could not send. It now explains what will not work and offers to ask again or open Settings.
- **Admin alerts no longer stop arriving.** Every alert created a new notification and none was
  ever cleared, so after about fifty Android silently discarded the rest — permanently, on a phone
  nobody taps. Alerts now collapse into one that updates in place.
- **The message history is indexed and no longer grows forever.** There was not a single index in
  the database, and nothing was ever deleted — roughly a hundred rows per message posted, about
  three quarters of a million a year, every one of them scanned each time the dashboard refreshed.
  Indexes are added and history older than ninety days is removed once per sending run.
- **The member statistics were wrong and are now right.** "Sent" counted every message twice.
  The activity rating measured messages a member *received*, so in a large group everybody looked
  highly active and "inactive" was unreachable. And "today" on the dashboard covered a different
  period from "today" in the daily limit, so the two disagreed.
- **The app fits the screen on Android 15.** Newer Android draws behind the status bar and the
  navigation area; nothing accounted for it, so headings sat under the clock and the bottom of
  every screen was unreachable. On a folded phone that is a large share of the usable space.
- Hebrew and Yiddish kept in sync with the six new strings (350 in each of the three files).
- Test suite 71 to 110. The new tests cover the message and nickname checks that stop the
  impersonation described above, and the phone-number handling — all of which had none.
- Updated `README.md` and `VERSION.md`.

## 5.6 - Group Capacity, and an End to Announcement Spam

Two features, both about the same thing: not spending your daily message budget on things that
aren't conversation.

- **Group Notices: every announcement is now individually switchable, and most are off.** Until
  now, six ordinary events each sent a text to *every* member — someone leaving, a nickname
  change (by them or by an admin), a removal, a group rename, and a mode change. At a hundred
  members that is a hundred messages per event, none of it anyone's actual message, and they were
  never merged with anything. Five admin actions in a day came to roughly five hundred texts, on a
  line whose safe ceiling is about a thousand.
  - There is now a **Group Notices** section in Settings with one switch per announcement. The
    setting that already existed for "member added" has been moved in there too, so all six live
    in one place instead of five being invisible and one being somewhere else.
  - **Five of the six now default to off.** That is a deliberate change from how the app behaved
    before, not an oversight.
  - **Mode changes still announce by default, and that one is worth leaving alone.** In Reply Mode
    a plain text reaches only the person whose post you are replying to — you have to write `#all`
    in front of a message meant for everyone. If the mode changes and nobody is told, the next
    message a member sends goes quietly to one person instead of the group, with nothing to
    indicate it. That announcement is the only thing standing in the way of it.
  - **Whoever an action actually happens to is always told, regardless of every switch above.** If
    you are removed you hear about it; if your name changes you hear about it. Only the
    group-wide announcement is affected. While wiring this up we found a case where that was not
    true: an admin renaming someone from the app told nobody at all, not even the person renamed,
    whose name then appeared differently on every message they sent. They are now told. That was
    survivable when the group announcement always fired and they would have heard it second-hand;
    with announcements off it would have been silent, so it is fixed here rather than left.
- **A maximum group size, with an automatic reply when it is reached.** Set the number in Settings
  → Group Capacity; 0 means no limit, which is how it ships, so nothing changes unless you ask for
  it. The section also shows how many active members you currently have, so you are choosing a
  number against a real one.
  - Someone texting `#join` when the group is full gets an automatic reply and is not added. The
    wording of that reply is yours to edit in Settings; leave the field empty and it falls back to
    the built-in text.
  - The refusal happens **before** admins are notified, so a full group does not send every admin
    a request they have no way to accept.
  - **The limit applies to admins too** — adding by text or in the app is refused the same way,
    with a message naming the current count and the limit so it is obvious what to change.
    A cap only admins can walk around is not a cap, and raising it is one screen away.
  - **A CSV import fills up to the limit rather than failing.** Import 120 rows into a group
    capped at 100 and you get 100 members, with the rest reported as skipped and a note saying the
    group filled up — not an all-or-nothing rejection, and not a silent overfill.
  - Only active members count. Somebody who left does not hold a seat, and re-adding a member who
    is already in the group was never a new seat and still isn't.
- Hebrew and Yiddish kept in sync with the eighteen new strings (346 in each of the three files).
  The Hebrew and Yiddish "group is full" replies deliberately drop the English "Sorry" — partly to
  fit inside a single message segment, and partly because none of the app's other outgoing texts
  open with an apology.
- Updated `README.md` and `VERSION.md`.

## 5.5 - Audit Fixes, Part One

A full adversarial audit of the whole codebase found around sixty real problems (written up in
`docs/audit-2026-09.md`). This is the first remediation wave: the ones that expose data, send
duplicates, or take the relay out of your control. Nothing here is a new feature.

- **The app no longer ships as a debuggable build, and no longer backs your members up to Google.**
  Two separate exposures of the same data, both present since the first release.
  - The APK you install is the debug build, and Android's default marks that build debuggable.
    Anyone with a machine that is authorised for ADB could run one command and copy the entire
    database off the phone — every member's number and every message ever relayed, no root
    needed. That phone gets plugged into a PC as a matter of routine, because that is how the
    outgoing-SMS-limit setting gets granted. The build now explicitly turns that off. Logcat
    still works, so nothing on the device checklist is affected.
  - Backups were on, and both backup rule files were the unmodified template Android Studio
    generates, with every rule commented out — so the default applied and the database was
    included. That sent the member roster and the full message history to whichever Google
    account the phone is signed into, and carried it along in a phone-to-phone transfer. None of
    the hundred people in the group agreed to their number leaving the relay. Backups are now off,
    and both rule files have been filled in as well, so that switching backups back on later
    cannot quietly re-expose anything.
- **Two ways a message could be sent twice are now closed.** Both were in the part of the app that
  had already been reviewed the most, and neither needed anything unusual to happen.
  - When a message was given up on for having gone quiet too long, the app marked it dead but left
    behind the two values a late reply is matched against. If that message was then picked up and
    re-sent, a reply belonging to the abandoned attempt could still be accepted, and it would push
    the message — already going out again — back into the queue for a third try.
  - Three places that change a message's state did so without first checking the message was still
    the one they thought it was, while every comparable place in the same file does check. That let
    a late reply overwrite a send already in progress, discard that send's real outcome as stale,
    and queue the message up again.
  - **Still open, and being fixed next:** one further path remains where a message can be resent
    while its outcome is being recorded. It is narrower than it was but not gone. It needs the
    recording step made atomic, which touches the most carefully guarded code in the app and
    deserves its own release.
- **A failing admin number can no longer generate its own alerts forever.** When a member's
  messages fail repeatedly, admins get a warning. That warning was being counted against the
  failure record of the admin it was sent to — so if an admin's own number went bad, the warning
  about it failed, which produced another warning, which failed. It fires on exactly the
  conditions that mean the line is already struggling: no service, radio off, or the carrier
  refusing messages. The app's answer to "this line is in trouble" was to put more traffic on it.
  The fix is one line, and the correct version of it was already sitting in a neighbouring method.
- **The last admin can no longer lock everyone out of the group.** Texting `STOP` as the only
  admin — or a stray "Cancel" in conversation, which counts as the same thing — removed the final
  admin, after which every admin command refused everyone, permanently. There was no way back by
  text: re-adding that person returns them as an ordinary member, and the only place admin status
  can be granted is the app itself. Whoever held the phone would have had to fix it by hand. All
  four routes to that state — two by text, two in the app — now refuse and explain why.
- **Opening the Settings screen no longer cancels a pause.** The screen's pause control fired its
  own "resume" as soon as it was drawn, before you touched anything. Because of how it was
  guarded, it did nothing at all when the relay was running and fired only when it was paused —
  so pausing the group, then opening Settings for any unrelated reason, silently resumed sending
  and released the whole backlog. The screen then showed "Not Paused", so it looked correct.
- **Send pacing can no longer be set to a value that breaks the relay.** Every pacing field had a
  minimum but no maximum. A large enough wait could put the sender to sleep for years, and because
  the pacing settings are read once when sending starts, changing the value back had no effect —
  only force-stopping the app recovered it. A particular pair of values crashed the sender
  outright, and since it runs on its own thread, that killed the app mid-send. All the pacing
  fields now have sensible upper bounds.
- **A blank text no longer costs a hundred messages.** An empty or whitespace-only message — a
  pocket-send — was relayed to the whole group as an empty post and charged against the daily
  limit.
- **Commands written with a line break no longer vanish.** The app looked for a space to separate
  a command from what follows it, so `#admin` followed by a new line left nothing after the
  command and the message was dropped with no reply at all. Seven commands were affected. The same
  problem had already been found and fixed in two other places; these were missed.
- Command matching is now independent of the phone's language setting, which could otherwise stop
  every command from being recognised on certain locales.
- Hebrew and Yiddish kept in sync with the two new strings (328 in each of the three files) — one
  sent as a text to the departing admin, one shown in the app, deliberately worded differently
  because one addresses the person leaving and the other describes someone else on screen.
- Updated `README.md` and `VERSION.md`.

## 5.4 - Retry Backoff, `#help`, and Two Things the App Was Getting Wrong

A small release on top of 5.3. One behaviour change, one new command, and two places where the app was telling somebody something untrue.

- **A failed message now waits before it is retried, and how long it waits depends on why it failed.** Until now every failure was retried on the very next burst regardless of cause. That is fine for an ordinary hiccup and exactly wrong for a rate refusal, where an instant resend is the one response this app's entire pacing design exists to avoid. Rate refusals now wait 5 minutes; a radio that is off or out of service waits 1 minute, since both resolve themselves; everything else waits 30 seconds rather than going out immediately.
  - No new machinery was needed. A backed-off message is held exactly the way the coalescing window already holds one, so the existing burst draw skips it until it is due — no new column, no migration, no scheduler, nothing new that can fail.
  - **Known limitation, to be fixed in 5.5 — the 5-minute figure is very likely too short.** The usual source of a rate refusal is Android's own per-app limit, and its window is 30 messages per *30 minutes*. Five minutes later that window is often still closed, so the message spends its single retry for nothing and is then marked failed. Raising the number is not the real fix either: the limit applies to the **whole line**, not to one message, so backing one message off while the queue keeps sending at full pace simply burns every message's retry in turn. The real fix is to hold the entire queue when the limit is hit, which is the next release. Until then the remedy is the one the app already offers — raise the outgoing SMS limit in Settings. This is still strictly better than 5.3, which resent instantly and failed instantly.
- **New `#help` command: how to phrase a message right now.** Any member can text `#help` and get an answer that matches the group's current mode — in Reply mode it explains the `#all` prefix, in Announcement mode it explains who may post, in Group mode it says to just text normally. Before this release `#help` was not a command at all and got back "Unknown command", which is a poor answer to the most natural word a confused person will type.
  - A **muted** member gets a different answer, because the mode-based one would be false for them: nothing they send is relayed and nothing is delivered to them, whichever mode the group is in. They are told they are muted and pointed at `#unmute`, the one command that fixes their situation — which the general help never mentions.
  - Bare `HELP` with no `#` still returns the full command list, unchanged. It is a carrier opt-out convention and has to keep working; the two now cross-reference each other, so whichever a member types they end up somewhere useful rather than at a dead end.
- **The dashboard no longer claims a held message is waiting to merge when it isn't.** It read "Holding *n* to merge", which was true when coalescing was the only reason to hold a message. Retry backoff now holds messages too — so a host who had turned coalescing **off** would have seen "Holding 1 to merge" for up to five minutes with merging disabled, on the exact screen they would be looking at to find out why a message hadn't gone out. It now reads "Holding *n*", which is true either way.
- Removed a leftover accessor that nothing called. The diagnostic column it read is still written and still there — the note explaining why it is deliberately write-only is now on the column itself.
- Hebrew and Yiddish kept in sync with the new help strings (326 in each of the three files), reusing the existing wording for "muted" and for the relay verb so the new text reads like the rest of the app rather than like a translation of it. Test suite 66 to 71.
- Updated `README.md` and `VERSION.md`.

## 5.3 - Real Delivery Results (and a long-standing silent message loss, fixed)

Until now the app had no idea whether a message actually went out. It passed no delivery callback to Android and treated "the send call didn't throw" as success — but network rejection, no signal, no service and rate refusals all complete without throwing. So the retry counter, the per-member failure count and the admin failure alerts were all watching the wrong thing: they could only react to the rare case where the send call itself failed, which is not how a message usually fails to arrive.

To be clear about what this does **not** buy: it cannot detect carrier filtering. Filtering is silent by design — the network accepts the message, reports success, and drops it downstream — so no delivery result is ever produced and nothing here can see it. What this release surfaces is the set of failures that *announce themselves*, which until now were all being recorded as successful deliveries.

- **Messages now report their real outcome.** Each outgoing message (each part, for long ones) carries a callback that Android fires with the actual result, and that result decides what happens: delivered, retried, or marked failed. Failures show a plain reason in the member's activity feed — *Carrier rejected the message*, *Phone had no signal (airplane mode or radio off)*, *No cell service at the time*, *Sending too fast -- check the outgoing SMS limit in Settings*, and so on. An unrecognised code is shown with its raw number rather than guessed at, so an unfamiliar response stays diagnosable instead of being mislabelled as something it isn't.
  - **The rate reason points at a setting on the phone, not at your carrier.** That result code is most often Android's own per-app SMS throttle — 30 messages per 30 minutes by default — rather than the network, and the app already has a Settings screen for it. The wording says so, instead of asserting a cause it cannot actually distinguish.
  - Retries, failure counts and admin alerts now fire on real failures. They were never wrong before so much as blind — they simply never triggered for the failures that actually happen.
- **Long Hebrew and Yiddish messages were being silently lost, and are now fixed.** This is not a 5.3 regression; it has been present since the first release. The app decided whether a message needed splitting by counting *characters* against the 160-character limit — which only applies to plain Latin text. Hebrew and Yiddish are sent in a different encoding with a **70-character** limit per part, and the zero-width-space salt forces that encoding on any message. So a message of roughly 71 to 160 Hebrew or Yiddish characters — a sentence or two — was sent as if it fit in one part, could not actually be encoded, and never arrived. The app marked it delivered and nobody ever knew. Splitting is now decided by the same segment count Android itself uses, for any language and any salt setting.
  - Worth knowing: until this release the app would have reported those messages as sent. If members have occasionally mentioned not receiving something, this may be why.
- **A late result can no longer resolve the wrong send.** Each send attempt carries a token, and a result that doesn't match the current attempt is ignored. Without it, a stale result arriving after a message had already been retried could mark the new attempt delivered before it had actually gone out — and then swallow its real failure.
- **A message can no longer be sent twice after a crash.** The callbacks Android holds outlive the app's own process, so identity had to be built from values that survive a restart rather than from a counter that resets with it. Without that, a restart mid-send could tangle two different messages onto one callback: one silently re-sent to somebody who already received it, the other marked delivered when it had failed.
- **Messages whose result never arrives can no longer retry forever.** A message handed to the radio that never reports back is retried, but that retry now counts against the normal limit, so it eventually stops rather than looping indefinitely — with every loop a chance of a duplicate.
  - A message that never reached the radio at all is treated differently: it is retried without burning an attempt, because nothing was ever actually sent.
  - After a reboot the app can tell that older in-flight messages are dead — Android's callbacks don't survive a restart — and recovers them immediately instead of waiting.
- **A send now finishes before the app stands down.** Results arrive slightly after a message goes out, so the sending service stays up briefly to receive them, with a short cap and an immediate exit if new messages arrive. Without it, a failure on the last message of a group send could sit unretried until some unrelated message happened to arrive — possibly hours on a quiet night — and failures cluster at exactly the moment that matters.
- Hebrew and Yiddish kept in sync with the ten new strings (322 in each of the three files). Test suite 51 to 66: the new tests pin the failure-reason mapping, including that "never reached the network" and "delivered" can never collapse onto the same value — a bug that was caught in review this release and would otherwise have displayed a failed send as a successful one.
- Updated `README.md` and `VERSION.md`.

## 5.2 - Foreground Service, Reboot Recovery, Stranded-Message Repair

No new features. This release is about the relay not stopping — every change below protects sending that 5.1 already does.

- **The send service now runs in the foreground, with a notification.** A full fan-out takes about three minutes, and since 5.1 a drain can also hold messages open for the coalescing window. A plain background service doing that is fair game for Doze, OEM battery throttling and ordinary memory pressure — and when it gets killed the symptom is simply that messages stop, with nothing anywhere to say why. A foreground service is the framework's own mechanism for "this is doing real work, don't kill it", and it is the actual fix for that; the battery exemptions and boot recovery below only paper over it.
  - The notification is deliberately useful rather than an annoyance on a dedicated relay phone: it shows pending and held counts and whether a burst is scheduled, updated once per burst cycle (never per message, and with no extra database query). It sits on its own silent, low-importance channel so it never makes a sound, and tapping it opens the dashboard.
  - Declared as `specialUse` with a justification, not `shortService` — the latter caps out around three minutes, which a 100-member fan-out already brushes against, so it would time out mid-group.
  - `startForeground` is called as the very first thing on every path into the service, including one that is about to be suppressed as a duplicate. Android gives roughly five seconds to make that call and kills the app otherwise, and a suppressed start has still asked for a foreground service — so skipping it there would crash precisely when two posts arrive close together, which is the case coalescing exists for.
  - **A foreground service does not keep the processor awake.** It stops the service being killed; it does not stop the device suspending. With the screen off, the waits between bursts can stretch, so a fan-out may take longer than the configured pacing predicts. Messages are delayed, never lost. If that proves material in practice, a wake lock is the next step.
- **Messages stranded by a crash are now recovered.** The outbox claims a whole burst by marking those rows `SENDING`, and nothing ever moved them back — so every kill mid-fan-out silently lost that burst while still counting it in the dashboard's queue, which then never drained and never explained itself. `OutboxRepository.resetOrphanedSending` returns them to `PENDING`.
  - It is only safe to call when no drain can have rows claimed, since resetting a live burst would deliver it twice. Exactly two conditions establish that, and both are now documented on the method itself rather than left implicit in its callers: immediately after winning the single-drainer guard, or while the service is paused.
- **The outbox resumes after a reboot.** There was no boot receiver at all, so queued messages sat until some member happened to text the group — which on a quiet night could be hours. This is not hypothetical on the intended hardware: Samsung ships an "auto restart at set times" option, sometimes enabled by default. `BootReceiver` now repairs stranded rows and starts a drain, and respects Pause: if the group is paused it repairs the rows and stops there, because the whole point of Pause is that nothing sends.
  - Note for whoever hosts the phone: the app is not direct-boot aware and does not try to be — its database is encrypted until first unlock and genuinely unreadable before then. So after a reboot the relay resumes once the phone has been unlocked a single time, not before.
- **Starting the service can no longer crash the relay.** On modern Android a background app may not start a foreground service, and the app relies on an exemption granted while handling an incoming SMS. That exemption is expected to hold, but if some manufacturer's build does not honour it the exception would have propagated out of the SMS receiver and killed the process on *every inbound message* — a total, silent outage. The call is now guarded, and logs at error level, turning a dead relay into one missed send trigger that the next message recovers from.
- Tearing the service down now checks whether it is actually the one stopping before dropping foreground status, so a drain that has just taken over does not lose its protection.
- Hebrew and Yiddish kept in sync with the six new notification strings (312 in each of the three files), reusing the wording the dashboard already uses for "pending" and "held" so the two describe the same state with the same word.
- Updated `README.md` and `VERSION.md`.

## 5.1 - Coalescing Window, Per-Recipient Salting, Single-Drainer Guard

- **Coalescing window** (new **Coalesce window (seconds; 0 = off)**, default 45, and **Max segments per merged message**, default 3, both in Settings → Send Pacing): when several posts are made within a few seconds, each member receives them as **one** text instead of one text each. A relayed post is held briefly before sending; posts that land in the same window are joined with newlines, in order, into a single body per recipient. Three posts in 45 seconds to 100 members goes out as 100 sends instead of 300, and a person composing one longer message is what carriers expect to see.
  - Applies **only** to relayed posts (`RELAY` rows). Command replies, system notices, admin messages, DMs and Reply-Mode replies are never held and go out immediately, as before.
  - A merged body never exceeds the segment cap: before each line is appended the candidate body is measured with `SmsManager.divideMessage(...).size()`, and if it would overflow, the current merged message closes and a new one starts. A single post that alone exceeds the cap is still sent intact — never split, never dropped.
  - Segments are measured on a **representative salted** body, because the zero-width-space salt injects U+200B, which is outside GSM-7 and forces the whole message to UCS-2 (70 characters per segment instead of 160). Measuring the unsalted body would let a message measured at 3 segments go out as 6 or 7 — and segments are what the carrier budget is actually spent in.
  - New `sms/MessageMerger`: the merge decision logic, deliberately free of any Android API (segment counting is injected) so it is covered by real JVM unit tests. 12 new tests, including that an oversized single message is emitted whole and that order is preserved across and within merged groups. Suite is now 51 tests.
  - There is still no background alarm in this app. When the send service finds only held rows, it sleeps until the earliest one is released — in short slices on the worker thread it already uses for inter-burst waits, waking early if something sendable (a command reply) arrives meanwhile. As before, if the process is killed while holding, the backlog goes out on the next natural trigger.
  - Dashboard: the queue line reports held rows — appended to the existing "Next burst in Xs" countdown when a burst is scheduled, and shown on its own as "Holding N to merge" when everything is held, so a host never sees a post apparently vanish for 45 seconds with no explanation. The **Sending…** badge stays hidden while merely holding, since nothing is being sent.
- **Salting now varies per recipient, which is a deliberate behavior change.** Previously a post was salted once and that single finished body was sent to every member — so all ~100 copies were byte-identical, and salting only ever distinguished one post from another, never one copy from another. That is exactly the pattern carrier filtering looks for, so the feature was doing far less than intended. Salting now happens once per outgoing message, at send time, so every recipient's copy differs.
  - Consequence worth knowing: the **timestamp** salt now reflects when *that member's* copy was sent, not when the post was made. At the default pacing (burst 3-5, wait 3-8s, microspacing 350ms) a 100-member fan-out takes roughly 3 minutes, so the spread across the group is small; slower pacing widens it proportionally. Invisible in the `HEX_SECONDS`, `HEX_MILLIS` and `UNIX_SECONDS` formats; visible in `TIME_HHMMSS`. Switching format is the fix if it ever matters.
  - Mechanically, `MessageSalt.applyAll` split into `applyEnqueueTime` (strip phone numbers, append sender number — per original message) and `applySendTime` (timestamp, hex, zero-width space — per outgoing message); `applyAll` now *is* the composition of the two, so the transform order cannot drift from the original. `outbox.apply_salt` marks which rows are salted at send. Retries are also re-salted fresh now, where 5.0 resent a byte-identical body — an improvement for the same anti-filtering reason.
- **Single-drainer guard** (`SmsSendService`): only one drain may run at a time; a start while one is in progress is dropped rather than spawning another. This became essential in 5.1 — the drain loop now stays alive while rows are held, so without the guard every inbound post would have added another concurrent burst loop with its own independent pacing, and an active group could have been sending at many times the configured rate. That is the precise failure the pacing engine exists to prevent. The running drain re-queries the queue every iteration, so a dropped start loses no work.
- **Merging is atomic.** `OutboxRepository.applyMerge` rewrites the kept row's body, clears its hold and marks the folded rows `MERGED` in a single transaction, with every write guarded on the row still being `PENDING`; if any row was already claimed by another send, the whole merge is abandoned and nothing is written. Without this, a merge racing a concurrent send could deliver the same text twice and overwrite a sent row's status. The non-atomic helpers it replaced were removed outright so the pattern cannot be reintroduced.
- **Pause Service is now honoured mid-drain**: `isPaused()` is re-checked at the top of every loop iteration instead of only when the drain starts. Previously a pause pressed during a fan-out kept sending until that drain finished — a few minutes on a 100-member group at the default pacing, longer with slower settings, and longer again in 5.1 where a drain stays alive while rows are held — despite the setting being documented as stopping SMS activity immediately.
- The dashboard no longer counts down to a burst that cannot happen: when every pending row is still held, burst sizing and the inter-burst wait are skipped entirely.
- The coalesce window is clamped to 600 seconds in Settings, so a mistyped value cannot silently hold the group's messages for hours. 0 still means off.
- **Schema**: `outbox` gains `category`, `hold_until` and `apply_salt`; `DbHelper` bumped to DB version 5, migrated additively as always. The new columns' defaults mean any message already queued at upgrade time stays immediately sendable and goes out exactly as 5.0 would have sent it.
- Hebrew and Yiddish kept in sync with every new string (306 in each of the three files). Updated `README.md` and `VERSION.md`.

## 5.0 - Reply Mode, Bare Keywords

- **Reply Mode** (new `Prefs.GroupMode.REPLY`, default still `GROUP`): a third group mode aimed squarely at send volume. Anyone can still post to everyone, but a *plain* reply goes only to the person who posted, so replies stop multiplying by the member count. On a ~100-member group this is the difference between a reply costing 100 SMS and costing 1.
  - **Posting**: a message starting with `#all ` or `all:` (case-insensitive) is an explicit post. The prefix is stripped and the message runs the **existing** Group-mode relay path unchanged — group/individual daily-limit checks, `tpl_relay_prefix`, `MessageSalt.applyAll`, the `RELAYED` quota marker, then the fan-out. Applies to admins and members alike.
  - **Replying**: a plain message with no prefix goes only to the author of the last post that member *received*, formatted `<nickname> (reply): <text>`, logged under a new `REPLY` category. Replies deliberately **do not** count against daily limits, mirroring how `#admin` and Announcement-mode routing already behave.
  - **No valid target** (no post received yet, reply window expired, the author has left, or the sender wrote the last post themselves): the message is routed to admins via the existing `sendToAdminsOnly`, and the sender gets `tpl_reply_no_target` explaining how to post with `#all`. Also uncharged.
  - **`#to <nickname> <message>`** (Reply Mode only): reply privately to a named member when two posts arrived close together. Uses the same tolerant nickname-or-phone matching `#remove` uses, and supports nicknames containing spaces via longest-prefix matching. In other modes it replies "Only available in Reply Mode."
  - **Copy replies to admins** (new setting, off by default): also sends each delivered reply to active admins as `@admin reply from <sender> to <target>: <text>`, skipping muted admins and skipping the sender and target so nobody receives it twice.
  - `#commands` gains `#all`/`#to` while in Reply Mode, and the welcome texts (`tpl_added_you`, `tpl_added_you_self`) gain a mode-aware sentence explaining `#all` — including for CSV-imported members, which the first cut missed.
  - `#all` also works in **Group mode** (the prefix is simply stripped and the message posts as normal), so members who learn the habit in Reply Mode are never punished for it. Group and Announcement mode are otherwise completely unchanged — the Group-mode relay body was moved verbatim into a new `postToGroup` method rather than rewritten.
- **Tracking the reply target** (new nullable `members.last_post_received_id`, `DbHelper` bumped to DB version 4, additive `ALTER TABLE` as always): holds the `message_log` id of the `RELAYED` marker for the last post that member received, so targeting and quota both read from one source of truth instead of a denormalized copy. `MessageRepository.log` now returns the inserted row id, and a new `MessageRepository.getById` resolves it.
  - Written in one batched statement per fan-out (`MemberRepository.setLastPostReceivedIdForAll`, chunked at 500 ids to stay under SQLite's bind-parameter limit) rather than one `UPDATE` per recipient — a 100-member post costs 1 extra write instead of 100.
  - Deliberately **cleared** whenever a member receives something that isn't a relayed post: the app's **Send to Group** broadcast, an admin direct message, and on rejoining via `MemberRepository.reactivate`. Without this, replying to an admin broadcast silently delivered your reply to some unrelated member who happened to post earlier, and the admin never saw it.
  - A pointer left dangling by **Clear History** is harmless: `message_log.id` is `AUTOINCREMENT` so ids are never reused, the lookup returns null, and the reply falls through to the admin route.
- **Bare keywords** (new **Commands** settings section, on by default, applies in *every* mode): a whole-message, trimmed, case-insensitive `STOP`, `UNSUBSCRIBE`, `CANCEL`, `QUIT` or `END` acts as `#stop`; `HELP` acts as `#commands`; `MUTE`/`UNMUTE` act as `#mute`/`#unmute`. Whole-message matching only, so "stop by later" still relays as ordinary text. This exists because carriers expect opt-out words to work without a leading `#`.
- **New `sms/MessageIntent`**: a small pure-Java parser (no Android APIs) for the post prefix, the bare keywords, and the reply-window arithmetic — deliberately dependency-free so it is covered by real JVM unit tests. New `MessageIntentTest` adds 24 cases (35 total in the suite), including that `all : hi` and `#allen` are *not* posts, that a multi-line post parses as one body, and the 24h window boundary.
- **Settings**: Group Mode's dropdown gains a third **Reply Mode** entry; a new **Reply Mode** section adds *Reply window (hours; 0 = no limit)* (default 24) and *Copy replies to admins* (default off); a new **Commands** section adds *Accept bare keywords* (default on).
- **Dashboard**: the Options menu's two-way mode toggle became a pair of entries showing the two modes that aren't currently active, and a **Reply Mode** badge sits next to the group name, matching the existing Announcement badge.
- Hebrew and Yiddish kept in sync with every new and changed string (301 strings in each of the three files). The admin command list now advertises `#mode group|announcement|reply` instead of only the two old modes.
- Updated `README.md` and `VERSION.md`.

## 4.1 - Unlimited Per-Member Limit, Pause Service, Announcement Mode UI, Cleaner Activity Feeds

- **Unlimited per-member daily limit**: the custom daily cap on Member Detail gained an **Unlimited** checkbox — when set, `daily_limit_value` is stored as `NULL` (still `daily_limit_custom = 1`), and `DailyLimitManager.Status` gained an `unlimited` flag so that member's own cap never blocks them (`MemberRepository.setDailyLimitOverride` now takes a nullable `Integer`). This only exempts them from their *own* cap — the shared group pool, if enabled, still takes precedence as before.
- **Pause Service** (new Settings section, placed at the top): a dropdown to immediately stop all SMS activity — **Pause for 10 Seconds / 1 Minute / 1 Hour / 1 Day / Until Unpaused**, or resume. Backed by a single `Prefs.pauseUntilMillis` (0 = not paused, `Prefs.PAUSE_INDEFINITE` = paused with no scheduled end, otherwise an epoch-millis resume time); `Prefs.isPaused()` is checked at the top of both `CommandProcessor.handleIncoming` (incoming messages are dropped entirely while paused) and `SmsSendService.drainAll` (outbox stays queued, undrained, until resumed). Resuming manually immediately kicks a drain (`SmsSendService.start`) to flush anything that queued up during the pause; a timed pause that expires while the app is backgrounded flushes on the next natural trigger (an incoming message or app action) since there's no background alarm/scheduler. The dashboard shows a **Service Paused** badge whenever active.
- **Announcement/Group mode now has an actual UI** (previously SMS-command-only, which was a UI gap): a **Group Mode** section in Settings (dropdown + Save) and a one-tap toggle in the dashboard's **Options** menu (label switches between "Switch to Announcement Mode"/"Switch to Group Mode" based on current state) — both call a new public `CommandProcessor.setGroupMode(newMode, changedByLabel, excludeId)`, extracted from the `#mode` command handler so the app-triggered and text-triggered paths share the same broadcast-notice logic.
- **Version info**: Settings gained an **About** section at the bottom showing `jRelay <versionName> (<versionCode>)`, read via `PackageManager.getPackageInfo` (not `BuildConfig`, which isn't generated by default under this AGP version) so it can't drift from the actual installed build.
- **Recent Activity de-duplication**: a single relayed message no longer shows up once per recipient in the dashboard's global feed, nor twice (as both `RELAY` and the quota-tracking `RELAYED` marker) for the sender. `MessageRepository.getRecent` now excludes `category = 'RELAYED'` and `direction = 'OUT' AND category = 'RELAY'` (the per-recipient fan-out copies); `getRecentForMember` excludes just the `RELAYED` marker (a member's own feed was never affected by the fan-out duplication, since each relay only ever touches a given member once).
- **Admin-relay message format** changed from `[Admin-only from <nickname>]: <message>` to `@admin <nickname>: <message>` (used by both `#admin` and Announcement-mode auto-routing) — same literal `@admin` tag kept untranslated across English/Hebrew/Yiddish, matching the existing `@system` failure-alert convention.
- Updated `README.md` and `VERSION.md`; Hebrew and Yiddish translations kept in sync with every new/changed string.

## 4.0 - Announcement Mode, Join Requests, Member Reporting Controls, Global Send Pacing

- **Send pacing reverted to global (not per-member)**: removed the per-member Send Pacing override entirely — the UI section on Member Detail, `MemberRepository.setRateLimitOverride`/`clearRateLimitOverride`, and the `rate*` fields on `Member`. `RateLimitConfig` no longer takes a `Member`; it's always resolved once from `Prefs`. `SmsSendService` no longer spawns one thread per recipient — `drainAll()` is back to a single shared burst/wait/microspacing loop over the whole outbox, matching "send pacing, bursts, and queuing are global." `OutboxRepository.takeBurst(limit, shuffle)` replaces the per-member `takeBurstForMember`/`countPendingForMember`/`getDistinctPendingMemberIds`; Delivery Queue Shuffling now shuffles which pending rows a burst draws from (`Collections.shuffle`) instead of shuffling per-member thread order. `SendQueueStatus` is back to a single global schedule instead of a per-member map. (The now-unused `rate_limit_custom`/`rate_burst_*`/`rate_initial_delay` columns are left in place in the `members` table rather than risking a `DROP COLUMN` migration on older bundled SQLite versions — they're simply no longer read or written.)
- **Member Detail** recent-activity feed now has the same thin separators between entries as the dashboard's Recent Activity feed (`UiUtil.createDivider`).
- **Member Reporting** (new Settings section): a **"Notify group when a member is added"** toggle (on by default) gating the usual welcome/broadcast texts for `#add`, Add Member, and `#join` self-adds. Off adds a member completely silently.
- **CSV import reporting modes**: importing now prompts for **Usual** (notify everyone, per row, exactly like an individual `#add`), **Streamlined** (each new member still gets their own welcome text, but every pre-existing member gets one combined "An Admin added N new members" notice instead of N broadcasts), or **No reporting** (add silently) — independent of the Member Reporting toggle above. `CommandProcessor.importMembers(List<String[]>, addedByLabel, ImportReportingMode)` replaces the per-row `addMember` loop in `MembershipActivity`.
- **Announcement Mode** (new `Prefs.GroupMode`, default `GROUP`): admins toggle it by texting `#mode announcement` / `#mode group`; anyone can check the current mode with `#mode`. In Announcement mode, only admins' plain-text messages are relayed to the whole group — everyone else's messages are instead routed to admins only (reusing the same delivery path as `#admin`) and don't count against daily limits. Switching modes broadcasts a notice and replies with the new status. The dashboard shows a colored **Announcement Mode** badge next to the group name whenever it's active.
- **Join Requests** (new `Prefs.JoinPolicy`, default `OFF`): lets a non-member text `#join <nickname>` (or bare `#join`, falling back to their phone number as the nickname) to the group number.
  - `OFF` — silently ignored.
  - `ALLOW` — added immediately (`CommandProcessor.selfJoin`), subject to the Member Reporting toggle, with a dedicated "you joined" / "X joined the group" template pair distinct from admin-added wording.
  - `REQUIRE_APPROVAL` — nobody is added automatically; admins get a notice with a ready-to-forward `#add <number> <nickname>` command, and the requester gets a brief "sent to the admins" acknowledgment. No pending-request state is tracked — an admin approves by simply sending the suggested `#add` back.
  - An existing member sending `#join` is just told they're already a member.
- **Settings**: ADB `WRITE_SECURE_SETTINGS` grant command notice is now tappable (`ClipboardManager`) to copy it directly instead of retyping it.
- Updated `README.md` and `VERSION.md` with all of the above; `rate_limit_app_desc` and related Settings copy updated to reflect global (not per-member) send pacing.

## 3.0 - Pacing Engine, Salting, Daily Limits, Settings Page, Localization, Theming

- **Send pacing engine overhaul** (`SmsSendService`, `RateLimitConfig`, new `sms/MicroSpacer`):
  - **Staggering**: the existing inter-burst wait is now a toggle (`Prefs.isStaggeringEnabled`, default on) — off skips both the inter-burst wait and the initial-delay-before-first-burst entirely.
  - **Burst Configuration**: `Prefs.BurstMode` (`RANDOM_RANGE` default / `FIXED` / `ALL_AT_ONCE`) controls how each burst's size is chosen; `RANDOM_RANGE` reuses the existing burst min/max fields, `FIXED` uses a new fixed-size field, `ALL_AT_ONCE` sends everything currently pending for that recipient in one burst.
  - **Microspacing**: paces individual sends apart *within* one burst (default 350ms, fixed or randomized between bounds). Deliberately not built on `Thread.sleep` — `MicroSpacer.waitMillis` converts a fractional-millisecond target to a nanosecond deadline (`Math.round(millis * 1_000_000.0)`) and enforces it with a self-correcting loop of short `LockSupport.parkNanos` calls, re-measuring `System.nanoTime()` each iteration instead of relying on one coarse, drift-prone sleep call.
  - **Delivery Queue Shuffling** (default on): `SmsSendService.drainAll()` now shuffles the order recipient threads are started in, so dispatch order isn't a repeating pattern.
  - **Retries**: `outbox` gained an `attempts` column; a failed send is requeued as `PENDING` (picked up by a later burst) until a configurable retry limit (default 1, meaning 2 attempts total) is exhausted, at which point it's marked `FAILED` and logged.
  - Burst mode, microspacing, and staggering are group-wide only (not currently per-member-overridable); burst/wait ranges and initial-delay remain member-overridable as in 2.0.
- **Message content transforms**, applied only to relayed member broadcasts (new `util/MessageSalt`):
  - Optional: append the sender's own number (bare 10-digit digits, e.g. `2345678910`) to the end of the message.
  - Optional: strip anything that looks like a phone number (regex) out of a member's message before relaying it.
  - **Salting** (all off by default): append a unique send timestamp (4 selectable formats, including two hex formats for a short-but-unique tag), append/prepend a random 3-digit hex code, and/or randomly inject invisible zero-width spaces (U+200B) between words — so otherwise-identical messages don't look byte-for-byte identical to the carrier.
- **Failure tracking & admin alerts**: a send that exhausts its retries is logged with a new `message_log` category `"FAILED"` (so it shows up in Recent Activity feeds) and increments a new `members.failed_count` column (reset on that member's next successful send). Once the count crosses a configurable multiple of a failure-alert threshold (default 3), every admin gets an `@system: <nickname> has had N failed messages.` broadcast + notification.
- **Two-tier daily relay limits** (new `util/DailyLimitManager`, new `members` columns `daily_limit_custom`/`daily_limit_value`/`daily_limit_bonus`/`daily_limit_bonus_window_start`):
  - A shared **group daily pool** (optional, off by default) that takes precedence over everything else — once exhausted, every member's relayed messages are blocked until reset, with a reply explicitly citing the **group limit** and reset time.
  - An independent, optional **per-member daily cap**, settable on the Member Detail screen or bulk-seeded from Settings via **Apply to All Members** / **Apply to Members Without a Custom Limit**. A member hitting only their own cap is told it's an **individual limit**.
  - Both usage numbers are derived from `message_log` (a new `"RELAYED"` category logged only on the success path, deliberately distinct from the general `"RELAY"` inbound-log category so blocked/command messages never get counted) rather than a denormalized counter, so a single log write keeps both the group and per-member counts correct.
  - Both caps reset at the same configurable time of day (`Prefs.getDailyLimitResetMinuteOfDay`, default midnight); each side's "bonus" (added by an override) is lazily zeroed once its stored window no longer matches the current one, so no background job/alarm is needed.
  - New admin commands: `#limits` (today's group status) and `#override` (`#override` alone tops up the group pool by 1; `#override <nickname or number>` tops up that member's individual cap by 1, using the same tolerant name/number matching as `#remove`, and errors if they have no custom cap set).
  - Member Detail gained a matching **Override (+1 Today)** button that appears once a member's own custom cap is exhausted.
- **Dedicated Settings screen** (`SettingsActivity`/`activity_settings.xml`, replacing `RateLimitActivity`): every option above, plus the existing send-pacing and Android-outgoing-SMS-limit controls, organized into labeled sections (Staggering, Burst Configuration + Microspacing, Delivery Queue Shuffling, Message Content, Salting, Failures & Retries, Group Daily Limit, Android Outgoing SMS Limit, Language, Appearance, Miscellaneous) using `Spinner` dropdowns for the new enum-style choices.
- **Dashboard polish**: the header's rename button is now **Options**, a dropdown (matching Membership's existing menu pattern) holding **Set Group Name**, **Add Member**, **Settings**, and **Send to Group** — decluttering the always-visible button stack down to just **Membership**. Stats are now a 2-column `GridLayout` of flat, non-elevated stat tiles (Members/Admins/Muted/Messages Today/Messages Total/**Failed Today** (new)/In Queue), and the Queue tile shows a colored **Sending…** badge while a burst is active/scheduled.
- **Member Detail polish**: stats use the same stat-tile grid styling as the dashboard, with a new Messages Today tile; admin/muted badges on the Membership screen are now colored pill chips (hidden entirely when neither applies) instead of plain text.
- **Localization**: full Hebrew (`values-iw`) and Yiddish (`values-yi`) translations alongside English, selectable in Settings (or **System Default**, which follows the device). RTL was already covered by the 2.0 design pass (`supportsRtl`, start/end attributes throughout).
- **Theming**: Settings → Appearance offers **System Default** / **Light** / **Dark**, independent of the device's own setting.
  - Both localization and theming are implemented without androidx: a new `BaseActivity` (extended by every screen) wraps its `Context` in `attachBaseContext` via new `util/LocaleThemeUtil`, forcing the chosen `Locale`/`Configuration.uiMode` so `values-iw`/`values-yi`/`values-night` resources resolve correctly regardless of the device's own locale/theme. Changing either setting relaunches the app at the dashboard (same `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` pattern already used by Disband Group) so the whole back stack picks up the change.
- **Schema**: `DbHelper` bumped to DB version 3, migrated additively as in 2.0 (`ALTER TABLE`, no data loss) — new `members` columns for daily limits/bonus/failure tracking, new `outbox.attempts` column.
- Updated `README.md` with a full rundown of all of the above.

## 2.0 - Modern UI Overhaul, Per-Member Send Pacing

- **Visual overhaul across every screen**, still strictly DPAD-compatible (no cards, no overlays, every screen a root `ScrollView` over a vertical `LinearLayout`, `android:clickable`/`focusable` on every interactive element):
  - New light/dark color system (`values/colors.xml`, `values-night/colors.xml`): a blue accent, neutral surfaces, and semantic tokens for text, borders, and status (success/warning/danger).
  - New `values/styles.xml` with reusable text appearances (title/subtitle/body/label/caption) and widget styles for buttons (primary / outline / danger), inputs, checkboxes, rows, and badges — applied consistently instead of one-off inline colors/sizes.
  - New shape-drawable button/input/row backgrounds (`bg_button_primary`, `bg_button_outline`, `bg_button_danger`, `bg_edittext`, redesigned `focus_highlight`) with distinct, clearly visible **focused** and **pressed** states for DPAD navigation — rounded corners and flat color instead of shadows/elevation.
  - Every screen now opens with a full-width colored header band (title, and subtitle where relevant) instead of a bare title `TextView`.
  - Admin/muted badges on the Membership screen are now small colored pill chips instead of plain parenthetical text, and are hidden entirely when a member has neither flag.
  - `themes.xml` / `values-night/themes.xml` set app-wide defaults (button/edit text styles, primary/background/text colors) so every screen picks up the new look consistently.
- **Per-member send pacing**: each member can now have their own custom burst/wait rate limit, overriding the group default.
  - Added a **Send Pacing** section to the Member Detail screen: a "Use a custom send pace for this member" toggle plus the same burst-size/wait-time fields as the group setting; unchecked (the default), the member follows the group's pacing.
  - The Rate Limiting screen's pacing section is now labeled **Group Default Send Pacing** with an explanatory note that members can override it individually.
  - `members` table gained `rate_limit_custom`, `rate_burst_min`, `rate_burst_max`, `rate_min_wait`, `rate_max_wait`, `rate_initial_delay` columns (`DbHelper` bumped to DB version 2, migrated via non-destructive `ALTER TABLE` — existing members, message history, and queued sends are preserved on upgrade, unlike the previous drop-and-recreate `onUpgrade`).
  - Added `util/RateLimitConfig`, resolving a member's effective pacing (their override if set, else the group default from `Prefs`).
  - `SmsSendService` no longer drains the outbox as one global burst/wait loop: it now runs one independent burst/wait cadence per recipient (concurrently, one thread per member with pending mail), each using that member's effective pacing. `SendQueueStatus` now tracks a schedule per member and the dashboard's "next burst" countdown shows whichever member's burst is coming up soonest.

## 1.12 - In-App License Viewer

- Added `app/src/main/assets/license.html`, an HTML transcription of `LICENSE.md`.
- Added `LicenseActivity`, a `WebView`-backed screen (`activity_license.xml`, WebView as root since it manages its own scrolling) that loads the bundled license asset via `file:///android_asset/license.html` — no network access or new permissions needed.
- Added a **License** button at the bottom of the Rate Limiting screen that opens `LicenseActivity`.
- Added a "You have read and accept the terms of the license." checkbox to the first-run consent screen, with a **License** link next to it that also opens `LicenseActivity`. **I Agree** is now disabled until that checkbox is checked, in addition to the existing disclosure acceptance.

## 1.11 - Disband Resets Group Name

- Disband Group now also resets the group name back to the default "jRelay" (`Prefs.resetGroupName`), instead of leaving the previous group's name behind after all its members and history are erased.

## 1.10 - #list Header, Dashboard Button Label

- `#list` now replies with the group name, a blank line, then the member list, instead of the bare list.
- Renamed the dashboard's "Rate Limiting" button label to "Configure".

## 1.9 - Membership Search, Clear History, Dashboard Polish

- Added an instant search bar to the Membership screen: filters live as you type by nickname (substring, case-insensitive), by phone number in any format (digit-only comparison, so formatting doesn't matter), or by typing "admin" to show only admins. Matching text is highlighted (`SpannableString` + `BackgroundColorSpan`) in both the nickname and the phone number.
- Each member row now shows the phone number in small text underneath the nickname (`row_member.xml` restructured into a nickname+phone column beside the admin/muted badge).
- Added thin 1dp separators between rows in both the Membership list and the dashboard's Recent Activity feed (`util/UiUtil.createDivider`).
- Tapping a Recent Activity entry on the dashboard now opens that member's detail screen.
- Added a small "Group Name" caption underneath the group name on the dashboard.
- Added a **Clear History** button above Disband Group on the Rate Limiting screen: a Continue/Cancel confirmation that erases only the message history (`MessageRepository.deleteAll`) — members, admins, mute state, and settings are untouched, and nobody is notified. The button label shows a rough size estimate in parentheses (`MessageRepository.estimateStorageBytes`).

## 1.8 - Disband Group, Membership CSV Import/Export

- Added a red **Disband Group** button at the bottom of the Rate Limiting screen. Confirming requires typing back a random 4-digit PIN shown in the warning dialog (freshly generated each time); a wrong PIN or Cancel does nothing. On correct confirmation, `DbHelper.wipeAllData()` permanently deletes all members, message history, and any queued outbound messages, with no notification sent to anyone, and returns to the dashboard.
- Added a **Menu** button at the top-left of the Membership screen (same line as the title) opening a dropdown with **Export to CSV** and **Import from CSV**:
  - Export writes every active member as `phone,nickname` rows via the system "save file" picker (`ACTION_CREATE_DOCUMENT`) — no storage permission needed.
  - Import reads a chosen CSV via the system file picker (`ACTION_OPEN_DOCUMENT`) and adds a member per valid row through the existing `CommandProcessor.addMember` flow (same welcome/broadcast messages as `#add`), skipping header rows, blanks, invalid numbers, empty nicknames, and existing active members, then reports an imported/skipped summary.
  - Added `util/CsvUtil` (minimal RFC 4180-style field escaping/parsing) with unit tests (`CsvUtilTest`).

## 1.7 - Flexible #add Argument Order

- Fixed `#add` rejecting the number with `"Invalid phone number format."` when the nickname came first (e.g. `#add User 234-567-8910`) — only `#add <number> <nickname>` was accepted. Added `PhoneNumberUtils.splitTrailingNumberAndRest`, tried as a fallback whenever the leading-number parse fails, so `#add <nickname> <number>` now works too. Covered by new tests in `PhoneNumberUtilsTest`.

## 1.6 - Fixed Hyphenated Number Entry on Add Member

- `PhoneNumberUtils` already normalized `234-567-8910` and `1-234-567-8910` correctly (confirmed with new `PhoneNumberUtilsTest` unit tests covering all six documented US formats) — the actual bug was the Add Member screen's phone field using `android:inputType="phone"`, which attaches Android's `DialerKeyListener` and silently filters out `-`, `(`, `)`, and spaces as they're typed. Changed it to `textNoSuggestions` so every documented number format can actually be entered there. The `#add` SMS command and the Member Detail "Edit Phone Number" dialog were unaffected (no character restriction on those inputs).

## 1.5 - Group Renaming, Role-Aware Command List

- Added the `#topic <new name>` command (admin-only): changes the group name and notifies every other active member (`"<admin nickname> has changed the group name to <new name>."`).
- Renaming the group from the dashboard's Edit button now notifies every active member the same way, attributed to "An Admin" (`"An Admin has changed the group name to <new name>."`), via the same new `CommandProcessor.changeGroupName` used by `#topic`. Previously it silently updated `SharedPreferences` with no notification at all.
- The dashboard's group name now refreshes live (every second, alongside the queue status tick) instead of only on `onResume`, so a rename from any source shows up immediately while the dashboard is open.
- `#commands` replies now depend on the requester's admin status: `#add`, `#remove`, and `#topic` are only listed for admins. Non-admins get the same reply as before minus those three lines.

## 1.4 - Nickname Changes

- Added the `#name <new nickname>` command: any member can rename themselves by text. They get a confirmation reply (`"Your name has been changed to <new nickname>."`), and every other active member is notified (`"<old nickname> changed their name to <new nickname>."`).
- Fixed a gap where changing a member's nickname from the Member Detail screen silently updated the database with no notification at all. It now notifies the rest of the group (`"An Admin has changed <old nickname>'s name to <new nickname>."`), via a new `CommandProcessor.renameMemberFromApp`, matching how app-initiated add/remove already behave.

## 1.3 - Send Queue Visibility, Burst Randomization, Initial Delay, Group Broadcast

- Dashboard now shows live queue depth ("Messages in Queue") and a live countdown to the next burst ("Next burst in Xs/Xm Ys (N messages)"), updated every second while the screen is visible. Backed by a new in-memory `SendQueueStatus` holder that `SmsSendService` publishes to as it schedules each wait.
- Rate Limiting screen's single "messages per burst" field is now a random range: **Minimum** and **Maximum** messages per burst (`Prefs.burstMin`/`burstMax`), matching the existing min/max wait-time randomization. `SmsSendService` picks a random burst size in that range before each burst.
- Added an **initial delay** checkbox (checked by default) on Rate Limiting: when enabled, jRelay waits a random duration (drawn from the same min/max wait-between-bursts range) before sending the very first burst of a new send cycle, instead of relaying immediately. Unchecked sends right away.
- Every EditText on the Rate Limiting screen now has a small bold header label above it (in addition to its hint) so the field's purpose stays visible once text is entered.
- Added **Send to Group**: a dashboard button opens a dedicated `SendGroupMessageActivity` where an admin composes a message that's sent (as `[Admin]: ...`) to every active member regardless of mute state, via `CommandProcessor.broadcastToGroup`.
- Added `OutboxRepository.countUnsent()`/`countPending()` to back the new queue-depth display.

## 1.2 - Documentation

- Added `README.md` documenting all features (dashboard, membership, member detail, add member, rate limiting), the full command list with exact syntax and message templates, mute/removal semantics, permissions, and build instructions.

## 1.1 - Bug Fix

- Fixed a crash when adding a member whose phone number belonged to a previously removed (soft-deleted) member. `phone_e164` is UNIQUE in the `members` table, and soft-deleted rows keep their number, so re-adding it via `#add` or the Add Member screen hit `insertOrThrow` and threw an uncaught `SQLiteConstraintException`. `CommandProcessor.addMember` now reactivates the existing row (`MemberRepository.reactivate`) instead of inserting a duplicate when the number belongs to an inactive member.

## 1.0 - Initial Build

- Stripped all androidx/Jetpack dependencies from the generated template (AppCompatActivity, ConstraintLayout, Material Components, activity-ktx) in favor of vanilla `android.app.Activity` and framework widgets/themes, per project instructions.
- Added SQLite schema (`DbHelper`) with `members`, `message_log`, and `outbox` tables.
- Implemented phone number normalization (`PhoneNumberUtils`) supporting common US formats (+1XXXXXXXXXX, +1 (XXX) XXX-XXXX, (XXX) XXX-XXXX, XXX-XXX-XXXX, 1-XXX-XXX-XXXX, 10-digit, etc.), including numbers with internal spaces when parsing `#add <number> <nickname>`.
- Implemented the SMS relay pipeline: `SmsReceiver` (incoming SMS) -> `CommandProcessor` (parsing/business logic) -> `outbox` table -> `SmsSendService` (rate-limited/staggered sending in configurable bursts with randomized wait between bursts).
- Implemented all member commands: `#commands`, `#mute`, `#unmute`, `#stop`, `#list`, `#admin <message>` (admin-only relay + host notification), `#add <number> <nickname>` and `#remove <nickname|number>` (admin-only, with an "Only admins can use this command." reply for non-admins).
- Implemented all add/remove/mute/stop message templates and broadcasts to the rest of the group.
- Implemented soft-delete for removed/`#stop`'d members so message history and stats are preserved, while excluding them from relay, `#list`, and the membership screen.
- Muting pauses both directions: a muted member neither receives relayed/admin broadcasts nor has their own plain-text messages relayed, while slash-commands still work.
- Added first-run `ConsentActivity`: SMS/data-rate and legal-liability disclaimer that must be accepted before use (re-shown every launch until accepted), followed by runtime permission requests (`SEND_SMS`, `RECEIVE_SMS`, `POST_NOTIFICATIONS`).
- Added `MainActivity` dashboard: group name + rename, member/admin/muted counts, today/total message counts, scrollable recent activity feed, Add Member shortcut, navigation to Membership and Rate Limiting screens.
- Added `MembershipActivity`: scrollable list of active members with an admin badge, tap-through to member detail.
- Added `MemberDetailActivity`: per-member stats (messages sent/received, member since, last activity, 7-day activity level) and recent activity; actions to toggle admin/mute, remove, send a direct message, edit nickname/number, export contact, call, and text via the default SMS app.
- Added `AddMemberActivity` for adding members from the UI (recorded as "An Admin" in broadcasts/templates).
- Added `RateLimitActivity`: configurable burst size and randomized min/max wait between bursts for jRelay's own send pacing, plus a section to view/edit Android's built-in outgoing-SMS throttle (`sms_outgoing_check_max_count` / `sms_outgoing_check_interval_ms`) when `WRITE_SECURE_SETTINGS` is granted, or the exact `adb shell pm grant` command to grant it when it isn't.
- Added a notification channel/alert on the host device whenever a `#admin` message is relayed.
- Added DPAD-focus highlighting on all interactive rows/buttons and made every screen a `ScrollView`-rooted vertical `LinearLayout` per UI guidelines.
- Removed the default instrumented test (`androidTest`) along with its androidx.test dependencies, keeping only plain JUnit for unit tests.
