package com.sh7411usa.jrelay.sms.mms;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Sends ONE text-only group MMS (one m-send-req PDU, one "To" header per recipient) instead of
 * one SMS per recipient. This is the piece that turns ~99 individual texts into ~11 group sends
 * for a ~9-recipient sub-group cap.
 *
 * <p>Callers resolve the recipient list themselves (who belongs to which sub-group); this class
 * never queries the database. It also never writes to the database - {@code rowId} and
 * {@code token} are supplied by the caller (the outbox row for this group send, and the attempt
 * token already stamped into that row's {@code handed_off_at}, exactly like
 * {@code SmsSendService.sendOne} does before calling {@code SentReceiver.create}) and are only
 * used here to build the sentIntent's {@code data} Uri so the result can find its way back to the
 * right row.
 *
 * <h3>Why the result is handled by a manifest receiver, not a dynamic one</h3>
 * An earlier version of this class registered its own {@code BroadcastReceiver} dynamically per
 * send. That receiver dies with the process, but the {@code PendingIntent} handed to
 * {@code SmsManager} survives - so a kill between hand-off and result (routine on this
 * long-running, frequently-Doze'd/battery-managed relay) would lose the result permanently, leave
 * the row stuck at SENDING, and let {@code resetOrphanedSending} + {@code takeBurst} resend a
 * message that may have already reached every recipient. Results now go to the manifest-declared
 * {@link MmsSentReceiver} instead, for the same reason {@code SentReceiver} is manifest-declared:
 * the broadcast must be able to resolve the row (or wake the process) no matter what happened to
 * this call's stack in between.
 *
 * <p>The only case {@link Callback} still covers is a synchronous throw from
 * {@code sendMultimediaMessage}: the message never reached the radio, so no broadcast will ever
 * arrive and {@link MmsSentReceiver} will never run for this attempt. Everything else -
 * success, carrier/platform failure, retry - is reported and decided asynchronously by
 * {@link MmsSentReceiver} and {@code SentReceiver#handleFailure}, exactly as SMS results are.
 */
public final class MmsSender {

    private static final String TAG = "MmsSender";
    static final String ACTION_MMS_SENT = "com.sh7411usa.jrelay.MMS_SENT";

    /**
     * Mirrors {@code SentReceiver#RESULT_NOT_SENT}: no delivery result is possible because
     * {@code SmsManager} threw synchronously, so the message never reached the radio and no
     * {@link PendingIntent} for it will ever fire.
     */
    public static final int RESULT_NOT_SENT = -1000;

    /** Reported to {@link Callback#onResult} when the recipient list is empty -- see {@link #send}. */
    public static final int RESULT_NO_RECIPIENTS = -2000;

    public interface Callback {
        /**
         * Invoked ONLY for the cases {@link MmsSentReceiver} can never handle: an empty recipient
         * list, or a synchronous throw from {@code sendMultimediaMessage}. A successful hand-off
         * to the radio reports nothing here - the eventual result (success or platform failure)
         * arrives at {@link MmsSentReceiver} and is applied to the outbox row directly.
         *
         * @param resultCode {@link #RESULT_NO_RECIPIENTS} or {@link #RESULT_NOT_SENT}.
         */
        void onResult(int resultCode);
    }

    private MmsSender() {
    }

    /**
     * Sends one group MMS containing {@code textBody} to every number in {@code recipientsE164},
     * on behalf of outbox row {@code rowId} under attempt {@code token}.
     *
     * @param rowId          the outbox row this send belongs to (one row per sub-group send).
     * @param token          the attempt token the caller already stamped into that row (e.g. via
     *                       {@code OutboxRepository.markHandedOff}) before calling this method -
     *                       shared with {@link MmsSentReceiver} so a late or stale result can be
     *                       told apart from the current attempt, same guard {@code SentReceiver}
     *                       uses for SMS.
     * @param recipientsE164 recipients for this one send. Must be resolved by the caller -- this
     *                       class does not query the database. If empty, this is a bug upstream
     *                       (an empty sub-group should never have reached here); it is reported
     *                       via {@code callback}, not sent as a zero-recipient message.
     * @param textBody       message body. Must not be null/empty (enforced by {@link MmsPduWriter}).
     */
    public static void send(Context context, long rowId, long token, List<String> recipientsE164,
                             String textBody, Callback callback) {
        Context appContext = context.getApplicationContext();

        if (recipientsE164 == null || recipientsE164.isEmpty()) {
            Log.e(TAG, "send() called with no recipients for row " + rowId
                    + " -- refusing to call SmsManager; this is a caller bug");
            callback.onResult(RESULT_NO_RECIPIENTS);
            return;
        }

        String transactionId = "jrelay-mms-" + rowId + "-" + token;

        byte[] pdu = MmsPduWriter.buildSendReq(recipientsE164, textBody, transactionId);

        // No PDU dump: it carries every recipient's number and the message text.
        Log.i(TAG, "Sending group MMS for row " + rowId + " to " + recipientsE164.size()
                + " recipients, pdu=" + pdu.length + " bytes");

        Uri contentUri;
        try {
            contentUri = MmsFileProvider.writeToCache(appContext, transactionId + ".pdu", pdu);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write PDU to cache for row " + rowId + ", transactionId=" + transactionId, e);
            callback.onResult(RESULT_NOT_SENT);
            return;
        }

        PendingIntent sentIntent = createSentIntent(appContext, rowId, token);
        try {
            SmsManager.getDefault().sendMultimediaMessage(appContext, contentUri, null, null, sentIntent);
        } catch (RuntimeException e) {
            // Synchronous throw: the message never reached the radio, so sentIntent will never
            // fire and MmsSentReceiver will never run for this attempt - report it here instead,
            // same as SmsSendService.sendOne's catch block does for SMS.
            Log.e(TAG, "sendMultimediaMessage threw synchronously for row " + rowId
                    + ", transactionId=" + transactionId, e);
            callback.onResult(RESULT_NOT_SENT);
        }
    }

    /**
     * Builds the PendingIntent Android will fire with this send's result, explicitly targeting
     * {@link MmsSentReceiver} (so only this app can ever receive it, regardless of manifest
     * declaration) with a {@code data} Uri unique to {@code (rowId, token)}.
     *
     * <p>PendingIntent identity is (request code, action, data, type, component, categories) - NOT
     * extras - so a fixed request code is safe only because {@code data} already carries all the
     * uniqueness this send needs; see {@code SentReceiver.create}'s javadoc for the full hazard
     * this avoids ({@code FLAG_UPDATE_CURRENT} silently rewriting an unrelated outstanding
     * PendingIntent's extras when two intents collide on identity). {@code FLAG_IMMUTABLE} is
     * required on API 31+ (this targets 36) and is safe here since {@link MmsSentReceiver} only
     * needs {@code getResultCode()}, never the platform's merged {@code errorCode} extra.
     */
    private static PendingIntent createSentIntent(Context appContext, long rowId, long token) {
        Intent intent = new Intent(ACTION_MMS_SENT);
        intent.setClass(appContext, MmsSentReceiver.class);
        intent.setData(dataUriFor(rowId, token));

        return PendingIntent.getBroadcast(appContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Uri dataUriFor(long rowId, long token) {
        return Uri.parse(String.format(Locale.US, "jrelay://mms_sent/%d/%d", rowId, token));
    }

    /**
     * Inverse of {@link #dataUriFor}: pulls {@code {rowId, token}} back out of a
     * {@code jrelay://mms_sent/<rowId>/<token>} Uri. Package-private and pure so
     * {@link MmsSentReceiver} can use it without any Android dependency beyond {@link Uri} itself,
     * and so the encode/decode pair can't drift apart silently.
     *
     * @return {@code {rowId, token}}, or {@code null} if {@code data} doesn't match the expected
     *         shape (defensive only - every Uri this app ever hands to {@code SmsManager} for this
     *         action is minted by {@link #dataUriFor}).
     */
    static long[] parseSentData(Uri data) {
        if (data == null || !"jrelay".equals(data.getScheme()) || !"mms_sent".equals(data.getAuthority())) {
            return null;
        }
        List<String> segments = data.getPathSegments();
        if (segments.size() != 2) {
            return null;
        }
        try {
            return new long[]{Long.parseLong(segments.get(0)), Long.parseLong(segments.get(1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
