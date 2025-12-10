// app/src/main/java/com/upuaut/xposedsearch/DarkWordConfigManager.java
package com.upuaut.xposedsearch;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.File;

public class DarkWordConfigManager {

    private static final String TAG = "XposedSearch";
    public static final String PREF_NAME = "xposed_dark_words";
    private static final String KEY_ENABLED = "module_enabled";
    private static final String KEY_DISABLED = "darkword_disabled";

    public static final String AUTHORITY = "com.upuaut.xposedsearch.provider";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isModuleEnabled(Context context) {
        if (context == null) return true;
        return getPrefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setModuleEnabled(Context context, boolean enabled) {
        if (context == null) return;
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
        makePrefsWorldReadable(context);
        notifyChange(context);
    }

    public static boolean isDarkWordDisabled(Context context) {
        if (context == null) return false;
        return getPrefs(context).getBoolean(KEY_DISABLED, false);
    }

    public static void setDarkWordDisabled(Context context, boolean disabled) {
        if (context == null) return;
        getPrefs(context).edit().putBoolean(KEY_DISABLED, disabled).commit();
        makePrefsWorldReadable(context);
        notifyChange(context);
    }

    private static void makePrefsWorldReadable(Context context) {
        try {
            File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false);
                prefsDir.setExecutable(true, false);
            }

            File prefsFile = new File(prefsDir, PREF_NAME + ".xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "makePrefsWorldReadable failed: " + e.getMessage());
        }
    }

    private static void notifyChange(Context context) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/darkword");
            context.getContentResolver().notifyChange(uri, null);
        } catch (Exception e) {
            Log.e(TAG, "notifyChange failed: " + e.getMessage());
        }
    }
}