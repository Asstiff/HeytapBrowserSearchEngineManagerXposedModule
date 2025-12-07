package com.example.xposedsearch;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

public class ConfigManager {

    static final String PREF_NAME   = "xposed_search_engines";
    static final String KEY_ENGINES = "engines";
    private static final String TAG = "XposedSearch";

    private static final String PROVIDER_AUTHORITY = "com.example.xposedsearch.engines";
    private static final String PROVIDER_PATH      = "engines";
    private static final Uri   PROVIDER_URI        =
            Uri.parse("content://" + PROVIDER_AUTHORITY + "/" + PROVIDER_PATH);

    public static List<SearchEngineConfig> loadEngines(Context context) {
        SharedPreferences sp =
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_ENGINES, null);

        Log.d(TAG, "[APP] loadEngines json=" + (json != null ? json : "null"));

        if (json == null || json.isEmpty()) {
            return createDefaultEngines();
        }
        return parseEnginesJson(json);
    }

    public static void saveEngines(Context context, List<SearchEngineConfig> list) {
        String json = toJson(list);

        SharedPreferences sp =
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean ok = sp.edit().putString(KEY_ENGINES, json).commit();

        Log.d(TAG, "[APP] saveEngines commitOk=" + ok + ", jsonLength=" + json.length());
    }

    public static String toJson(List<SearchEngineConfig> list) {
        JSONArray array = new JSONArray();
        if (list != null) {
            for (SearchEngineConfig cfg : list) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("key", cfg.key);
                    obj.put("name", cfg.name);
                    obj.put("searchUrl", cfg.searchUrl);
                    obj.put("enabled", cfg.enabled);
                    array.put(obj);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        return array.toString();
    }

    public static List<SearchEngineConfig> loadEnginesForXposed() {
        try {
            Context ctx = getAnyApplicationContext();
            if (ctx == null) {
                XposedBridge.log("XposedSearch: loadEnginesForXposed getAnyApplicationContext() is null");
            } else {
                List<SearchEngineConfig> list = loadFromProvider(ctx);
                if (list != null && !list.isEmpty()) {
                    XposedBridge.log("XposedSearch: loadEnginesForXposed from ContentProvider, size=" + list.size());
                    return list;
                } else {
                    XposedBridge.log("XposedSearch: loadEnginesForXposed provider returned empty, fallback to default");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: loadEnginesForXposed error: " + t);
        }

        XposedBridge.log("XposedSearch: loadEnginesForXposed use default config");
        return createDefaultEngines();
    }

    private static Context getAnyApplicationContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: getAnyApplicationContext error: " + t);
        }
        return null;
    }
    private static List<SearchEngineConfig> loadFromProvider(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver()
                    .query(PROVIDER_URI, null, null, null, null);

            if (cursor == null) {
                XposedBridge.log("XposedSearch: loadFromProvider cursor is null");
                return null;
            }
            if (!cursor.moveToFirst()) {
                XposedBridge.log("XposedSearch: loadFromProvider cursor empty");
                return null;
            }

            int index = cursor.getColumnIndex("json");
            if (index < 0) {
                XposedBridge.log("XposedSearch: loadFromProvider 'json' column missing");
                return null;
            }

            String json = cursor.getString(index);
            if (json == null || json.isEmpty()) {
                XposedBridge.log("XposedSearch: loadFromProvider json empty");
                return null;
            }

            return parseEnginesJson(json);
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: loadFromProvider error: " + t);
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {}
            }
        }
    }
    public static SearchEngineConfig findByKey(List<SearchEngineConfig> list, String key) {
        if (key == null || list == null) return null;
        for (SearchEngineConfig cfg : list) {
            if (key.equals(cfg.key)) return cfg;
        }
        return null;
    }
    private static List<SearchEngineConfig> createDefaultEngines() {
        List<SearchEngineConfig> list = new ArrayList<>();
        list.add(new SearchEngineConfig(
                "baidu", "百度",
                "https://m.baidu.com/s?from=1020681k&word={searchTerms}",
                true
        ));
        list.add(new SearchEngineConfig(
                "360", "360 搜索",
                "https://m.so.com/s?q={searchTerms}&src=home&srcg=cs_oppo_1",
                true
        ));
        list.add(new SearchEngineConfig(
                "douyin", "抖音搜索",
                "https://so.douyin.com/s?keyword={searchTerms}&query_correct_type=1" +
                        "&traffic_source=CS1113&original_source=1&in_tfs=OP&in_ogs=1" +
                        "&search_entrance=oppo&enter_method=normal_search",
                true
        ));
        list.add(new SearchEngineConfig(
                "360AI", "纳米 AI",
                "https://www.n.cn/?q={searchTerms}&src=ec_oppo_1001",
                true
        ));
        list.add(new SearchEngineConfig(
                "bing", "必应",
                "https://cn.bing.com/search?q={searchTerms}",
                true
        ));
        return list;
    }

    private static List<SearchEngineConfig> parseEnginesJson(String json) {
        List<SearchEngineConfig> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                SearchEngineConfig cfg = new SearchEngineConfig();
                cfg.key       = obj.optString("key");
                cfg.name      = obj.optString("name");
                cfg.searchUrl = obj.optString("searchUrl");
                cfg.enabled   = obj.optBoolean("enabled", true);
                if (cfg.key != null && !cfg.key.isEmpty()) {
                    list.add(cfg);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        if (list.isEmpty()) {
            return createDefaultEngines();
        }
        return list;
    }
}