package com.sh7411usa.jrelay.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;
import android.util.Log;

import com.sh7411usa.jrelay.db.ProcessedSmsRepository;
import com.sh7411usa.jrelay.util.Prefs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Relays inbound SMS that the live {@link SmsReceiver} never saw, by reading them back out of
 * {@code content://sms/inbox}.
 * <p>
 * Inbound SMS normally reach jRelay only through the SMS_RECEIVED broadcast. If the app is not
 * running at that moment (a reboot before the first unlock -- the app is not direct-boot aware --
 * a process kill, or an OEM battery kill), that text is never relayed. The stock messaging app
 * still stores every SMS in the provider, so this scan finds them afterwards. Same design as
 * {@code MmsIngestService}: a watermark, a seen-set, and mark-before-process. jRelay is not the
 * default SMS app and never writes to the provider.
 *
 * <h3>Why a message can never be relayed twice</h3>
 * Both paths claim a message in {@link ProcessedSmsRepository} before calling
 * {@link CommandProcessor#handleIncoming}, and only process it if the claim is new (INSERT OR
 * IGNORE, in one transaction). The claim key is {@link #dedupeKey}: normalized sender, the
 * SERVICE-CENTER timestamp, and a hash of the whole body. Both inputs describe the same PDUs, so
 * the live path and the provider path compute the same key for the same text:
 * <ul>
 *   <li>Live: {@code SmsMessage.getTimestampMillis()} of the first part, and every part's
 *       {@code getMessageBody()} concatenated in order ({@link #concatBodies}).
 *   <li>Provider: the {@code date_sent} column and the stored {@code body}. The default SMS app
 *       writes one row per (multipart) message, with {@code date_sent} = the first part's
 *       {@code getTimestampMillis()} and the parts' bodies concatenated in order (AOSP Messaging
 *       and its Google Messages descendant do exactly this).
 * </ul>
 * That equality is not something jRelay controls -- a given OEM app could store the body or the
 * timestamp slightly differently. Every such doubt is resolved toward MISSING a message, never
 * duplicating it:
 * <ul>
 *   <li>The body is hashed after removing all whitespace and control characters, so e.g. the AOSP
 *       app's form-feed-to-newline rewrite, CRLF, or trimmed trailing spaces cannot change the key.
 *   <li>Two guard keys are recorded per claim, tagged with the path that wrote them: sender +
 *       timestamp, and sender + body hash + a 10-minute receive-time bucket. A claim is refused if
 *       the OTHER path already recorded either guard. So if the provider body differs from the live
 *       body, the timestamp guard still matches; if the stored timestamp differs, the body guard
 *       still matches. Only a message whose body AND timestamp both differ could slip through.
 *       The cost: two different texts from one sender in the same second, or the identical text
 *       from one sender within ~10 minutes, where one came live and one only via catch-up, lose the
 *       second one. Guards are only checked against the other path, so between two live texts only
 *       an exact repeat (same sender, timestamp and body -- a re-delivered broadcast) is dropped.
 *   <li>A provider row with no {@code date_sent} ({@code <= 0}) is skipped, never relayed.
 *   <li>A 90-second settle window means catch-up never touches a text the live receiver may still
 *       be about to handle.
 * </ul>
 */
public final class SmsCatchUp {

    private static final String TAG = "SmsCatchUp";

    public static final String SOURCE_LIVE = "LIVE";
    public static final String SOURCE_CATCHUP = "CATCHUP";

    /** Rows younger than this (by receive time) are left for the live receiver and the next scan. */
    static final long SETTLE_MILLIS = 90_000L;
    /** A long-dead app does not replay more than this much chatter. */
    static final long MAX_LOOKBACK_MILLIS = 48L * 60 * 60 * 1000;
    /** Well past the lookback cap: nothing this old can be examined again, so its keys can go. */
    private static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** How often a scan prunes old keys; far shorter than RETENTION_MILLIS minus the lookback. */
    private static final long PRUNE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000;
    /** Last prune, process-local. Guarded by LOCK; 0 after process start, so the first scan prunes. */
    private static long lastPruneMillis;
    /** Width of the receive-time bucket in the sender+body guard key; neighbours are checked too. */
    static final long BODY_GUARD_BUCKET_MILLIS = 10L * 60 * 1000;

    private static final int TYPE_INBOX = 1;

    /** Serializes whole scans: two overlapping scans would otherwise race on the watermark. */
    private static final Object LOCK = new Object();

    private SmsCatchUp() {
    }

    /** Synchronous. Must be called off the main thread. Safe to call concurrently: the whole scan is serialized. */
    public static void run(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            try {
                scan(app);
            } catch (Exception e) {
                // Never crash the caller (often a receiver or a bare thread). The watermark only
                // moves past rows actually examined, so the next trigger resumes where this stopped.
                Log.e(TAG, "SMS catch-up scan failed", e);
            }
        }
    }

    /** Posts run() to a background thread and returns immediately. Safe from an Activity. */
    public static void runAsync(Context context) {
        runAsync(context, null);
    }

    /**
     * Same as {@link #runAsync(Context)}, for a BroadcastReceiver: pass the result of
     * {@code goAsync()} and it is finished when the scan ends, keeping the process alive meanwhile.
     */
    public static void runAsync(Context context, BroadcastReceiver.PendingResult pendingResult) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                run(app);
            } finally {
                if (pendingResult != null) {
                    pendingResult.finish();
                }
            }
        }, "SmsCatchUp").start();
    }

    /**
     * Claims one inbound SMS for {@code source} (SOURCE_LIVE or SOURCE_CATCHUP). True means the
     * caller owns it and must process it now; false means it was already handled and must be
     * dropped. See the class comment for why the two paths can never both get true.
     *
     * @param receivedAtMillis live: now; catch-up: the provider's {@code date} column. Only feeds
     *                         the coarse body-guard bucket.
     */
    public static boolean claim(Context context, String normalizedSender, long serviceCenterMillis,
                                String body, long receivedAtMillis, String source) {
        String other = SOURCE_LIVE.equals(source) ? SOURCE_CATCHUP : SOURCE_LIVE;
        String bodyHash = bodyHash(body);
        long bucket = receivedAtMillis / BODY_GUARD_BUCKET_MILLIS;
        String[] refuse = {
                timeGuardKey(other, normalizedSender, serviceCenterMillis),
                bodyGuardKey(other, normalizedSender, bodyHash, bucket - 1),
                bodyGuardKey(other, normalizedSender, bodyHash, bucket),
                bodyGuardKey(other, normalizedSender, bodyHash, bucket + 1),
        };
        String[] record = {
                timeGuardKey(source, normalizedSender, serviceCenterMillis),
                bodyGuardKey(source, normalizedSender, bodyHash, bucket),
        };
        return new ProcessedSmsRepository(context).claim(
                dedupeKey(normalizedSender, serviceCenterMillis, body), refuse, record, source);
    }

    private static void scan(Context context) {
        Prefs prefs = new Prefs(context);
        long now = System.currentTimeMillis();
        long since = prefs.getSmsCatchUpSinceMillis();
        if (since == 0) {
            // Never relay history that predates this feature: seed the watermark and start from
            // the next text instead of scanning backward.
            prefs.setSmsCatchUpSinceMillis(now);
            return;
        }
        long lower = Math.max(since, now - MAX_LOOKBACK_MILLIS);
        long upper = now - SETTLE_MILLIS;
        if (upper <= lower) {
            return;
        }

        // Retention is days past the lookback, so pruning once a day loses nothing.
        if (now - lastPruneMillis >= PRUNE_INTERVAL_MILLIS) {
            new ProcessedSmsRepository(context).pruneOlderThan(now - RETENTION_MILLIS);
            lastPruneMillis = now;
        }

        Cursor cursor;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                            Telephony.Sms.DATE, Telephony.Sms.DATE_SENT},
                    Telephony.Sms.TYPE + " = ? AND " + Telephony.Sms.DATE + " > ? AND "
                            + Telephony.Sms.DATE + " <= ?",
                    new String[]{String.valueOf(TYPE_INBOX), String.valueOf(lower), String.valueOf(upper)},
                    Telephony.Sms.DATE + " ASC");
        } catch (SecurityException e) {
            Log.e(TAG, "READ_SMS denied; cannot catch up on missed SMS", e);
            return;
        }
        if (cursor == null) {
            // Some OEM builds return null instead of throwing; nothing to do this time.
            Log.w(TAG, "content://sms/inbox query returned null cursor");
            return;
        }

        CommandProcessor processor = new CommandProcessor(context);
        long newestExamined = since;
        boolean processedAny = false;
        try {
            int addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
            int bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY);
            int dateCol = cursor.getColumnIndex(Telephony.Sms.DATE);
            int dateSentCol = cursor.getColumnIndex(Telephony.Sms.DATE_SENT);
            if (addressCol < 0 || bodyCol < 0 || dateCol < 0 || dateSentCol < 0) {
                Log.e(TAG, "content://sms/inbox cursor missing expected columns");
                return;
            }
            while (cursor.moveToNext()) {
                long date = cursor.getLong(dateCol);
                long dateSent = cursor.getLong(dateSentCol);
                String sender = PhoneNumberUtils.normalize(cursor.getString(addressCol));
                String body = cursor.getString(bodyCol);
                if (sender != null && body != null && !body.trim().isEmpty()) {
                    if (dateSent <= 0) {
                        // No service-center timestamp to match the live key against. Skipping can
                        // only miss this text; relaying could duplicate it.
                        Log.w(TAG, "Skipping inbox SMS with no date_sent, received " + date);
                    } else if (claim(context, sender, dateSent, body, date, SOURCE_CATCHUP)) {
                        // The receive time, not now: this text may be hours old, and the echo
                        // check must compare it with what the relay sent back then.
                        processor.handleIncoming(sender, body, date);
                        processedAny = true;
                        Log.i(TAG, "Caught up missed SMS from " + PhoneNumberUtils.mask(sender) + " received " + date);
                    }
                }
                // Advanced per row, after the row is fully handled, and saved in finally: a failure
                // mid-scan keeps every row already claimed behind the watermark and nothing else.
                newestExamined = Math.max(newestExamined, date);
            }
        } finally {
            cursor.close();
            if (newestExamined > since) {
                prefs.setSmsCatchUpSinceMillis(newestExamined);
            }
            if (processedAny) {
                SmsSendService.start(context);
            }
        }
    }

    // ---- Pure key functions (JVM-tested in SmsCatchUpKeyTest). ----

    /**
     * Joins a multipart message's part bodies in order, skipping null parts -- exactly how
     * {@link SmsReceiver} has always built the body it hands to handleIncoming.
     */
    public static String concatBodies(String[] partBodies) {
        StringBuilder sb = new StringBuilder();
        for (String part : partBodies) {
            if (part != null) {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** The one dedupe key both paths compute: sender | service-center millis | body hash. */
    public static String dedupeKey(String normalizedSender, long serviceCenterMillis, String body) {
        return normalizedSender + "|" + serviceCenterMillis + "|" + bodyHash(body);
    }

    static String timeGuardKey(String source, String normalizedSender, long serviceCenterMillis) {
        return "T|" + source + "|" + normalizedSender + "|" + serviceCenterMillis;
    }

    static String bodyGuardKey(String source, String normalizedSender, String bodyHash, long bucket) {
        return "B|" + source + "|" + normalizedSender + "|" + bodyHash + "|" + bucket;
    }

    /**
     * SHA-256 (hex) of the body with every whitespace and control character removed, so a stored
     * body that differs from the live one only in line endings, form feeds or trailing spaces still
     * hashes the same. Null hashes like the empty string.
     */
    static String bodyHash(String body) {
        StringBuilder canonical = new StringBuilder();
        if (body != null) {
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && !Character.isISOControl(c)) {
                    canonical.append(c);
                }
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Java and Android platform.
            throw new IllegalStateException(e);
        }
    }
}
