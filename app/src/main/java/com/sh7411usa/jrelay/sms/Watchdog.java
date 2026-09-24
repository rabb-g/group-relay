package com.sh7411usa.jrelay.sms;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Schedules {@link WatchdogJobService}: a periodic self-heal that restarts the relay's services
 * and catches up on anything missed while they were down. Exists because MmsIngestService can
 * stop with nothing noticing -- killed by an app update or by Samsung battery management -- and
 * the other start hooks (boot, inbound SMS, WAP push, app open) only fire on their own events.
 */
public final class Watchdog {

    private static final String TAG = "Watchdog";
    private static final int JOB_ID = 7411;
    /** The platform minimum for a periodic job; anything shorter is silently clamped to this. */
    private static final long INTERVAL_MILLIS = 15 * 60 * 1000L;

    private Watchdog() {
    }

    /** Idempotent: schedules (or keeps) a periodic JobScheduler job, every 15 minutes (the platform minimum), persisted across reboot. */
    public static void schedule(Context context) {
        Context appContext = context.getApplicationContext();
        JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }
        // Keep an existing job rather than rescheduling it: schedule() replaces the job and
        // restarts its period, so calling it from every onResume would keep pushing the next run
        // back and the watchdog might never fire.
        if (scheduler.getPendingJob(JOB_ID) != null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(appContext, WatchdogJobService.class))
                .setPeriodic(INTERVAL_MILLIS)
                // Needs RECEIVE_BOOT_COMPLETED, which the manifest declares.
                .setPersisted(true)
                .build();
        try {
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.e(TAG, "Could not schedule watchdog job");
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not schedule watchdog job", e);
        }
    }
}
