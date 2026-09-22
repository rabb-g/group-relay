package com.sh7411usa.jrelay.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import com.sh7411usa.jrelay.MainActivity;
import com.sh7411usa.jrelay.R;

public class NotificationHelper {

    private static final String CHANNEL_ID = "admin_messages";
    private static final int NOTIFICATION_ID_BASE = 1000;
    private static int notificationCounter = 0;

    // Admin-alert ids above are NOTIFICATION_ID_BASE (1000) and up, so a small
    // constant here can never collide with them.
    /** Notification id for the ongoing send-service notification. */
    public static final int FOREGROUND_NOTIFICATION_ID = 1;

    private static final String SEND_CHANNEL_ID = "send_service";

    public static void showAdminMessage(Context context, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
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

        builder.setContentTitle(context.getString(R.string.notification_admin_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(mainActivityPendingIntent(context))
                .setAutoCancel(true);

        nm.notify(NOTIFICATION_ID_BASE + (++notificationCounter), builder.build());
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
