package com.sh7411usa.jrelay.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import com.sh7411usa.jrelay.sms.mms.MmsIngestService;

public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            return;
        }
        String sender = messages[0].getOriginatingAddress();
        if (sender == null) {
            return;
        }
        String normalized = PhoneNumberUtils.normalize(sender);
        if (normalized == null) {
            return;
        }

        String[] partBodies = new String[messages.length];
        for (int i = 0; i < messages.length; i++) {
            partBodies[i] = messages[i].getMessageBody();
        }
        String body = SmsCatchUp.concatBodies(partBodies);

        // Claim before handling (at-most-once), with the same key SmsCatchUp computes from the
        // provider row: the first part's service-center timestamp is what the stock app stores as
        // date_sent. If the catch-up scan already relayed this text (it can, when this broadcast
        // arrives late, e.g. after a boot), the claim fails and it is not relayed a second time.
        if (SmsCatchUp.claim(context, normalized, messages[0].getTimestampMillis(), body,
                System.currentTimeMillis(), SmsCatchUp.SOURCE_LIVE)) {
            new CommandProcessor(context).handleIncoming(normalized, body);
        }
        // Cheap catch-up scan; no-ops unless delivery mode is GROUP_MMS.
        MmsIngestService.start(context.getApplicationContext());
    }
}
