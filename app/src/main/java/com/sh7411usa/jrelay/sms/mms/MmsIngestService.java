package com.sh7411usa.jrelay.sms.mms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Telephony;
import android.util.Log;

import com.sh7411usa.jrelay.MainActivity;
import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.ThreadRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.sms.SmsSendService;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RelayIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches {@code content://mms} for new inbound group-MMS replies and hands each one to
 * {@link CommandProcessor#handleThreadMessage} to be bridged to the other sub-groups.
 * <p>
 * jRelay is not the default SMS app, so it never writes to the MMS provider; the stock messaging
 * app downloads inbound MMS into it on its own, and this service only notices and reads what
 * lands there (see {@link MmsReader}). Only runs anything when
 * {@link Prefs#getDeliveryMode()} is {@link Prefs.DeliveryMode#GROUP_MMS} -- in SMS mode there are
 * no group-MMS threads to bridge.
 */
public class MmsIngestService extends Service {

    private static final String TAG = "MmsIngestService";
    private static final String CHANNEL_ID = "mms_ingest";
    private static final int NOTIFICATION_ID = 2;

    /**
     * How old an inbound MMS must be before a negative outcome (unreadable, no text, unmatched, not
     * a member) is recorded permanently. The stock app writes a downloaded MMS in stages, so a young
     * message may simply not be fully written yet. Two minutes is far longer than that write takes,
     * and short enough that a genuinely unmatched message stops being re-read almost at once.
     */
    private static final long SETTLE_MILLIS = 120_000L;

    /**
     * True from onCreate until onDestroy, cleared early if startForeground is refused. Static and
     * process-local on purpose: process death resets it to false, which is the truth -- nothing is
     * watching content://mms then. Read by the Dashboard banner and by {@code Watchdog}.
     */
    private static volatile boolean running;

    /**
     * Serializes every scan, the service's own and {@link #scanOnce}'s, so two can never run at the
     * same time in this process. This is belt and braces, not the double-bridge guard: that is
     * {@link ThreadRepository#markIngested}, a persistent INSERT OR IGNORE done BEFORE forwarding,
     * which only one caller can ever win for a given MMS id.
     */
    private static final Object SCAN_LOCK = new Object();

    /** What a scan asks its caller to do afterwards. Only the service acts on these. */
    private enum ScanOutcome { DONE, MODE_OFF, DEFERRED }

    private HandlerThread thread;
    private Handler handler;
    private ContentObserver observer;

    /** True while a scan is running on {@link #handler}; guards against overlapping scans. */
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    /** Set when a change arrives mid-scan, so exactly one more scan runs after this one finishes. */
    private final AtomicBoolean rerunScan = new AtomicBoolean(false);

    /**
     * Starts the service if group MMS delivery is on. Mirrors {@link SmsSendService#start}: catches
     * the exception a background-start restriction throws on API 26+ rather than letting it crash
     * the caller, since ingestion resuming late (on the next trigger) is far better than a crash.
     * <p>
     * On API 31+ a foreground service may only be started from the background by an exempt
     * trigger. Exempt among our callers: BOOT_COMPLETED and MY_PACKAGE_REPLACED (BootReceiver),
     * SMS_RECEIVED and WAP_PUSH_RECEIVED (SmsReceiver, WapPushReceiver), and any start while an
     * activity is visible (MainActivity, SettingsActivity). NOT exempt: the periodic
     * {@code WatchdogJobService}, unless the app is exempt from battery optimisation. There the
     * start throws ForegroundServiceStartNotAllowedException, caught below, and the watchdog falls
     * back to {@link #scanOnce}.
     */
    public static void start(Context context) {
        if (new Prefs(context).getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            return;
        }
        Intent intent = new Intent(context, MmsIngestService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start MMS ingest service", e);
        }
    }

    /** Whether the service is alive in this process (see {@link #running}). */
    public static boolean isRunning() {
        return running;
    }

    /**
     * Runs one scan synchronously, without the service, for when the service could not be started
     * (a background FGS start refused on API 31+). Must be called off the main thread. Blocks while
     * the service's own scan runs, via {@link #SCAN_LOCK}. A message still inside the settle window
     * is left unmarked and simply picked up by the next scan or watchdog run.
     */
    public static void scanOnce(Context context) {
        if (new Prefs(context).getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            return;
        }
        try {
            synchronized (SCAN_LOCK) {
                scan(context.getApplicationContext());
            }
        } catch (Throwable t) {
            Log.e(TAG, "One-off MMS ingest scan failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        thread = new HandlerThread("MmsIngestWorker");
        thread.start();
        handler = new Handler(thread.getLooper());

        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                requestScan();
            }
        };
        getContentResolver().registerContentObserver(
                Telephony.Mms.CONTENT_URI, true, observer);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        // START_STICKY means Android may restart this service on its own after process death. On
        // Android 12+ that restart can run in the background, where startForeground throws
        // ForegroundServiceStartNotAllowedException (an IllegalStateException). Uncaught, that
        // crashes the app -- repeatedly, under the restart backoff. Give up quietly instead: every
        // real trigger (boot, inbound SMS, WAP push, app open) calls start() again, and the
        // watermark never advances past an unscanned message, so nothing is lost by waiting.
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Not allowed to start in the foreground right now; will resume on next trigger", e);
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (new Prefs(this).getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            stopSelf();
            return START_NOT_STICKY;
        }
        requestScan();
        return START_STICKY;
    }

    /** Single-flight: if a scan is already running, just record that another pass is needed. */
    private void requestScan() {
        if (!scanning.compareAndSet(false, true)) {
            rerunScan.set(true);
            return;
        }
        handler.post(this::runScanLoop);
    }

    private void runScanLoop() {
        try {
            ScanOutcome outcome;
            synchronized (SCAN_LOCK) {
                outcome = scan(this);
            }
            if (outcome == ScanOutcome.MODE_OFF) {
                // Group delivery was switched off while this service was running. Stop rather than
                // sit in the foreground doing nothing.
                stopSelf();
            } else if (outcome == ScanOutcome.DEFERRED) {
                // One re-scan after the settle window, so a message caught mid-write is retried even
                // if nothing else changes in content://mms in the meantime.
                handler.postDelayed(this::requestScan, SETTLE_MILLIS + 5_000L);
            }
        } catch (Throwable t) {
            Log.e(TAG, "MMS ingest scan failed", t);
        } finally {
            scanning.set(false);
        }
        // A change that arrived while this scan was running is covered by exactly one more pass,
        // not a busy loop: rerunScan is consumed here and, if set, re-acquires and posts itself again.
        if (rerunScan.compareAndSet(true, false) && scanning.compareAndSet(false, true)) {
            handler.post(this::runScanLoop);
        }
    }

    /**
     * One pass over recent inbound MMS. Static so {@link #scanOnce} can run it without the service;
     * callers must hold {@link #SCAN_LOCK}. Never bridges in SMS mode.
     */
    private static ScanOutcome scan(Context context) {
        Prefs prefs = new Prefs(context);
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) {
            return ScanOutcome.MODE_OFF;
        }
        long since = prefs.getMmsIngestSinceSeconds();
        if (since == 0) {
            // Never bridge history that predates enabling group delivery: seed the watermark to
            // "now" and pick up from the next change instead of scanning backward.
            prefs.setMmsIngestSinceSeconds(System.currentTimeMillis() / 1000L);
            return ScanOutcome.DONE;
        }

        MemberRepository memberRepository = new MemberRepository(context);
        ThreadRepository threadRepository = new ThreadRepository(context);

        // Idempotent re-seed of every sub-group's thread signature, so a thread created by an
        // earlier build (before this feature existed) is still recognised.
        for (Long subgroupId : memberRepository.getDistinctSubgroupIds()) {
            List<Member> members = memberRepository.getSubgroupMembers(subgroupId);
            List<String> phones = new ArrayList<>(members.size());
            for (Member m : members) {
                phones.add(m.phoneE164);
            }
            threadRepository.recordThread(ThreadMatcher.signature(phones), subgroupId);
        }

        MmsReader reader = new MmsReader(context);
        MmsReader.ReadResult result = reader.fetchRecentInboxIds(since);
        if (result.permissionDenied) {
            Log.e(TAG, "READ_SMS denied; cannot scan for inbound group MMS");
            return ScanOutcome.DONE;
        }

        CommandProcessor processor = new CommandProcessor(context);
        boolean bridgedAny = false;
        boolean deferredAny = false;
        long nowMs = System.currentTimeMillis();
        for (MmsReader.InboundMms stub : result.messages) {
            if (threadRepository.isIngested(stub.id)) {
                continue;
            }
            // The stock app writes a downloaded MMS in stages: the message row first, then its
            // addresses and text part. The ContentObserver fires on the first insert, so a scan
            // can land mid-write and see no body or a partial address list. A negative outcome is
            // therefore only made permanent once the message is old enough to be fully written;
            // until then it is skipped unmarked, and a delayed re-scan is scheduled below. Without
            // this, a message caught mid-write would be marked UNREADABLE or UNMATCHED forever and
            // silently never reach the other groups.
            boolean settled = nowMs - stub.timestampMs >= SETTLE_MILLIS;

            MmsReader.InboundMms m = reader.readMessage(stub.id, stub.timestampMs);
            String rejection = null;
            ThreadMatcher.Match match = null;
            Member sender = null;
            if (m == null) {
                rejection = "UNREADABLE";
            } else if (m.body == null || m.body.trim().isEmpty()) {
                // Picture-only MMS, no text to relay -- out of scope.
                rejection = "NO_TEXT";
            } else {
                match = ThreadMatcher.match(m.sender, m.recipients,
                        threadRepository::findSubgroupForSignature);
                if (match == null) {
                    // Not a thread jRelay itself created -- a private chat that happens to include
                    // this phone. Never bridge, never reply.
                    rejection = "UNMATCHED";
                } else {
                    learnOwnNumber(context, match);
                    sender = memberRepository.findByPhone(PhoneNumberUtils.normalize(m.sender));
                    if (sender == null) {
                        rejection = "NOT_MEMBER";
                    }
                }
            }
            if (rejection != null) {
                if (settled) {
                    threadRepository.markIngested(stub.id, rejection);
                } else {
                    deferredAny = true;
                }
                continue;
            }
            // Mark ingested BEFORE forwarding: at-most-once. A crash between the two loses one
            // bridge, which is recoverable; the reverse order risks the same MMS being forwarded to
            // ~11 groups / ~100 people twice on a retry.
            if (threadRepository.markIngested(stub.id, "BRIDGED")) {
                processor.handleThreadMessage(sender, match.subgroupId, m.body);
                bridgedAny = true;
            }
        }

        if (bridgedAny) {
            SmsSendService.start(context);
        }
        return deferredAny ? ScanOutcome.DEFERRED : ScanOutcome.DONE;
    }

    /**
     * Learns the relay's own number from a matched thread. ThreadMatcher only matches when the
     * participants are the recorded signature plus exactly one extra, and a recorded signature is
     * the members jRelay itself sent to, so it never contains the relay. The relay is always on
     * its own threads, and inbound group MMS list it in TO (verified on the device), so that one
     * extra is usually the relay's own number, but not always (a thread missing the relay, matched
     * against an older signature, points at a member), so it is only a candidate:
     * RelayIdentity stores it after a second, different thread agrees, and never over a stored
     * number.
     */
    private static void learnOwnNumber(Context context, ThreadMatcher.Match match) {
        RelayIdentity.observeThreadCandidate(context, match.removedParticipant, match.signature);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }

    /** Own low-importance channel, separate from {@code SmsSendService}'s -- this is a distinct ongoing job. */
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.notif_mms_ingest_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.notif_mms_ingest_channel_desc));
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.setContentTitle(getString(R.string.notif_mms_ingest_title))
                .setContentText(getString(R.string.notif_mms_ingest_idle))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
