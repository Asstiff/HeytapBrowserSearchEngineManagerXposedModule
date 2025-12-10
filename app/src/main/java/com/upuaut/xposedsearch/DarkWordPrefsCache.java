// app/src/main/java/com/upuaut/xposedsearch/DarkWordPrefsCache.java
package com.upuaut.xposedsearch;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import de.robv.android.xposed.XposedBridge;

public class DarkWordPrefsCache {

    private static final String TAG = "XposedSearch";
    private static final String PROVIDER_URI = "content://com.upuaut.xposedsearch.provider/darkword";

    private static boolean moduleEnabled = true;
    private static boolean darkWordDisabled = false;

    private static final long CACHE_TTL_MS = 2000L;
    private static long lastLoadTime = 0L;

    public static boolean isModuleEnabled() {
        return moduleEnabled;
    }

    public static boolean isDarkWordDisabled() {
        return darkWordDisabled;
    }

    public static void refresh(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastLoadTime < CACHE_TTL_MS) {
            return;
        }

        if (loadFromProvider(context)) {
            lastLoadTime = now;
        }
    }

    private static boolean loadFromProvider(Context context) {
        if (context == null) return false;

        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = Uri.parse(PROVIDER_URI);

            cursor = resolver.query(uri, null, null, null, null);
            if (cursor == null) {
                XposedBridge.log("[" + TAG + "] DarkWordPrefs: cursor is null");
                return false;
            }

            int enabledIndex = cursor.getColumnIndex("moduleEnabled");
            int disabledIndex = cursor.getColumnIndex("darkWordDisabled");

            if (cursor.moveToFirst()) {
                if (enabledIndex >= 0) {
                    moduleEnabled = cursor.getInt(enabledIndex) == 1;
                }
                if (disabledIndex >= 0) {
                    darkWordDisabled = cursor.getInt(disabledIndex) == 1;
                }

                XposedBridge.log("[" + TAG + "] DarkWordPrefs: loaded moduleEnabled=" + moduleEnabled + ", darkWordDisabled=" + darkWordDisabled);
                return true;
            }

            return false;

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] DarkWordPrefs: load failed: " + t.getMessage());
            return false;
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) {}
            }
        }
    }
}