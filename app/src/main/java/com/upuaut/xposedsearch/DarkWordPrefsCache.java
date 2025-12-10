// app/src/main/java/com/upuaut/xposedsearch/DarkWordPrefsCache.java
package com.upuaut.xposedsearch;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import de.robv.android.xposed.XposedBridge;

public class DarkWordPrefsCache {

    private static final String TAG = "XposedSearch";
    private static final String PROVIDER_URI = "content://com.upuaut.xposedsearch.provider/darkword";
    private static final String LOCAL_CACHE_PREF = "xposed_darkword_cache";

    private static boolean moduleEnabled = true;
    private static boolean darkWordDisabled = false;

    private static final long CACHE_TTL_MS = 2000L;
    private static long lastLoadTime = 0L;

    // 性能优化：Provider 失败熔断机制
    private static int providerFailureCount = 0;
    private static final int MAX_FAILURES = 3;
    private static boolean providerCircuitOpen = false;

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

        // 1. 尝试从 Provider 加载
        if (!providerCircuitOpen) {
            if (loadFromProvider(context)) {
                lastLoadTime = now;
                providerFailureCount = 0;
                saveToLocalCache(context);
                return;
            } else {
                providerFailureCount++;
                if (providerFailureCount >= MAX_FAILURES) {
                    providerCircuitOpen = true;
                    XposedBridge.log("[" + TAG + "] DarkWordPrefs: Provider failed " + MAX_FAILURES + " times. Circuit breaker OPEN.");
                }
            }
        }

        // 2. Provider 失败，尝试从本地缓存加载
        // 注意：DarkWord 比较简单，静态变量其实就是内存缓存，
        // 这里主要是在 Provider 彻底挂掉后，尝试从 SharedPreferences 恢复一次值
        if (lastLoadTime == 0) {
            loadFromLocalCache(context);
            lastLoadTime = now; // 防止频繁读取 SP
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

    private static void saveToLocalCache(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(LOCAL_CACHE_PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putBoolean("moduleEnabled", moduleEnabled)
                    .putBoolean("darkWordDisabled", darkWordDisabled)
                    .apply();
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] DarkWordPrefs: save local failed: " + t.getMessage());
        }
    }

    private static void loadFromLocalCache(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(LOCAL_CACHE_PREF, Context.MODE_PRIVATE);
            moduleEnabled = sp.getBoolean("moduleEnabled", true);
            darkWordDisabled = sp.getBoolean("darkWordDisabled", false);
            XposedBridge.log("[" + TAG + "] DarkWordPrefs: loaded local moduleEnabled=" + moduleEnabled + ", darkWordDisabled=" + darkWordDisabled);
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] DarkWordPrefs: load local failed: " + t.getMessage());
        }
    }
}