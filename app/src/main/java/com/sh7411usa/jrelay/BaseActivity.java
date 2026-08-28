package com.sh7411usa.jrelay;

import android.app.Activity;
import android.content.Context;

import com.sh7411usa.jrelay.util.LocaleThemeUtil;

/** Every screen extends this instead of Activity so the user's language/theme Settings apply app-wide. */
public abstract class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleThemeUtil.wrap(newBase));
    }
}
