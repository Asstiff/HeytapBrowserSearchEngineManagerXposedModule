package com.example.xposedsearch;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 配置管理：
 * - App 端：保存到 SharedPreferences
 * - Xposed 端：优先通过 ContentProvider 读取，失败则用默认值
 */
public class ConfigManager {

    private static final String TAG = "XposedSearch";

    public static final String PREF_NAME = "xposed_search_engines";
    private static final String KEY_ENGINES = "engines";

    private static final String MODULE_PACKAGE = "com.example.xposedsearch";
    private static final String AUTHORITY = "com.example.xposedsearch.engines";
    private static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/engines");

    // ------------------------- App 侧：读写配置 -------------------------

    /**
     * App 内读取配置（UI 用）
     */
    public static List<SearchEngineConfig> loadEngines(Context context) {
        if (context == null) {
            return getDefaultEngines();
        }

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_ENGINES, null);

        if (json == null || json.isEmpty()) {
            Log.d(TAG, "[APP] loadEngines json=null, use default");
            return getDefaultEngines();
        }

        List<SearchEngineConfig> list = fromJson(json);
        if (list == null || list.isEmpty()) {
            Log.d(TAG, "[APP] loadEngines parse empty, use default");
            return getDefaultEngines();
        }

        Log.d(TAG, "[APP] loadEngines size=" + list.size());
        return list;
    }

    /**
     * App 内保存配置
     */
    public static void saveEngines(Context context, List<SearchEngineConfig> list) {
        if (context == null) {
            return;
        }
        if (list == null) {
            list = new ArrayList<>();
        }

        String json = toJson(list);
        Log.d(TAG, "[APP] saveEngines json length=" + json.length());

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean ok = sp.edit().putString(KEY_ENGINES, json).commit();
        Log.d(TAG, "[APP] save ok=" + ok);
    }

    // ------------------------- Xposed 侧：读取配置 -------------------------

    /**
     * Xposed 侧通过 ContentProvider 读取配置
     * @param context 浏览器的 Context
     */
    public static List<SearchEngineConfig> loadEnginesViaProvider(Context context) {
        if (context == null) {
            XposedBridge.log("XposedSearch: [Provider] context is null, using defaults");
            return getDefaultEngines();
        }

        List<SearchEngineConfig> list = new ArrayList<>();
        Cursor cursor = null;

        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(CONTENT_URI, null, null, null, null);

            if (cursor == null) {
                XposedBridge.log("XposedSearch: [Provider] cursor is null");
                return getDefaultEngines();
            }

            int keyIdx = cursor.getColumnIndex("key");
            int nameIdx = cursor.getColumnIndex("name");
            int urlIdx = cursor.getColumnIndex("searchUrl");
            int enabledIdx = cursor.getColumnIndex("enabled");

            while (cursor.moveToNext()) {
                SearchEngineConfig cfg = new SearchEngineConfig();
                cfg.key = cursor.getString(keyIdx);
                cfg.name = cursor.getString(nameIdx);
                cfg.searchUrl = cursor.getString(urlIdx);
                cfg.enabled = cursor.getInt(enabledIdx) == 1;
                list.add(cfg);
            }

            XposedBridge.log("XposedSearch: [Provider] loaded " + list.size() + " engines");

        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: [Provider] query failed: " + t.getMessage());
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }

        if (list.isEmpty()) {
            XposedBridge.log("XposedSearch: [Provider] empty result, using defaults");
            return getDefaultEngines();
        }

        return list;
    }

    /**
     * Xposed 侧读取配置（无 Context 版本，尝试多种方式）
     */
    public static List<SearchEngineConfig> loadEnginesForXposed() {
        String json = null;

        // 方式1：使用 XSharedPreferences
        try {
            XSharedPreferences xp = new XSharedPreferences(MODULE_PACKAGE, PREF_NAME);
            xp.makeWorldReadable();

            File file = xp.getFile();
            if (file != null && file.exists() && file.canRead()) {
                json = xp.getString(KEY_ENGINES, null);
                XposedBridge.log("XposedSearch: [XSP] read success, json len=" +
                        (json == null ? 0 : json.length()));
            } else {
                XposedBridge.log("XposedSearch: [XSP] file not readable");
            }
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: [XSP] error: " + t.getMessage());
        }

        // 方式2：直接读取文件
        if (json == null || json.isEmpty()) {
            json = readPrefsFileDirectly();
        }

        // 解析 JSON
        if (json != null && !json.isEmpty()) {
            List<SearchEngineConfig> list = fromJson(json);
            if (list != null && !list.isEmpty()) {
                XposedBridge.log("XposedSearch: loaded " + list.size() + " engines from file");
                return list;
            }
        }

        // 返回默认配置
        XposedBridge.log("XposedSearch: file methods failed, using defaults");
        return getDefaultEngines();
    }

    /**
     * 直接读取 SharedPreferences XML 文件
     */
    private static String readPrefsFileDirectly() {
        String[] paths = {
                "/data/data/" + MODULE_PACKAGE + "/shared_prefs/" + PREF_NAME + ".xml",
                "/data/user/0/" + MODULE_PACKAGE + "/shared_prefs/" + PREF_NAME + ".xml",
                "/data/user_de/0/" + MODULE_PACKAGE + "/shared_prefs/" + PREF_NAME + ".xml"
        };

        for (String path : paths) {
            try {
                File file = new File(path);
                if (!file.exists()) {
                    continue;
                }
                if (!file.canRead()) {
                    XposedBridge.log("XposedSearch: [file] exists but cannot read: " + path);
                    continue;
                }

                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                String content = sb.toString();

                String searchKey = "name=\"" + KEY_ENGINES + "\"";
                int keyIndex = content.indexOf(searchKey);
                if (keyIndex == -1) {
                    continue;
                }

                int startTag = content.indexOf(">", keyIndex);
                if (startTag == -1) continue;
                startTag++;

                int endTag = content.indexOf("</string>", startTag);
                if (endTag == -1) continue;

                String json = content.substring(startTag, endTag);
                json = json.replace("&quot;", "\"")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&apos;", "'")
                        .replace("&amp;", "&");

                XposedBridge.log("XposedSearch: [file] read success from " + path);
                return json;

            } catch (Throwable t) {
                XposedBridge.log("XposedSearch: [file] read error: " + t.getMessage());
            }
        }

        return null;
    }

    // ------------------------- 公共工具方法 -------------------------

    public static SearchEngineConfig findByKey(List<SearchEngineConfig> list, String key) {
        if (list == null || key == null) return null;
        for (SearchEngineConfig cfg : list) {
            if (key.equals(cfg.key)) {
                return cfg;
            }
        }
        return null;
    }

    public static String toJson(List<SearchEngineConfig> list) {
        JSONArray array = new JSONArray();
        if (list != null) {
            for (SearchEngineConfig cfg : list) {
                if (cfg == null) continue;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("key", cfg.key);
                    obj.put("name", cfg.name);
                    obj.put("searchUrl", cfg.searchUrl);
                    obj.put("enabled", cfg.enabled);
                    array.put(obj);
                } catch (JSONException ignored) {
                }
            }
        }
        return array.toString();
    }

    public static List<SearchEngineConfig> fromJson(String json) {
        List<SearchEngineConfig> list = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                SearchEngineConfig cfg = new SearchEngineConfig();
                cfg.key = obj.optString("key", "");
                cfg.name = obj.optString("name", "");
                cfg.searchUrl = obj.optString("searchUrl", "");
                cfg.enabled = obj.optBoolean("enabled", true);
                if (!cfg.key.isEmpty()) {
                    list.add(cfg);
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    /**
     * 默认配置 - 包含内置引擎和自定义引擎
     */
    public static List<SearchEngineConfig> getDefaultEngines() {
        List<SearchEngineConfig> list = new ArrayList<>();

        // 内置引擎（浏览器自带的）
        list.add(new SearchEngineConfig(
                "baidu", "百度",
                "", // 内置引擎不需要 URL
                true
        ));
        list.add(new SearchEngineConfig(
                "shenma", "神马",
                "",
                false // 默认禁用
        ));
        list.add(new SearchEngineConfig(
                "sogou", "搜狗",
                "",
                false // 默认禁用
        ));

        // 自定义引擎（需要 URL）
        list.add(new SearchEngineConfig(
                "bing", "必应",
                "https://cn.bing.com/search?q={searchTerms}",
                true
        ));
        list.add(new SearchEngineConfig(
                "google", "Google",
                "https://www.google.com/search?q={searchTerms}",
                false
        ));

        return list;
    }
}