package com.sh7411usa.jrelay;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.util.Prefs;

public class SendGroupMessageActivity extends BaseActivity {

    private EditText messageInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_group_message);

        messageInput = findViewById(R.id.edit_group_message);

        // The cost comes from the same plan broadcastToGroup sends from: in SMS delivery one text
        // per active member, in group delivery one group message per sub-group plus a text for
        // everyone those don't reach.
        int[] plan = new CommandProcessor(this).planBroadcast();
        TextView costView = findViewById(R.id.text_send_cost);
        if (new Prefs(this).getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS) {
            costView.setText(getString(R.string.send_group_cost_mms, plan[0], plan[1], plan[2]));
        } else {
            costView.setText(getString(R.string.send_group_cost_note, plan[2]));
        }

        findViewById(R.id.button_send).setOnClickListener(v -> onSend());
        findViewById(R.id.button_cancel).setOnClickListener(v -> finish());
    }

    private void onSend() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) {
            return;
        }
        new CommandProcessor(this).broadcastToGroup(message);
        finish();
    }
}
