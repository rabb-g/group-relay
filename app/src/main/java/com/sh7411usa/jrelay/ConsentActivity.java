package com.sh7411usa.jrelay;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;

import com.sh7411usa.jrelay.util.Prefs;

import java.util.ArrayList;
import java.util.List;

public class ConsentActivity extends BaseActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consent);

        Button agreeButton = findViewById(R.id.button_agree);
        Button declineButton = findViewById(R.id.button_decline);
        CheckBox licenseCheckbox = findViewById(R.id.checkbox_license_agree);

        agreeButton.setEnabled(licenseCheckbox.isChecked());
        licenseCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> agreeButton.setEnabled(isChecked));

        agreeButton.setOnClickListener(v -> onAgree());
        declineButton.setOnClickListener(v -> finish());
        findViewById(R.id.text_view_license_link).setOnClickListener(v ->
                startActivity(new Intent(this, LicenseActivity.class)));
    }

    private void onAgree() {
        new Prefs(this).setConsentAccepted(true);
        List<String> toRequest = neededPermissions();
        if (toRequest.isEmpty()) {
            goToMain();
        } else {
            requestPermissions(toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private List<String> neededPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.SEND_SMS);
        permissions.add(Manifest.permission.RECEIVE_SMS);
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> toRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(permission);
            }
        }
        return toRequest;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            goToMain();
        }
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
