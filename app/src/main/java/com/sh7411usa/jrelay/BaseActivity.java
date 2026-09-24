package com.sh7411usa.jrelay;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import com.sh7411usa.jrelay.util.LocaleThemeUtil;

/** Every screen extends this instead of Activity so the user's language/theme Settings apply app-wide. */
public abstract class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleThemeUtil.wrap(newBase));
    }

    /**
     * Android 15 (API 35+) enforces edge-to-edge for apps targeting 35+; the opt-out flag is
     * ignored. Without this, the header of every screen draws under the status bar and the
     * bottom of every scrollable screen draws under the gesture nav bar, where a DPAD user can
     * focus a control they cannot see (audit 4.7).
     *
     * Every activity in the app calls setContentView(int) with an inflated layout (verified: no
     * activity sets its content view another way), so hooking that single method here reaches
     * all of them, including LicenseActivity whose root is a bare WebView rather than a
     * ScrollView. We pad the system content container (android.R.id.content) itself rather than
     * reaching into each activity's inflated root, so this does not touch any layout file and
     * works regardless of what the root view is.
     */
    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        applyEdgeToEdgeInsets();
    }

    private void applyEdgeToEdgeInsets() {
        final View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        // Record the layout's own padding once, before any insets are applied, so repeated
        // dispatches (rotation, IME, re-layout) always compute padding as
        // "original + insets" instead of accumulating on top of the previous result.
        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();

        content.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                // getSystemWindowInset*() is deprecated but is the only path available below
                // API 30 and remains functional through minSdk 24.
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom);
            return insets;
        });
        content.requestApplyInsets();
    }
}
