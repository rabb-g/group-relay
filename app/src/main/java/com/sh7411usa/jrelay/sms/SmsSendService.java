package com.sh7411usa.jrelay.sms;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RateLimitConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SmsSendService extends Service {

    private static final String TAG = "SmsSendService";
    private static final int MAX_PART_LENGTH = 160;

    public static void start(Context context) {
        context.startService(new Intent(context, SmsSendService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            drainAll();
            stopSelf(startId);
        }).start();
        return START_NOT_STICKY;
    }

    /**
     * Each recipient can have their own send pace (group default or a per-member override), so
     * every recipient with pending mail drains on its own thread with its own burst/wait cadence.
     */
    private void drainAll() {
        OutboxRepository outbox = new OutboxRepository(this);
        List<Long> memberIds = outbox.getDistinctPendingMemberIds();

        List<Thread> workers = new ArrayList<>();
        for (Long memberId : memberIds) {
            Thread worker = new Thread(() -> drainMember(memberId));
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drainMember(Long memberId) {
        OutboxRepository outbox = new OutboxRepository(this);
        Prefs prefs = new Prefs(this);
        MemberRepository memberRepository = new MemberRepository(this);
        Member member = memberId != null ? memberRepository.findById(memberId) : null;
        RateLimitConfig config = RateLimitConfig.forMember(prefs, member);
        Random random = new Random();
        SmsManager smsManager = SmsManager.getDefault();

        try {
            if (outbox.countPendingForMember(memberId) <= 0) {
                return;
            }

            int burstSize = randomBurstSize(config, random);
            if (config.initialDelayEnabled) {
                waitBeforeNextBurst(outbox, memberId, config, random, burstSize);
            }

            List<OutboxRepository.OutboxItem> burst = outbox.takeBurstForMember(memberId, burstSize);
            while (!burst.isEmpty()) {
                for (OutboxRepository.OutboxItem item : burst) {
                    sendOne(smsManager, outbox, item);
                }
                if (outbox.countPendingForMember(memberId) <= 0) {
                    break;
                }
                int nextBurstSize = randomBurstSize(config, random);
                waitBeforeNextBurst(outbox, memberId, config, random, nextBurstSize);
                burst = outbox.takeBurstForMember(memberId, nextBurstSize);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            SendQueueStatus.clear(memberId);
        }
    }

    private int randomBurstSize(RateLimitConfig config, Random random) {
        int min = Math.max(1, config.burstMin);
        int max = Math.max(min, config.burstMax);
        return min + (max > min ? random.nextInt(max - min + 1) : 0);
    }

    private void waitBeforeNextBurst(OutboxRepository outbox, Long memberId, RateLimitConfig config, Random random, int upcomingBurstSize)
            throws InterruptedException {
        int min = Math.max(0, config.minWaitSeconds);
        int max = Math.max(min, config.maxWaitSeconds);
        int waitSeconds = min + (max > min ? random.nextInt(max - min + 1) : 0);

        int pending = outbox.countPendingForMember(memberId);
        int displaySize = pending > 0 ? Math.min(upcomingBurstSize, pending) : upcomingBurstSize;
        SendQueueStatus.setNextBurst(memberId, System.currentTimeMillis() + waitSeconds * 1000L, displaySize);

        Thread.sleep(waitSeconds * 1000L);
    }

    private void sendOne(SmsManager smsManager, OutboxRepository outbox, OutboxRepository.OutboxItem item) {
        try {
            if (item.body.length() > MAX_PART_LENGTH) {
                ArrayList<String> parts = smsManager.divideMessage(item.body);
                smsManager.sendMultipartTextMessage(item.phoneE164, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(item.phoneE164, null, item.body, null, null);
            }
            outbox.markSent(item.id);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS to " + item.phoneE164, e);
            outbox.markFailed(item.id);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
