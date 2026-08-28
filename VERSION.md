# Version History

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
