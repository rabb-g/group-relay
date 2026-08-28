package com.sh7411usa.jrelay;

import android.os.Bundle;
import android.widget.EditText;

import com.sh7411usa.jrelay.sms.CommandProcessor;

public class SendGroupMessageActivity extends BaseActivity {

    private EditText messageInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_group_message);

        messageInput = findViewById(R.id.edit_group_message);

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
