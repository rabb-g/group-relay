package com.sh7411usa.jrelay.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.sh7411usa.jrelay.MainActivity;
import com.sh7411usa.jrelay.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NotificationHelper {

    private static final String TAG = "NotificationHelper";

    private static final String CHANNEL_ID = "admin_messages";

    // Every admin alert collapses into this single id instead of a fresh id
    // per alert. A growing set of distinct, auto-cancel-only ids relies on a
    // human tapping each one to ever clear it; on an unattended relay phone
    // that never happens, so the old counter grew without bound until
    // Android's ~50-notification cap silently dropped every alert after it
    // (audit 3.4). Collapsing onto one id with InboxStyle keeps the most
    // recent alerts visible and bounded no matter how long the phone runs
    // unattended.
    private static final int ADMIN_NOTIFICATION_ID = 1000;

    // How many of the most recent alert lines to keep visible in the
    // expandable notification. Bounds memory and on-screen size regardless
    // of how many alerts have fired.
    private static final int MAX_ALERT_LINES = 8;

    private static final List<String> recentAlerts =
            Collections.synchronizedList(new ArrayList<String>());

    // Admin-alert id above is a single fixed constant, so a small constant
    // here can never collide with it.
    /** Notification id for the ongoing send-service notification. */
    public static final int FOREGROUND_NOTIFICATION_ID = 1;

    private static final String SEND_CHANNEL_ID = "send_service";

    public static void showAdminMessage(Context context, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (!nm.areNotificationsEnabled()) {
            // On API 33+ (and whenever the user has disabled the channel/app),
            // notify() below silently does nothing and reports no error. This
            // is the only trace that an admin alert was ever attempted.
            Log.e(TAG, "Admin alert suppressed, notifications disabled: " + text);
            return;
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(CHANNEL_ID,
                        context.getString(R.string.notification_channel_admin),
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        Notification.InboxStyle inbox = new Notification.InboxStyle();
        String latest;
        int total;
        synchronized (recentAlerts) {
            recentAlerts.add(text);
            while (recentAlerts.size() > MAX_ALERT_LINES) {
                recentAlerts.remove(0);
            }
            for (String line : recentAlerts) {
                inbox.addLine(line);
            }
            total = recentAlerts.size();
            latest = text;
        }
        inbox.setBigContentTitle(context.getString(R.string.notification_admin_title));
        inbox.setSummaryText(String.valueOf(total));

        builder.setContentTitle(context.getString(R.string.notification_admin_title))
                .setContentText(latest)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(mainActivityPendingIntent(context))
                .setStyle(inbox)
                // A single fixed id means tapping now actually clears the
                // notification the alerts are collapsed into, so auto-cancel
                // is meaningful again: whoever opens the dashboard (the only
                // place these alerts matter) dismisses it, and the next
                // alert starts a fresh one. Previously every alert had a
                // distinct id, so auto-cancel-on-tap could never keep up.
                .setAutoCancel(true);

        nm.notify(ADMIN_NOTIFICATION_ID, builder.build());
    }

    /** Tapping either notification opens the dashboard. Shared so the two cannot drift apart. */
    private static PendingIntent mainActivityPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Creates the low-importance channel used by the ongoing send notification. Safe to call repeatedly. */
    public static void ensureSendChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            NotificationChannel channel = nm.getNotificationChannel(SEND_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(SEND_CHANNEL_ID,
                        context.getString(R.string.notif_send_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(context.getString(R.string.notif_send_channel_desc));
                nm.createNotificationChannel(channel);
            }
        }
    }

    /** Builds the ongoing notification shown while a drain is running. */
    public static Notification buildSendNotification(Context context, String statusLine) {
        ensureSendChannel(context);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, SEND_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        builder.setContentTitle(context.getString(R.string.notif_send_title))
                .setContentText(statusLine)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(mainActivityPendingIntent(context))
                .setOngoing(true)
                .setAutoCancel(false);

        return builder.build();
    }

    /** Updates the already-showing ongoing notification in place. No-op if it isn't showing. */
    public static void updateSendNotification(Context context, String statusLine) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }

        boolean showing = false;
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (sbn.getId() == FOREGROUND_NOTIFICATION_ID) {
                showing = true;
                break;
            }
        }
        if (!showing) {
            return;
        }

        nm.notify(FOREGROUND_NOTIFICATION_ID, buildSendNotification(context, statusLine));
    }
}
