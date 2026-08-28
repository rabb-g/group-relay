package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * Forces a per-app language/theme choice by wrapping a Context's Configuration, instead of the
 * androidx AppCompatDelegate mechanism (this project stays off androidx entirely). Applied in
 * every Activity's attachBaseContext via BaseActivity, so values-iw/values-yi and values-night
 * resource selection follow the user's Settings choice regardless of the device's own locale/theme.
 */
public final class LocaleThemeUtil {

    private LocaleThemeUtil() {
    }

    public static Context wrap(Context base) {
        Prefs prefs = new Prefs(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());

        String languageCode = prefs.getLanguageCode();
        if (!Prefs.LANGUAGE_SYSTEM.equals(languageCode)) {
            Locale locale = new Locale(languageCode);
            Locale.setDefault(locale);
            config.setLocale(locale);
        }

        Prefs.ThemeChoice theme = prefs.getThemeChoice();
        if (theme == Prefs.ThemeChoice.LIGHT) {
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        } else if (theme == Prefs.ThemeChoice.DARK) {
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        }

        return base.createConfigurationContext(config);
    }
}
