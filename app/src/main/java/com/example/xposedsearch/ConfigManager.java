package com.example.xposedsearch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * 现在不会直接更新未修改的引擎，而是标记更新供用户确认
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
            newEngine.enabled = true;
            newEngine.isBuiltin = true;
            newEngine.isModified = false;
            newEngine.originalName = newEngine.name;
            newEngine.originalSearchUrl = newEngine.searchUrl;
            newEngine.isRemovedFromBrowser = false;
            newEngine.hasUpdate = false;

            engines.add(newEngine);
            saveEngines(context, engines);
            Log.d(TAG, "[APP] discovered new engine: " + key + " (" + name + ")");
            return true;

        } else if (existing.isBuiltin) {
            boolean changed = false;

            // 如果之前被标记为已消失，现在又出现了，清除标记
            if (existing.isRemovedFromBrowser) {
                existing.isRemovedFromBrowser = false;
                changed = true;
                Log.d(TAG, "[APP] engine reappeared: " + key);
            }

            // 检查是否有更新
            boolean nameChanged = name != null && !name.equals(existing.originalName);
            boolean urlChanged = searchUrl != null && !searchUrl.isEmpty()
                    && !searchUrl.equals(existing.originalSearchUrl);

            if (nameChanged || urlChanged) {
                if (existing.isModified) {
                    // 用户已修改过，静默更新原始信息，但保持用户的修改
                    existing.originalName = name != null ? name : existing.originalName;
                    existing.originalSearchUrl = (searchUrl != null && !searchUrl.isEmpty())
                            ? searchUrl : existing.originalSearchUrl;
                    existing.hasUpdate = false;
                    existing.pendingName = null;
                    existing.pendingSearchUrl = null;
                    changed = true;
                    Log.d(TAG, "[APP] silently updated original info for modified engine: " + key);
                } else {
                    // 用户未修改过，标记为有更新待确认
                    existing.hasUpdate = true;
                    existing.pendingName = nameChanged ? name : null;
                    existing.pendingSearchUrl = urlChanged ? searchUrl : null;
                    changed = true;
                    Log.d(TAG, "[APP] marked update available for engine: " + key);
                }
            }

            if (changed) {
                saveEngines(context, engines);
                return true;
            }
        }

        return false;
    }

    /**
     * 标记未被发现的内置引擎为已消失
     * @param discoveredKeys 本次发现的所有引擎 key
     */
    public static void markMissingEnginesAsRemoved(Context context, Set<String> discoveredKeys) {
        if (context == null || discoveredKeys == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        boolean changed = false;

        for (SearchEngineConfig engine : engines) {
            if (engine.isBuiltin && !discoveredKeys.contains(engine.key)) {
                if (!engine.isRemovedFromBrowser) {
                    engine.isRemovedFromBrowser = true;
                    changed = true;
                    Log.d(TAG, "[APP] marked engine as removed: " + engine.key);
                }
            }
        }

        if (changed) {
            saveEngines(context, engines);
        }
    }

    /**
     * 应用待更新的信息
     */
    public static void applyPendingUpdate(Context context, String key) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.hasUpdate) return;

        // 应用更新
        if (engine.pendingName != null) {
            engine.name = engine.pendingName;
            engine.originalName = engine.pendingName;
        }
        if (engine.pendingSearchUrl != null) {
            engine.searchUrl = engine.pendingSearchUrl;
            engine.originalSearchUrl = engine.pendingSearchUrl;
        }

        // 清除更新标记
        engine.hasUpdate = false;
        engine.pendingName = null;
        engine.pendingSearchUrl = null;

        saveEngines(context, engines);
        Log.d(TAG, "[APP] applied pending update for engine: " + key);
    }

    /**
     * 忽略待更新（保持当前状态，但将待更新信息设为新的原始信息）
     */
    public static void ignorePendingUpdate(Context context, String key) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.hasUpdate) return;

        // 更新原始信息但保持当前显示信息
        if (engine.pendingName != null) {
            engine.originalName = engine.pendingName;
        }
        if (engine.pendingSearchUrl != null) {
            engine.originalSearchUrl = engine.pendingSearchUrl;
        }

        // 标记为已修改（因为用户选择保持当前信息）
        engine.isModified = true;
        engine.hasUpdate = false;
        engine.pendingName = null;
        engine.pendingSearchUrl = null;

        saveEngines(context, engines);
        Log.d(TAG, "[APP] ignored pending update for engine: " + key);
    }

    /**
     * 将内置引擎转换为自定义引擎
     */
    public static void convertToCustomEngine(Context context, String key) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.isBuiltin) return;

        engine.isBuiltin = false;
        engine.isModified = false;
        engine.isRemovedFromBrowser = false;
        engine.hasUpdate = false;
        engine.originalName = null;
        engine.originalSearchUrl = null;
        engine.pendingName = null;
        engine.pendingSearchUrl = null;

        saveEngines(context, engines);
        Log.d(TAG, "[APP] converted to custom engine: " + key);
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
     * 用户修改内置引擎（标记为已修改，不能改 key）
     */
    public static void updateEngineByUser(Context context, String key, String name, String searchUrl, boolean enabled) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null) return;

        engine.name = name;
        engine.searchUrl = searchUrl;
        engine.enabled = enabled;

        // 只有内置引擎需要标记为已修改
        if (engine.isBuiltin) {
            engine.isModified = true;
        }

        saveEngines(context, engines);
        Log.d(TAG, "[APP] updated engine by user: " + key);
    }

    /**
     * 更新自定义引擎（可修改 key）
     * @return true 成功，false 表示新 key 已存在冲突
     */
    public static boolean updateCustomEngineWithKey(Context context, String oldKey, String newKey, String name, String searchUrl, boolean enabled) {
        if (context == null || oldKey == null || newKey == null || newKey.isEmpty()) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, oldKey);

        if (engine == null || engine.isBuiltin) return false;

        // 如果 key 没变，直接更新
        if (oldKey.equals(newKey)) {
            engine.name = name;
            engine.searchUrl = searchUrl;
            engine.enabled = enabled;
            saveEngines(context, engines);
            Log.d(TAG, "[APP] updated custom engine: " + oldKey);
            return true;
        }

        // 如果 key 变了，检查新 key 是否冲突
        if (findByKey(engines, newKey) != null) {
            Log.d(TAG, "[APP] key conflict: " + newKey);
            return false; // 新 key 已存在
        }

        // 更新 key 和其他字段
        engine.key = newKey;
        engine.name = name;
        engine.searchUrl = searchUrl;
        engine.enabled = enabled;

        saveEngines(context, engines);
        Log.d(TAG, "[APP] updated custom engine key: " + oldKey + " -> " + newKey);
        return true;
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
     * 删除引擎
     */
    public static boolean deleteEngine(Context context, String key) {
        if (context == null || key == null) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null) {
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
                    // 新增字段
                    obj.put("hasUpdate", cfg.hasUpdate);
                    if (cfg.pendingName != null) {
                        obj.put("pendingName", cfg.pendingName);
                    }
                    if (cfg.pendingSearchUrl != null) {
                        obj.put("pendingSearchUrl", cfg.pendingSearchUrl);
                    }
                    obj.put("isRemovedFromBrowser", cfg.isRemovedFromBrowser);
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
                // 新增字段
                cfg.hasUpdate = obj.optBoolean("hasUpdate", false);
                cfg.pendingName = obj.has("pendingName") ? obj.optString("pendingName") : null;
                cfg.pendingSearchUrl = obj.has("pendingSearchUrl") ? obj.optString("pendingSearchUrl") : null;
                cfg.isRemovedFromBrowser = obj.optBoolean("isRemovedFromBrowser", false);

                if (!cfg.key.isEmpty()) {
                    list.add(cfg);
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }
}