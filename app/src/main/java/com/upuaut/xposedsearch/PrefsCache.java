// PrefsCache.java
package com.upuaut.xposedsearch;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Xposed 侧的配置缓存
 * 优先级：ContentProvider > 内存缓存 > 本地 SharedPreferences 缓存
 */
public class PrefsCache {

    private static final String TAG = "XposedSearch";
    private static final String PROVIDER_URI = "content://com.upuaut.xposedsearch.provider/engines";
    private static final String LOCAL_CACHE_PREF = "xposed_search_cache";

    // 内存缓存
    private static final Map<String, EngineConfig> memoryCache = new ConcurrentHashMap<>();

    // 缓存有效期
    private static final long CACHE_TTL_MS = 5000L;
    private static long lastLoadTime = 0L;

    public static class EngineConfig {
        public String key;
        public String name;
        public String searchUrl;
        public boolean enabled;
        public boolean isBuiltin;
        public boolean isRemovedFromBrowser;
        public boolean hasBuiltinConflict;

        public EngineConfig() {}

        public EngineConfig(String key, String name, String searchUrl, boolean enabled,
                            boolean isBuiltin, boolean isRemovedFromBrowser, boolean hasBuiltinConflict) {
            this.key = key;
            this.name = name;
            this.searchUrl = searchUrl;
            this.enabled = enabled;
            this.isBuiltin = isBuiltin;
            this.isRemovedFromBrowser = isRemovedFromBrowser;
            this.hasBuiltinConflict = hasBuiltinConflict;
        }
    }

    /**
     * 获取所有引擎配置
     */
    public static Map<String, EngineConfig> getEngineConfigs(Context context) {
        long now = System.currentTimeMillis();

        // 检查缓存是否过期
        if (now - lastLoadTime < CACHE_TTL_MS && !memoryCache.isEmpty()) {
            return memoryCache;
        }

        // 1. 尝试从 ContentProvider 加载
        if (loadFromProvider(context)) {
            lastLoadTime = now;
            saveToLocalCache(context);
            return memoryCache;
        }

        // 2. Provider 失败，如果内存缓存有数据就用内存缓存
        if (!memoryCache.isEmpty()) {
            XposedBridge.log("[" + TAG + "] Using memory cache");
            return memoryCache;
        }

        // 3. 内存缓存也没有，尝试从本地缓存加载
        if (loadFromLocalCache(context)) {
            XposedBridge.log("[" + TAG + "] Loaded from local cache");
            return memoryCache;
        }

        // 都没有，返回空
        return memoryCache;
    }

    /**
     * 获取单个引擎配置
     */
    public static EngineConfig getEngineConfig(Context context, String key) {
        Map<String, EngineConfig> configs = getEngineConfigs(context);
        return configs.get(key);
    }

    /**
     * 强制刷新缓存
     */
    public static void refresh(Context context) {
        lastLoadTime = 0;
        getEngineConfigs(context);
    }

    /**
     * 清除内存缓存
     */
    public static void clearMemoryCache() {
        memoryCache.clear();
        lastLoadTime = 0;
    }

    /**
     * 从 ContentProvider 加载
     */
    private static boolean loadFromProvider(Context context) {
        if (context == null) return false;

        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = Uri.parse(PROVIDER_URI);

            cursor = resolver.query(uri, null, null, null, null);
            if (cursor == null) {
                XposedBridge.log("[" + TAG + "] Provider returned null cursor");
                return false;
            }

            memoryCache.clear();

            while (cursor.moveToNext()) {
                String key = cursor.getString(cursor.getColumnIndexOrThrow("key"));
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                String searchUrl = cursor.getString(cursor.getColumnIndexOrThrow("searchUrl"));
                boolean enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1;
                boolean isBuiltin = cursor.getInt(cursor.getColumnIndexOrThrow("isBuiltin")) == 1;
                boolean isRemoved = cursor.getInt(cursor.getColumnIndexOrThrow("isRemovedFromBrowser")) == 1;
                boolean hasConflict = cursor.getInt(cursor.getColumnIndexOrThrow("hasBuiltinConflict")) == 1;

                memoryCache.put(key, new EngineConfig(key, name, searchUrl, enabled,
                        isBuiltin, isRemoved, hasConflict));
            }

            XposedBridge.log("[" + TAG + "] Loaded " + memoryCache.size() + " engines from provider");
            return true;

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Provider load failed: " + t.getMessage());
            return false;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 保存到本地缓存（目标进程的 SharedPreferences）
     */
    private static void saveToLocalCache(Context context) {
        if (context == null || memoryCache.isEmpty()) return;

        try {
            SharedPreferences sp = context.getSharedPreferences(LOCAL_CACHE_PREF, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();

            // 清空旧数据
            editor.clear();

            // 保存引擎数量
            editor.putInt("engine_count", memoryCache.size());

            // 保存每个引擎
            int index = 0;
            for (EngineConfig config : memoryCache.values()) {
                String prefix = "engine_" + index + "_";
                editor.putString(prefix + "key", config.key);
                editor.putString(prefix + "name", config.name);
                editor.putString(prefix + "searchUrl", config.searchUrl);
                editor.putBoolean(prefix + "enabled", config.enabled);
                editor.putBoolean(prefix + "isBuiltin", config.isBuiltin);
                editor.putBoolean(prefix + "isRemovedFromBrowser", config.isRemovedFromBrowser);
                editor.putBoolean(prefix + "hasBuiltinConflict", config.hasBuiltinConflict);
                index++;
            }

            editor.apply();
            XposedBridge.log("[" + TAG + "] Saved " + memoryCache.size() + " engines to local cache");

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Save to local cache failed: " + t.getMessage());
        }
    }

    /**
     * 从本地缓存加载
     */
    private static boolean loadFromLocalCache(Context context) {
        if (context == null) return false;

        try {
            SharedPreferences sp = context.getSharedPreferences(LOCAL_CACHE_PREF, Context.MODE_PRIVATE);
            int count = sp.getInt("engine_count", 0);

            if (count == 0) return false;

            memoryCache.clear();

            for (int i = 0; i < count; i++) {
                String prefix = "engine_" + i + "_";
                String key = sp.getString(prefix + "key", null);
                if (key == null) continue;

                EngineConfig config = new EngineConfig();
                config.key = key;
                config.name = sp.getString(prefix + "name", key);
                config.searchUrl = sp.getString(prefix + "searchUrl", "");
                config.enabled = sp.getBoolean(prefix + "enabled", true);
                config.isBuiltin = sp.getBoolean(prefix + "isBuiltin", false);
                config.isRemovedFromBrowser = sp.getBoolean(prefix + "isRemovedFromBrowser", false);
                config.hasBuiltinConflict = sp.getBoolean(prefix + "hasBuiltinConflict", false);

                memoryCache.put(key, config);
            }

            return !memoryCache.isEmpty();

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Load from local cache failed: " + t.getMessage());
            return false;
        }
    }
}