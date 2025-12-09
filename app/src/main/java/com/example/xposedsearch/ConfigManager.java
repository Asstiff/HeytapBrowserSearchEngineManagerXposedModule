package com.example.xposedsearch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ConfigManager {

    private static final String TAG = "XposedSearch";
    public static final String PREF_NAME = "xposed_search_engines";
    private static final String KEY_ENGINES = "engines";

    // ------------------------- 读写配置 -------------------------

    public static List<SearchEngineConfig> loadEngines(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_ENGINES, null);

        if (json == null || json.isEmpty()) {
            Log.d(TAG, "[APP] loadEngines json=null, return empty");
            return new ArrayList<>();
        }

        List<SearchEngineConfig> list = fromJson(json);
        Log.d(TAG, "[APP] loadEngines size=" + list.size());
        return list;
    }

    public static void saveEngines(Context context, List<SearchEngineConfig> list) {
        if (context == null) return;
        if (list == null) list = new ArrayList<>();

        String json = toJson(list);
        Log.d(TAG, "[APP] saveEngines size=" + list.size());

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_ENGINES, json).apply();
    }

    // ------------------------- 引擎发现与同步 -------------------------

    /**
     * 处理从浏览器发现的引擎
     * @return true 表示有更新，false 表示无变化
     */
    public static boolean handleDiscoveredEngine(Context context, String key, String name, String searchUrl) {
        if (context == null || key == null || key.isEmpty()) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig existing = findByKey(engines, key);

        if (existing == null) {
            // 新引擎，添加为内置引擎
            SearchEngineConfig newEngine = new SearchEngineConfig();
            newEngine.key = key;
            newEngine.name = name != null ? name : key;
            newEngine.searchUrl = searchUrl != null ? searchUrl : "";
            newEngine.enabled = true;  // 默认启用
            newEngine.isBuiltin = true;
            newEngine.isModified = false;
            newEngine.originalName = newEngine.name;
            newEngine.originalSearchUrl = newEngine.searchUrl;

            engines.add(newEngine);
            saveEngines(context, engines);
            Log.d(TAG, "[APP] discovered new engine: " + key + " (" + name + ")");
            return true;

        } else if (existing.isBuiltin && !existing.isModified) {
            // 已存在的内置引擎，用户未修改过，检查是否需要更新
            boolean needUpdate = false;

            if (name != null && !name.equals(existing.originalName)) {
                needUpdate = true;
            }
            if (searchUrl != null && !searchUrl.isEmpty() && !searchUrl.equals(existing.originalSearchUrl)) {
                needUpdate = true;
            }

            if (needUpdate) {
                existing.name = name != null ? name : existing.name;
                existing.searchUrl = (searchUrl != null && !searchUrl.isEmpty()) ? searchUrl : existing.searchUrl;
                existing.originalName = existing.name;
                existing.originalSearchUrl = existing.searchUrl;
                saveEngines(context, engines);
                Log.d(TAG, "[APP] updated engine from browser: " + key);
                return true;
            }
        }
        // 用户已修改过，不更新
        return false;
    }

    /**
     * 恢复引擎为默认设置
     */
    public static boolean resetEngine(Context context, String key) {
        if (context == null || key == null) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.canReset()) return false;

        engine.name = engine.originalName != null ? engine.originalName : engine.name;
        engine.searchUrl = engine.originalSearchUrl != null ? engine.originalSearchUrl : engine.searchUrl;
        engine.enabled = true;
        engine.isModified = false;

        saveEngines(context, engines);
        Log.d(TAG, "[APP] reset engine: " + key);
        return true;
    }

    /**
     * 用户修改引擎（标记为已修改）
     */
    public static void updateEngineByUser(Context context, String key, String name, String searchUrl, boolean enabled) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null) return;

        engine.name = name;
        engine.searchUrl = searchUrl;
        engine.enabled = enabled;
        engine.isModified = true;

        saveEngines(context, engines);
    }

    /**
     * 仅更新启用状态
     */
    public static void updateEngineEnabled(Context context, String key, boolean enabled) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null) return;

        engine.enabled = enabled;
        saveEngines(context, engines);
    }

    /**
     * 添加用户自定义引擎
     */
    public static boolean addCustomEngine(Context context, String key, String name, String searchUrl) {
        if (context == null || key == null || key.isEmpty()) return false;

        List<SearchEngineConfig> engines = loadEngines(context);

        // 检查 key 是否已存在
        if (findByKey(engines, key) != null) {
            return false;
        }

        SearchEngineConfig newEngine = new SearchEngineConfig();
        newEngine.key = key;
        newEngine.name = name;
        newEngine.searchUrl = searchUrl;
        newEngine.enabled = true;
        newEngine.isBuiltin = false;
        newEngine.isModified = false;
        newEngine.originalName = null;
        newEngine.originalSearchUrl = null;

        engines.add(newEngine);
        saveEngines(context, engines);
        Log.d(TAG, "[APP] added custom engine: " + key);
        return true;
    }

    /**
     * 删除引擎（只能删除用户自定义引擎）
     */
    public static boolean deleteEngine(Context context, String key) {
        if (context == null || key == null) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.canDelete()) {
            return false;
        }

        engines.remove(engine);
        saveEngines(context, engines);
        Log.d(TAG, "[APP] deleted engine: " + key);
        return true;
    }

    // ------------------------- 工具方法 -------------------------

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
                    obj.put("isBuiltin", cfg.isBuiltin);
                    obj.put("isModified", cfg.isModified);
                    if (cfg.originalName != null) {
                        obj.put("originalName", cfg.originalName);
                    }
                    if (cfg.originalSearchUrl != null) {
                        obj.put("originalSearchUrl", cfg.originalSearchUrl);
                    }
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
                cfg.isBuiltin = obj.optBoolean("isBuiltin", false);
                cfg.isModified = obj.optBoolean("isModified", false);
                cfg.originalName = obj.has("originalName") ? obj.optString("originalName") : null;
                cfg.originalSearchUrl = obj.has("originalSearchUrl") ? obj.optString("originalSearchUrl") : null;

                if (!cfg.key.isEmpty()) {
                    list.add(cfg);
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }
}