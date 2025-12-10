// app/src/main/java/com/upuaut/xposedsearch/HotSitePrefsCache.java
package com.upuaut.xposedsearch;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Xposed 侧的热门网站配置缓存
 */
public class HotSitePrefsCache {

    private static final String TAG = "XposedSearch";
    private static final String PROVIDER_URI = "content://com.upuaut.xposedsearch.provider/hotsites";
    private static final String LOCAL_CACHE_PREF = "xposed_hotsites_cache";

    // 内存缓存
    private static final List<SiteConfig> memoryCacheList = new ArrayList<>();
    private static boolean moduleEnabled = true;

    // 缓存有效期
    private static final long CACHE_TTL_MS = 5000L;
    private static long lastLoadTime = 0L;

    public static class SiteConfig {
        public long id;
        public String name;
        public String url;
        public String iconUrl;
        public boolean enabled;
        public int order;

        public SiteConfig() {}

        public SiteConfig(long id, String name, String url, String iconUrl, boolean enabled, int order) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.iconUrl = iconUrl;
            this.enabled = enabled;
            this.order = order;
        }
    }

    public static boolean isModuleEnabled() {
        return moduleEnabled;
    }

    public static List<SiteConfig> getSiteConfigs(Context context) {
        long now = System.currentTimeMillis();

        if (now - lastLoadTime < CACHE_TTL_MS && !memoryCacheList.isEmpty()) {
            return new ArrayList<>(memoryCacheList);
        }

        if (loadFromProvider(context)) {
            lastLoadTime = now;
            saveToLocalCache(context);
            return new ArrayList<>(memoryCacheList);
        }

        if (!memoryCacheList.isEmpty()) {
            XposedBridge.log("[" + TAG + "] HotSites: Using memory cache");
            return new ArrayList<>(memoryCacheList);
        }

        if (loadFromLocalCache(context)) {
            XposedBridge.log("[" + TAG + "] HotSites: Loaded from local cache");
            return new ArrayList<>(memoryCacheList);
        }

        return new ArrayList<>();
    }

    public static void refresh(Context context) {
        lastLoadTime = 0;
        getSiteConfigs(context);
    }

    public static void clearMemoryCache() {
        memoryCacheList.clear();
        lastLoadTime = 0;
    }

    private static boolean loadFromProvider(Context context) {
        if (context == null) return false;

        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = Uri.parse(PROVIDER_URI);

            cursor = resolver.query(uri, null, null, null, null);
            if (cursor == null) {
                XposedBridge.log("[" + TAG + "] HotSites: Provider returned null cursor");
                return false;
            }

            memoryCacheList.clear();

            // 读取模块启用状态
            int enabledIndex = cursor.getColumnIndex("moduleEnabled");
            if (enabledIndex >= 0 && cursor.moveToFirst()) {
                moduleEnabled = cursor.getInt(enabledIndex) == 1;
                cursor.moveToPrevious();
            }

            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                String url = cursor.getString(cursor.getColumnIndexOrThrow("url"));
                String iconUrl = cursor.getString(cursor.getColumnIndexOrThrow("iconUrl"));
                boolean enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1;
                int order = cursor.getInt(cursor.getColumnIndexOrThrow("order"));

                memoryCacheList.add(new SiteConfig(id, name, url, iconUrl, enabled, order));
            }

            // 按 order 排序
            Collections.sort(memoryCacheList, Comparator.comparingInt(a -> a.order));

            XposedBridge.log("[" + TAG + "] HotSites: Loaded " + memoryCacheList.size() + " sites from provider");
            return true;

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSites: Provider load failed: " + t.getMessage());
            return false;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private static void saveToLocalCache(Context context) {
        if (context == null || memoryCacheList.isEmpty()) return;

        try {
            SharedPreferences sp = context.getSharedPreferences(LOCAL_CACHE_PREF, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();

            editor.clear();
            editor.putBoolean("module_enabled", moduleEnabled);
            editor.putInt("site_count", memoryCacheList.size());

            for (int i = 0; i < memoryCacheList.size(); i++) {
                SiteConfig config = memoryCacheList.get(i);
                String prefix = "site_" + i + "_";
                editor.putLong(prefix + "id", config.id);
                editor.putString(prefix + "name", config.name);
                editor.putString(prefix + "url", config.url);
                editor.putString(prefix + "iconUrl", config.iconUrl);
                editor.putBoolean(prefix + "enabled", config.enabled);
                editor.putInt(prefix + "order", config.order);
            }

            editor.apply();

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSites: Save to local cache failed: " + t.getMessage());
        }
    }

    private static boolean loadFromLocalCache(Context context) {
        if (context == null) return false;

        try {
            SharedPreferences sp = context.getSharedPreferences(LOCAL_CACHE_PREF, Context.MODE_PRIVATE);
            int count = sp.getInt("site_count", 0);

            if (count == 0) return false;

            memoryCacheList.clear();
            moduleEnabled = sp.getBoolean("module_enabled", true);

            for (int i = 0; i < count; i++) {
                String prefix = "site_" + i + "_";
                long id = sp.getLong(prefix + "id", 0);
                if (id == 0) continue;

                SiteConfig config = new SiteConfig();
                config.id = id;
                config.name = sp.getString(prefix + "name", "");
                config.url = sp.getString(prefix + "url", "");
                config.iconUrl = sp.getString(prefix + "iconUrl", "");
                config.enabled = sp.getBoolean(prefix + "enabled", true);
                config.order = sp.getInt(prefix + "order", i);

                memoryCacheList.add(config);
            }

            Collections.sort(memoryCacheList, Comparator.comparingInt(a -> a.order));
            return !memoryCacheList.isEmpty();

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSites: Load from local cache failed: " + t.getMessage());
            return false;
        }
    }
}