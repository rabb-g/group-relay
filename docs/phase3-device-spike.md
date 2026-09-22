# Phase 3 Device Spike — Group MMS Feasibility

Read `jrelay-handoff.md`'s Phase 3 section before running this. This procedure exists because Phase 3 is gated on it: **do not build any of Phase 3's real classes** (`MmsIngestService`, the vendored `com.sh7411usa.jrelay.mms.pdu` package as a permanent part of the app, `MmsFileProvider` as shipped, subgroup assignment, routing) **until this spike passes.** If it fails, stop and report — do not fall back to requesting the default-SMS-app role. That fallback is explicitly ruled out in the handoff's ground rules and Phase 3's "Explicitly out of scope" section.

---

## 1. What we are proving and why it matters

Phase 3's entire value proposition — 12 sends instead of 100 for a 100-member group — depends on one text-only MMS reaching an entire sub-group as a single carrier-level delivery, and on jRelay being able to read replies back out of Android's MMS content provider without ever becoming the default SMS app (a role it deliberately avoids so the stock Messages app can keep doing MMS download/storage duty). Both of those are assumptions, not verified facts. Two things could each independently sink the phase:

- **H1 — Outbound.** A multi-recipient `SmsManager.sendMultimediaMessage()` call from a **non-default** SMS app succeeds, and is delivered to recipients as **one group thread**, not as N individual messages — **including on a flip phone.** Flip phones are not an edge case here; they're the reason the deployment exists (see handoff §1: "Many members are on flip phones").
- **H2 — Inbound.** An inbound group MMS's sender address and text body can be read back out of `content://mms` by a non-default app, using the row shapes the handoff specifies (inbox rows `msg_box = 1`, `m_type = 132`; sender is the `addr` row with `type = 137`).

If either is false, Phase 3 as designed doesn't work, and per the handoff the answer is to stop and report — not to quietly fall back to requesting the default-SMS-app role.

---

## 2. Prerequisites

### Devices
| Role | Minimum | Notes |
|---|---|---|
| Host | 1 | The device that will eventually run jRelay. Has `adb` access from the Windows dev machine (USB or `adb connect`). |
| Recipient (smartphone) | 1 | Any modern Android or iPhone. Confirms the "normal" case. |
| Recipient (flip phone) | 1 | Any basic/feature phone the group actually uses that receives MMS today. This is the device that matters most. |
| Recipient (extra, optional) | 1 | A third recipient (smartphone or flip) makes it easier to tell "this is genuinely being treated as a group by the carrier" apart from "this just happens to work for two people." Recommended if available, not required. |

All devices need normal cellular service (not airplane mode, not Wi-Fi-only) — MMS goes over the carrier's MMSC, not straight IP, so Wi-Fi-only connectivity on the host can produce a misleading failure that has nothing to do with H1/H2.

### Host device state
- `adb` working (`adb devices` shows the host).
- Host is **not** the default SMS app — verify this explicitly (Settings → Apps → Default apps → SMS app). If the host somehow is default, the spike isn't representative of the real deployment; change it back before testing.
- `SEND_SMS` permission granted to whatever harness you build for H1 (see §3). No other jRelay permissions are needed to run this spike — `READ_SMS`/`RECEIVE_MMS` aren't required to run `adb shell content query` for H2, since that queries the provider directly as the `shell` user, not through the app.

### Stock messaging app (on every phone that can have one)
- **Stays the default SMS/MMS app.** Do not switch it off default anywhere, on any device, for this test.
- **MMS auto-download: ON.** If a recipient's messaging app is set to manually retrieve MMS, an inbound MMS sits as a notification-only placeholder until the user opens it — which would make an inbound test message look like it "never fully arrived" when it's actually just waiting on user action. Confirm this setting on the host too, since H2's provider rows depend on the message body actually being downloaded, not just announced.
- **RCS / chat features: OFF**, on every device that has them (this mainly means Google Messages' "Chat features" toggle, or Samsung Messages' RCS toggle). Turn it off, don't just leave it default. Reason: RCS group messaging is a different transport (IP-based, routed through the carrier's or Google's RCS backend) with its own idea of what a "group" is, and some clients will silently upgrade an ordinary SMS/MMS thread to RCS once they detect a group conversation. `SmsManager.sendMultimediaMessage()` itself always sends classic MMS regardless of RCS settings, but a recipient's client reinterpreting the resulting thread through RCS would change what you're observing and invalidate the read on "does a real MMS group thread work here." Disabling RCS everywhere keeps the test on the plain carrier MMS path the handoff is actually asking about.

---

## 3. Procedure for H1 (outbound group MMS)

A real send needs a composed text-only `SendReq` PDU exposed through a content provider — `SmsManager.sendMultimediaMessage()` takes a `content://` URI, not raw bytes or a recipient list. There is no way around building *something* first. Keep that something as small as possible and throwaway:

- Do **not** build this inside jRelay's real package structure as permanent code, and do **not** build the full vendored PDU set the handoff describes for the shipped app (`PduComposer`, `SendReq`, `PduBody`, `PduPart`, `PduHeaders`, `EncodedStringValue`, `ContentType`, `CharacterSets`, plus transitive dependencies — all AOSP-derived, Apache-2.0).
- For the spike you need a strict subset of that same list — `PduComposer`, `SendReq`, `PduHeaders`, `PduBody`, `PduPart`, `EncodedStringValue`, `ContentType`, `CharacterSets` — vendored into a scratch project or a clearly-marked, disposable branch. It's the same code either way (same license obligations apply even to a throwaway harness), just not wired into `OutboxRepository`, subgroup logic, or anything else that's part of the real phase.
- The provider side is equally minimal: one `ContentProvider` subclass implementing `openFile()` over a file in app-private storage, `android:exported="false"`, `android:grantUriPermissions="true"` — the same shape as the handoff's `MmsFileProvider`, but again, a throwaway class is fine for this test.
- One `Activity` with one button that runs the send and logs the result is enough. No UI polish.

### Steps
1. Confirm the phone numbers for every recipient device, in the exact format the carrier expects (E.164, e.g. `+15551234567`, is safest).
2. Build the throwaway harness: request `SEND_SMS` only, vendor the minimal PDU subset above, add the throwaway file provider.
3. Compose one `SendReq`:
   - `MessageType = PduHeaders.MESSAGE_TYPE_SEND_REQ`
   - `To` = every recipient's number, added via `addTo(new EncodedStringValue(number))` for each — this is what makes it a multi-recipient send rather than N single sends.
   - Leave `From` unset — the OS/carrier fills it in from the SIM.
   - `Body` = one `PduPart` with `ContentType = "text/plain"`, containing a short, identifiable message (include a timestamp so you can tell test runs apart, e.g. `jRelay MMS spike 2026-09-21T14:03Z`).
4. Serialize with `PduComposer.make()`, write the bytes to a file under the app's private storage (e.g. `getCacheDir()`), and get the `content://` URI for that file from your throwaway provider.
5. Register a `PendingIntent`/`BroadcastReceiver` for the `sentIntent` parameter of `sendMultimediaMessage()` and log the result code you get back (`Activity.RESULT_OK` vs. anything else) — don't rely on "the call didn't throw."
6. Call `SmsManager.getDefault().sendMultimediaMessage(context, pduUri, /* locationUrl */ null, /* configOverrides */ null, sentIntent)`.
7. Watch logcat (see §7 for filters) for the `sentIntent` result and for any MMS-subsystem errors during the send.
8. On **each** recipient phone, record exactly what arrived: one message or several, whether it reads as a single conversation with all recipients visible as participants (on phones that have that UI concept) or as a separate 1:1 thread, and whether the full text body came through intact.
9. For the clearest signal on the flip phone specifically, also do the reverse: have the smartphone recipient **reply from within the thread** the group MMS created (not start a new 1:1 text) — this both double-checks H1's "one group thread" claim from the recipient side and sets up the most realistic input for the H2 test in §4, since in production the inbound message jRelay needs to read is exactly this kind of in-thread reply from a member.

---

## 4. Procedure for H2 (inbound group MMS readable from `content://mms`)

This step needs no app code — `adb shell content query` reads the provider directly. Use the reply generated at the end of §3 step 9 (a real inbound group MMS arriving on the host) as the test input.

Note for the Windows dev machine: run these from an interactive `adb shell` session (`adb shell`, then paste the command below without the leading `adb shell`) rather than as one-liners from PowerShell/cmd — nested double quotes in `--where` clauses are easy to mangle through PowerShell's own quoting. If a query comes back empty or errors on a locked-down OEM build, it may need `--user 0` appended, or root — note whichever applies in the results table (§6).

### Find the inbox row
```
content query --uri content://mms --projection _id:thread_id:date:msg_box:m_type:sub:m_size --where "msg_box=1 AND m_type=132" --sort "date DESC"
```
- `msg_box = 1` → inbox (received, not sent/draft).
- `m_type = 132` → `m-retrieve-conf`, i.e. a fully retrieved MMS, not just a notification placeholder (`m_type = 130`, `m-notification-ind`) waiting on the user to download it. If every row you see is `130`, that's the "auto-download is off somewhere" failure mode from §2 — go fix that setting before concluding H2 fails.
- Take the `_id` of the row matching your test message's timestamp.

### Read the sender
```
content query --uri content://mms/<id>/addr --projection address:type:charset
```
or filtered directly to the sender:
```
content query --uri content://mms/<id>/addr --where "type=137" --projection address:type
```
`type = 137` is `PduHeaders.FROM`. Confirm `address` matches the actual sending phone's number.

### Read the text body
```
content query --uri content://mms/part --where "mid=<id> AND ct='text/plain'" --projection _id:mid:ct:text
```
- If the `text` column has your message content inline, that's a full pass — no code needed to read it.
- If `text` is empty/null, the body may be stored as a file rather than inline (carrier-dependent). That's not automatically a failure of H2 — it just means the real `MmsIngestService` will need to `openInputStream()` on the part's own URI (`content://mms/part/<part_id>`) rather than reading a column, which it would do anyway. Note which case you hit; it changes an implementation detail, not the pass/fail call.

---

## 5. Pass/fail criteria

### H1
| Result | Definition |
|---|---|
| **PASS** | `sendMultimediaMessage` reports `RESULT_OK`; every recipient — smartphone **and** flip phone — receives exactly one message with the full, correct text body; on phones with a "group thread" UI concept, it shows as one conversation with all recipients listed as participants, not as separate 1:1 threads. |
| **PARTIAL PASS** | Example: smartphone recipients get a proper single group thread, but the flip phone either doesn't receive anything, receives garbled/truncated text, or the carrier fans the send out into multiple separate messages to reach it. |
| **FAIL** | The send errors or throws; no recipient receives anything; or the message reaches recipients as though it had been sent as N separate individual messages even on capable smartphones (i.e., no group semantics at all). |

**A PARTIAL PASS is a FAIL of the phase's core premise.** Flip-phone members are the reason this app exists (handoff §1); a design that only works for smartphone recipients doesn't meet the deployment's actual requirement, even if it looks like a win on paper.

### H2
| Result | Definition |
|---|---|
| **PASS** | The inbound row appears in `content://mms` as `msg_box=1, m_type=132` without requiring the user to manually open the stock app; the `addr` sub-query returns a `type=137` row whose `address` matches the real sender; the message text is recoverable, either inline in the `part.text` column or via the part's own content URI. |
| **FAIL** | The row never reaches `m_type=132` (stuck at the notification-only `130` even with auto-download confirmed on); the `type=137` sender row is missing or wrong; or the text is unrecoverable by any means. |

There is no meaningful "partial" for H2 — either the data needed to route a reply (who sent it, what it said) is recoverable from the provider, or it isn't.

---

## 6. What to record

Carrier and OEM behavior around MMS varies enough that this isn't safe to generalize from one phone. Fill in one row per device involved, for every test run:

| Device model | OS version | Carrier | Role (host / smartphone recipient / flip recipient) | H1 result | H2 result | Notes |
|---|---|---|---|---|---|---|
| | | | | | | |

Run it more than once if the first result is surprising in either direction — a single run on one carrier is not enough to trust for a 100-member deployment spanning whatever carriers those members happen to be on.

---

## 7. If it fails

Per the handoff: **stop and report. Do not fall back to the default-SMS-app role.** The job at that point is to make the failure diagnosable later, not to work around it. Capture:

- The exact `sentIntent` result code from §3 step 5, logged, not inferred.
- Logcat around the send and receive windows. Start broad, since tag names vary by OEM/AOSP version:
  ```
  adb logcat -c
  adb logcat | grep -iE "mms|wap_push|smsmanager|telephony"
  ```
  and a tighter tag-filtered pass if the broad grep is too noisy:
  ```
  adb logcat -s SmsManager:V MmsService:V Mms:V Telephony:V ImsService:V *:S
  ```
- `adb shell dumpsys activity broadcasts | grep -i wap_push` around the time of an inbound test message — shows whether `WAP_PUSH_RECEIVED` was dispatched at all and whether it was blocked for a permission reason (relevant to §8 too).
- What each recipient's phone actually displayed — a screenshot or a precise written description of thread structure, number of separate notifications/messages received, and any carrier error text shown.
- The raw composed PDU bytes (the file written in §3 step 4), in case the failure is in PDU construction rather than in carrier delivery.
- Whether mobile data (not just Wi-Fi) was active on the host during the send — MMS requires a route to the carrier's MMSC; Wi-Fi-only connectivity produces a failure that looks like an MMS problem but isn't one.
- The filled-in results table from §6 for every device tried.

---

## 8. Open question to settle during the spike: does `WAP_PUSH_RECEIVED` fire without `RECEIVE_WAP_PUSH`?

The handoff's inbound design has two mechanisms: a manifest receiver for `WAP_PUSH_RECEIVED` that wakes `MmsIngestService` as fast as possible, and a `ContentObserver` plus catch-up scan that works regardless. The receiver is normally permission-gated behind `RECEIVE_WAP_PUSH`, and Phase 3 deliberately does not request that permission (see "Explicitly out of scope"). Whether the broadcast still reaches an app that hasn't asked for the gated permission is unknown until tested — and **this is not fatal either way**, since the `ContentObserver` + catch-up scan is the real mechanism the design already leans on; the receiver is only meant to shave latency. What matters is knowing which path is actually carrying traffic so nothing downstream is built assuming instant wake-up if that turns out to be unavailable.

### How to test it
1. In the same throwaway harness (or a temporary addition to it — not to jRelay's shipped manifest), declare a receiver:
   ```xml
   <receiver android:name=".WapPushSpikeReceiver" android:exported="true">
       <intent-filter>
           <action android:name="android.provider.Telephony.WAP_PUSH_RECEIVED" />
           <data android:mimeType="application/vnd.wap.mms-message" />
       </intent-filter>
   </receiver>
   ```
   Deliberately **do not** add `android:permission="android.permission.RECEIVE_WAP_PUSH"` to the receiver, and **do not** add `<uses-permission android:name="android.permission.RECEIVE_WAP_PUSH"/>` to the manifest. The whole point is testing delivery to a receiver that hasn't asked for the gated permission.
2. Log a timestamped line in `onReceive()`.
3. Trigger an inbound group MMS (reuse §3 step 9's reply, or send a fresh one).
4. Check logcat for that log line.
5. Cross-reference with `adb shell dumpsys activity broadcasts | grep -i wap_push` from the same window — this can distinguish "broadcast never sent" from "broadcast sent but blocked for lack of permission" (look for a permission-denial trace) if the receiver never fires.

### Recording the result
- **Fires:** note it as a usable latency optimization; no change to the design, since it was always meant to be optional.
- **Doesn't fire:** confirms the `ContentObserver` + catch-up scan is carrying all inbound traffic, which is already accounted for in the handoff's design. No action needed — just don't build anything downstream that assumes the receiver path is live.

Either outcome is informational only. It does not gate the phase the way H1/H2 do.
