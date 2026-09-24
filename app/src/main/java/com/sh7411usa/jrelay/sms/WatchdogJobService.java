package com.sh7411usa.jrelay.sms;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.util.Log;

import com.sh7411usa.jrelay.sms.mms.MmsIngestService;

/**
 * The periodic self-heal scheduled by {@link Watchdog}. Runs its work on a background thread,
 * since the catch-ups read the SMS/MMS providers and must not run on the main thread.
 * <p>
 * On API 31+ a JobService is NOT exempt from the background foreground-service start
 * restriction, unless the app is exempt from battery optimisation. So the service starts below
 * may be refused (both start() methods catch that). The job is still useful then: SmsCatchUp
 * relays missed SMS, and {@link MmsIngestService#scanOnce} bridges missed group MMS directly.
 */
public class WatchdogJobService extends JobService {

    private static final String TAG = "WatchdogJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        Context context = getApplicationContext();
        new Thread(() -> {
            try {
                // No-op unless delivery mode is GROUP_MMS.
                MmsIngestService.start(context);
                if (!MmsIngestService.isRunning()) {
                    // The start was refused, or has not reached onCreate yet. Scan directly. Even
                    // if the service does come up and scan at the same moment, the two are
                    // serialized, and markIngested (done before forwarding) lets only one of them
                    // ever bridge a given MMS.
                    MmsIngestService.scanOnce(context);
                }
                SmsCatchUp.run(context);
                SmsSendService.start(context);
            } catch (Throwable t) {
                Log.e(TAG, "Watchdog run failed", t);
            } finally {
                jobFinished(params, false);
            }
        }, "WatchdogJob").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Nothing to reschedule early: the periodic job runs again on its next interval.
        return false;
    }
}
