package com.sh7411usa.jrelay.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.sms.mms.MmsIngestService;
import com.sh7411usa.jrelay.util.Prefs;

/**
 * Resumes the outbox after a reboot. Samsung devices in particular ship "Auto restart at set
 * times", sometimes enabled by default, so a queued outbox could otherwise sit untouched for
 * hours with no sign anything is wrong. Also recovers rows a reboot mid-drain stranded in
 * SENDING, which nothing else would ever reset.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Context appContext = context.getApplicationContext();

        // Resetting orphaned SENDING rows is only safe here while paused: drainAll() checks
        // isPaused() before ever claiming a burst, so while paused no row can legitimately be
        // SENDING. When not paused, a drain may already be mid-burst in this same process (all
        // four components share it), and flipping its claimed rows back to PENDING would send
        // them twice - so leave the reset to SmsSendService.onStartCommand, which only performs
        // it on the one path where it's provably safe (no drain currently running).
        if (new Prefs(appContext).isPaused()) {
            int reset = new OutboxRepository(appContext).resetOrphanedSending();
            if (reset > 0) {
                Log.i(TAG, "Reset " + reset + " orphaned SENDING row(s) after boot");
            }
            return;
        }
        SmsSendService.start(appContext);
        // No-ops unless delivery mode is GROUP_MMS.
        MmsIngestService.start(appContext);
    }
}
