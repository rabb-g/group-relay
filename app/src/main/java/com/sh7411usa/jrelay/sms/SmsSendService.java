package com.sh7411usa.jrelay.sms;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
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

    /** Sending is global: one shared burst/wait/microspacing cadence drains the whole outbox, not one per recipient. */
    private void drainAll() {
        OutboxRepository outbox = new OutboxRepository(this);
        MessageRepository messageRepository = new MessageRepository(this);
        MemberRepository memberRepository = new MemberRepository(this);
        Prefs prefs = new Prefs(this);
        RateLimitConfig config = RateLimitConfig.groupDefault(prefs);
        Random random = new Random();
        SmsManager smsManager = SmsManager.getDefault();
        boolean shuffle = prefs.isDeliveryShuffleEnabled();

        try {
            if (outbox.countPending() <= 0) {
                return;
            }

            int burstSize = nextBurstSize(outbox, config, random);
            if (config.staggeringEnabled && config.initialDelayEnabled) {
                waitBeforeNextBurst(outbox, config, random, burstSize);
            }

            List<OutboxRepository.OutboxItem> burst = outbox.takeBurst(burstSize, shuffle);
            while (!burst.isEmpty()) {
                sendBurst(smsManager, outbox, messageRepository, memberRepository, prefs, config, random, burst);
                if (outbox.countPending() <= 0) {
                    break;
                }
                int nextSize = nextBurstSize(outbox, config, random);
                if (config.staggeringEnabled) {
                    waitBeforeNextBurst(outbox, config, random, nextSize);
                }
                burst = outbox.takeBurst(nextSize, shuffle);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            SendQueueStatus.clear();
        }
    }

    private int nextBurstSize(OutboxRepository outbox, RateLimitConfig config, Random random) {
        switch (config.burstMode) {
            case FIXED:
                return Math.max(1, config.fixedBurstSize);
            case ALL_AT_ONCE:
                return Math.max(1, outbox.countPending());
            case RANDOM_RANGE:
            default:
                return randomBurstSize(config, random);
        }
    }

    private int randomBurstSize(RateLimitConfig config, Random random) {
        int min = Math.max(1, config.burstMin);
        int max = Math.max(min, config.burstMax);
        return min + (max > min ? random.nextInt(max - min + 1) : 0);
    }

    private void waitBeforeNextBurst(OutboxRepository outbox, RateLimitConfig config, Random random, int upcomingBurstSize)
            throws InterruptedException {
        int min = Math.max(0, config.minWaitSeconds);
        int max = Math.max(min, config.maxWaitSeconds);
        int waitSeconds = min + (max > min ? random.nextInt(max - min + 1) : 0);

        int pending = outbox.countPending();
        int displaySize = pending > 0 ? Math.min(upcomingBurstSize, pending) : upcomingBurstSize;
        SendQueueStatus.setNextBurst(System.currentTimeMillis() + waitSeconds * 1000L, displaySize);

        Thread.sleep(waitSeconds * 1000L);
    }

    private void sendBurst(SmsManager smsManager, OutboxRepository outbox, MessageRepository messageRepository,
                            MemberRepository memberRepository, Prefs prefs, RateLimitConfig config,
                            Random random, List<OutboxRepository.OutboxItem> burst) {
        for (int i = 0; i < burst.size(); i++) {
            sendOne(smsManager, outbox, messageRepository, memberRepository, prefs, burst.get(i));
            if (config.microspacingEnabled && i < burst.size() - 1) {
                MicroSpacer.waitMillis(microspacingDelayMillis(config, random));
            }
        }
    }

    /** Fractional-millisecond gap between two sends in the same burst; randomized between bounds when configured. */
    private double microspacingDelayMillis(RateLimitConfig config, Random random) {
        if (config.microspacingMode == Prefs.MicrospacingMode.RANDOM_RANGE) {
            int min = Math.max(0, config.microspacingMinMs);
            int max = Math.max(min, config.microspacingMaxMs);
            return min + random.nextDouble() * (max - min);
        }
        return Math.max(0, config.microspacingFixedMs);
    }

    private void sendOne(SmsManager smsManager, OutboxRepository outbox, MessageRepository messageRepository,
                          MemberRepository memberRepository, Prefs prefs, OutboxRepository.OutboxItem item) {
        boolean success;
        try {
            if (item.body.length() > MAX_PART_LENGTH) {
                ArrayList<String> parts = smsManager.divideMessage(item.body);
                smsManager.sendMultipartTextMessage(item.phoneE164, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(item.phoneE164, null, item.body, null, null);
            }
            success = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS to " + item.phoneE164, e);
            success = false;
        }

        Member member = item.memberId != null ? memberRepository.findById(item.memberId) : null;

        if (success) {
            outbox.markSent(item.id);
            if (member != null) {
                memberRepository.resetFailedCount(member.id);
            }
            return;
        }

        int attempts = item.attempts + 1;
        int maxAttempts = Math.max(1, prefs.getRetryLimit() + 1);
        if (attempts < maxAttempts) {
            outbox.requeueForRetry(item.id, attempts);
            return;
        }

        outbox.markFailed(item.id);
        messageRepository.log(item.memberId, "OUT", "FAILED", item.body);
        if (member != null) {
            int failedCount = memberRepository.incrementFailedCount(member.id);
            int threshold = prefs.getFailureAlertThreshold();
            if (threshold > 0 && failedCount % threshold == 0) {
                new CommandProcessor(this).alertAdminsOfFailures(member, failedCount);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
