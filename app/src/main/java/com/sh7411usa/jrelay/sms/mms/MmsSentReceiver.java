package com.sh7411usa.jrelay.sms.mms;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.sms.SentReceiver;

/**
 * Receives the send result Android broadcasts back for a group-MMS {@link PendingIntent} handed
 * to {@code SmsManager.sendMultimediaMessage} by {@link MmsSender}.
 *
 * <p>Manifest-declared (not dynamically registered) for the same reason {@code SentReceiver} is:
 * a dynamic receiver dies with the process, but the PendingIntent it's tied to survives. This app
 * is a long-running unattended relay routinely killed by Doze/memory pressure/OEM battery
 * management, so "the process died between hand-off and result" is not a rare edge case - it's
 * business as usual. A group-MMS send is one outbox row ({@code subgroup_id} set); if this
 * receiver were dynamic, a kill in that window would strand the row at SENDING forever with no
 * broadcast to resolve it, {@code resetOrphanedSending} would later reclaim it as orphaned, and
 * {@code takeBurst} would resend it - duplicating a message that may have already reached every
 * recipient in the sub-group. A manifest receiver lets the broadcast wake the process (or simply
 * arrive, if it's already running) and resolve the row instead.
 *
 * <p>Deliberately thin: all retry-vs-fail policy for a failed send is delegated to
 * {@link SentReceiver#handleFailure}, so this app has exactly one place that decides what happens
 * after a send fails, whether it went out as SMS parts or one MMS. This receiver's only job is
 * pulling {@code rowId}/{@code token} back out of the Intent's {@code data} Uri and dispatching to
 * the right {@code OutboxRepository} call.
 */
public class MmsSentReceiver extends BroadcastReceiver {

    private static final String TAG = "MmsSentReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }

        long[] parsed = MmsSender.parseSentData(intent.getData());
        if (parsed == null) {
            Log.e(TAG, "Unparseable data Uri on MMS sent broadcast: " + intent.getData());
            return;
        }
        long rowId = parsed[0];
        long token = parsed[1];

        Context appContext = context.getApplicationContext();
        OutboxRepository outbox = new OutboxRepository(appContext);
        OutboxRepository.OutboxItem item = outbox.findById(rowId);
        if (item == null) {
            // Row no longer exists (or never did) - nothing to record against.
            return;
        }

        int resultCode = getResultCode();
        if (resultCode == Activity.RESULT_OK) {
            // A group-MMS row is handed off as a single unit (one PDU, one PendingIntent) even
            // though it may address several recipients, so one successful result always resolves
            // the whole row - mirrors SentReceiver.handleSuccess's "last part reaches 0" case, but
            // there is only ever one "part" here. recordPartSent already no-ops (-1) on a stale or
            // duplicate result, so no separate guard is needed before calling it.
            outbox.recordPartSent(item.id, token);
            return;
        }

        if (outbox.recordSendFailure(item.id, token, resultCode)) {
            SentReceiver.handleFailure(appContext, item, token, resultCode);
        }
        // false: either already resolved, or this result belongs to a prior attempt whose token no
        // longer matches the row's current one - nothing further happens for this broadcast.
    }
}
