package com.sh7411usa.jrelay.sms.mms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

/**
 * Fires at MMS notification time, BEFORE the stock messaging app has downloaded the message
 * body -- reading content://mms right now would just see the still-empty placeholder row
 * (m_type 130). This receiver does no reading itself; it only kicks {@link MmsIngestService},
 * whose ContentObserver on content://mms catches the body once the stock app finishes the
 * download a moment later.
 */
public class WapPushReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.WAP_PUSH_RECEIVED".equals(intent.getAction())) {
            return;
        }
        MmsIngestService.start(context.getApplicationContext());
    }
}
