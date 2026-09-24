package com.sh7411usa.jrelay;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
        List<String> toRequest = neededPermissions();
        if (toRequest.isEmpty()) {
            new Prefs(this).setConsentAccepted(true);
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

    private static boolean isCritical(String permission) {
        return Manifest.permission.SEND_SMS.equals(permission)
                || Manifest.permission.RECEIVE_SMS.equals(permission);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }

        boolean criticalDenied = false;
        boolean criticalPermanentlyDenied = false;
        boolean notificationsDenied = false;

        for (int i = 0; i < permissions.length; i++) {
            if (i >= grantResults.length || grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                continue;
            }
            if (isCritical(permissions[i])) {
                criticalDenied = true;
                if (!shouldShowRequestPermissionRationale(permissions[i])) {
                    criticalPermanentlyDenied = true;
                }
            } else {
                notificationsDenied = true;
            }
        }

        if (criticalDenied) {
            showCriticalDenialDialog(criticalPermanentlyDenied);
            return;
        }

        new Prefs(this).setConsentAccepted(true);
        if (notificationsDenied) {
            showNotificationsDeniedDialog();
        } else {
            goToMain();
        }
    }

    private void showCriticalDenialDialog(boolean permanentlyDenied) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setMessage(R.string.permission_denied_critical)
                .setCancelable(false);
        if (permanentlyDenied) {
            builder.setPositiveButton(R.string.action_open_app_settings, (dialog, which) -> openAppSettings());
        } else {
            builder.setPositiveButton(R.string.action_retry_permission, (dialog, which) -> onAgree());
        }
        builder.setNegativeButton(R.string.action_cancel, null);
        builder.show();
    }

    private void showNotificationsDeniedDialog() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.permission_denied_notifications)
                .setCancelable(false)
                .setPositiveButton(R.string.action_continue, (dialog, which) -> goToMain())
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
