package com.sh7411usa.jrelay;

import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class LicenseActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_license);

        WebView webView = findViewById(R.id.webview_license);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // Block the navigation
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true; // For older Android versions
            }
        });
        webView.loadUrl("file:///android_asset/license.html");

    }
}
