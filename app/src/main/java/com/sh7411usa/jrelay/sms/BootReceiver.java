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
 * <p>
 * Also handles MY_PACKAGE_REPLACED: an app update stops the running services and Android never
 * restarts them, which is how group-MMS forwarding once silently stopped. Both broadcasts are
 * exempt from the API 31+ background foreground-service start restriction, so the starts below
 * are allowed here.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        // After a reboot persisted jobs survive on their own; this covers a job that was never set.
        Watchdog.schedule(appContext);

        // Boot only. Resetting orphaned SENDING rows is only safe here while paused: drainAll()
        // checks isPaused() before ever claiming a burst, so while paused no row can legitimately be
        // SENDING. When not paused, a drain may already be mid-burst in this same process (all
        // four components share it), and flipping its claimed rows back to PENDING would send
        // them twice - so leave the reset to SmsSendService.onStartCommand, which only performs
        // it on the one path where it's provably safe (no drain currently running).
        // An app update does neither the reset nor the pause check: a paused drain sends nothing,
        // and CommandProcessor drops posts while paused.
        if (boot && new Prefs(appContext).isPaused()) {
            int reset = new OutboxRepository(appContext).resetOrphanedSending();
            if (reset > 0) {
                Log.i(TAG, "Reset " + reset + " orphaned SENDING row(s) after boot");
            }
            // No catch-up while paused: handleIncoming drops every text then.
            return;
        }
        SmsSendService.start(appContext);
        // No-ops unless delivery mode is GROUP_MMS.
        MmsIngestService.start(appContext);
        // Relays texts that arrived while the app was down (after a boot: before the first unlock,
        // since the app is not direct-boot aware), now rather than at the next watchdog run.
        // goAsync keeps the process alive until the scan finishes.
        SmsCatchUp.runAsync(appContext, goAsync());
    }
}
