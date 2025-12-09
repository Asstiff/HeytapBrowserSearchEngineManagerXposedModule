package com.upuaut.xposedsearch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ConfigManager {

    private static final String TAG = "XposedSearch";
    public static final String PREF_NAME = "xposed_search_engines";
    private static final String KEY_ENGINES = "engines";

    /**
     * 使用 MODE_WORLD_READABLE 以便 Xposed hook 可以通过 XSharedPreferences 读取配置
     * 
     * 安全说明:
     * - 此文件包含搜索引擎配置（引擎名称、搜索 URL、启用状态）
     * - 不包含任何敏感信息（如密码、令牌等）
     * - 这是 Xposed 模块跨进程共享配置的标准做法
     * - 虽然此模式已被标记为 deprecated，但对于 Xposed 模块来说是必需的
     */
    @SuppressWarnings("deprecation")
    private static final int PREFS_MODE = Context.MODE_WORLD_READABLE;

    // ------------------------- 读写配置 -------------------------

    @SuppressWarnings("deprecation")
    public static List<SearchEngineConfig> loadEngines(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, PREFS_MODE);
        String json = sp.getString(KEY_ENGINES, null);

        if (json == null || json.isEmpty()) {
            Log.d(TAG, "[APP] loadEngines json=null, return empty");
            return new ArrayList<>();
        }

        List<SearchEngineConfig> list = fromJson(json);
        Log.d(TAG, "[APP] loadEngines size=" + list.size());
        return list;
    }

    @SuppressWarnings("deprecation")
    public static void saveEngines(Context context, List<SearchEngineConfig> list) {
        if (context == null) return;
        if (list == null) list = new ArrayList<>();

        String json = toJson(list);
        Log.d(TAG, "[APP] saveEngines size=" + list.size());

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, PREFS_MODE);
        sp.edit().putString(KEY_ENGINES, json).commit(); // 使用 commit() 而非 apply() 确保立即写入
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
            // 已存在的内置引擎
            boolean changed = false;

            // 如果之前被标记为已消失，现在又出现了，清除标记
            if (existing.isRemovedFromBrowser) {
                existing.isRemovedFromBrowser = false;
                changed = true;
                Log.d(TAG, "[APP] engine reappeared: " + key);
            }

            // 检查是否有更新（与原始值比较）
            boolean nameChanged = name != null && !name.equals(existing.originalName);
            boolean urlChanged = searchUrl != null && !searchUrl.isEmpty()
                    && !searchUrl.equals(existing.originalSearchUrl);

            if (nameChanged || urlChanged) {
                if (existing.isModified) {
                    // 用户已修改过，静默更新原始信息，但保持用户的修改
                    if (name != null) {
                        existing.originalName = name;
                    }
                    if (searchUrl != null && !searchUrl.isEmpty()) {
                        existing.originalSearchUrl = searchUrl;
                    }
                    existing.hasUpdate = false;
                    existing.pendingName = null;
                    existing.pendingSearchUrl = null;
                    changed = true;
                    Log.d(TAG, "[APP] silently updated original info for modified engine: " + key);
                } else {
                    // 用户未修改过，标记为有更新待确认
                    existing.hasUpdate = true;
                    // 始终记录待更新的值，即使只有一个变化
                    existing.pendingName = name;
                    existing.pendingSearchUrl = searchUrl;
                    changed = true;
                    Log.d(TAG, "[APP] marked update available for engine: " + key +
                            " (name: " + existing.originalName + " -> " + name +
                            ", url changed: " + urlChanged + ")");
                }
            }

            if (changed) {
                saveEngines(context, engines);
                return true;
            }

        } else {
            // 已存在同 key 的自定义引擎，标记冲突
            if (!existing.hasBuiltinConflict) {
                existing.hasBuiltinConflict = true;
                existing.conflictBuiltinName = name;
                existing.conflictBuiltinSearchUrl = searchUrl;
                saveEngines(context, engines);
                Log.d(TAG, "[APP] custom engine conflict with new builtin: " + key);
                return true;
            } else {
                // 已经标记过冲突，更新冲突信息
                boolean changed = false;
                if (name != null && !name.equals(existing.conflictBuiltinName)) {
                    existing.conflictBuiltinName = name;
                    changed = true;
                }
                if (searchUrl != null && !searchUrl.equals(existing.conflictBuiltinSearchUrl)) {
                    existing.conflictBuiltinSearchUrl = searchUrl;
                    changed = true;
                }
                if (changed) {
                    saveEngines(context, engines);
                    return true;
                }
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

            // 如果自定义引擎之前有冲突，但内置引擎消失了，清除冲突标记
            if (!engine.isBuiltin && engine.hasBuiltinConflict && !discoveredKeys.contains(engine.key)) {
                engine.hasBuiltinConflict = false;
                engine.conflictBuiltinName = null;
                engine.conflictBuiltinSearchUrl = null;
                changed = true;
                Log.d(TAG, "[APP] cleared builtin conflict for custom engine: " + engine.key);
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

        // 应用更新 - 同时更新显示值和原始值
        if (engine.pendingName != null) {
            engine.name = engine.pendingName;
            engine.originalName = engine.pendingName;
        }
        if (engine.pendingSearchUrl != null && !engine.pendingSearchUrl.isEmpty()) {
            engine.searchUrl = engine.pendingSearchUrl;
            engine.originalSearchUrl = engine.pendingSearchUrl;
        }

        // 清除更新标记
        engine.hasUpdate = false;
        engine.pendingName = null;
        engine.pendingSearchUrl = null;
        engine.isModified = false;  // 应用更新后不再是修改状态

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
        if (engine.pendingSearchUrl != null && !engine.pendingSearchUrl.isEmpty()) {
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
     * 将自定义引擎转换为内置引擎（处理冲突情况）
     * 用户选择"转为内置"时调用
     */
    public static void convertCustomToBuiltin(Context context, String key) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || engine.isBuiltin || !engine.hasBuiltinConflict) return;

        // 转换为内置引擎，标记为已修改状态
        // 原始信息使用浏览器的内置引擎信息
        // 当前使用的信息保持用户之前的自定义内容
        engine.isBuiltin = true;
        engine.isModified = true;
        engine.originalName = engine.conflictBuiltinName;
        engine.originalSearchUrl = engine.conflictBuiltinSearchUrl;

        // 清除冲突标记
        engine.hasBuiltinConflict = false;
        engine.conflictBuiltinName = null;
        engine.conflictBuiltinSearchUrl = null;

        saveEngines(context, engines);
        Log.d(TAG, "[APP] converted custom to builtin (modified): " + key);
    }

    /**
     * 创建自定义引擎副本（处理冲突情况）
     * 用户选择"创建副本"时调用
     * @return 新引擎的 key，如果失败返回 null
     */
    public static String createCustomEngineCopy(Context context, String key) {
        if (context == null || key == null) return null;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || engine.isBuiltin || !engine.hasBuiltinConflict) return null;

        // 保存冲突的内置引擎信息
        String builtinName = engine.conflictBuiltinName;
        String builtinSearchUrl = engine.conflictBuiltinSearchUrl;

        // 为自定义引擎生成新 key
        String newKey = key + "Custom";
        int suffix = 1;
        while (findByKey(engines, newKey) != null) {
            newKey = key + "Custom" + suffix;
            suffix++;
        }

        // 更新自定义引擎的 key，清除冲突标记
        engine.key = newKey;
        engine.hasBuiltinConflict = false;
        engine.conflictBuiltinName = null;
        engine.conflictBuiltinSearchUrl = null;

        // 创建新的内置引擎（使用原来的 key 和浏览器的配置）
        SearchEngineConfig builtinEngine = new SearchEngineConfig();
        builtinEngine.key = key;
        builtinEngine.name = builtinName != null ? builtinName : key;
        builtinEngine.searchUrl = builtinSearchUrl != null ? builtinSearchUrl : "";
        builtinEngine.enabled = true;
        builtinEngine.isBuiltin = true;
        builtinEngine.isModified = false;
        builtinEngine.originalName = builtinEngine.name;
        builtinEngine.originalSearchUrl = builtinEngine.searchUrl;
        builtinEngine.isRemovedFromBrowser = false;
        builtinEngine.hasUpdate = false;

        engines.add(builtinEngine);
        saveEngines(context, engines);
        Log.d(TAG, "[APP] created custom engine copy: " + key + " -> " + newKey +
                ", builtin name: " + builtinName + ", url: " + builtinSearchUrl);
        return newKey;
    }

    /**
     * 恢复引擎为默认设置
     */
    public static boolean resetEngine(Context context, String key) {
        if (context == null || key == null) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.canReset()) return false;

        // 恢复为原始值
        if (engine.originalName != null) {
            engine.name = engine.originalName;
        }
        if (engine.originalSearchUrl != null) {
            engine.searchUrl = engine.originalSearchUrl;
        }
        engine.enabled = true;
        engine.isModified = false;

        saveEngines(context, engines);
        Log.d(TAG, "[APP] reset engine: " + key + " to name: " + engine.name + ", url: " + engine.searchUrl);
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

        // 检查是否真的有修改（对于内置引擎，与原始值比较）
        if (engine.isBuiltin) {
            boolean nameChanged = !name.equals(engine.originalName);
            boolean urlChanged = (searchUrl != null && engine.originalSearchUrl != null)
                    ? !searchUrl.equals(engine.originalSearchUrl)
                    : (searchUrl != null || engine.originalSearchUrl != null);

            engine.name = name;
            engine.searchUrl = searchUrl;
            engine.enabled = enabled;
            engine.isModified = nameChanged || urlChanged;

            Log.d(TAG, "[APP] updated builtin engine by user: " + key +
                    ", isModified: " + engine.isModified +
                    ", originalName: " + engine.originalName + ", newName: " + name);
        } else {
            engine.name = name;
            engine.searchUrl = searchUrl;
            engine.enabled = enabled;
        }

        saveEngines(context, engines);
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
                    obj.put("hasUpdate", cfg.hasUpdate);
                    if (cfg.pendingName != null) {
                        obj.put("pendingName", cfg.pendingName);
                    }
                    if (cfg.pendingSearchUrl != null) {
                        obj.put("pendingSearchUrl", cfg.pendingSearchUrl);
                    }
                    obj.put("isRemovedFromBrowser", cfg.isRemovedFromBrowser);
                    obj.put("hasBuiltinConflict", cfg.hasBuiltinConflict);
                    if (cfg.conflictBuiltinName != null) {
                        obj.put("conflictBuiltinName", cfg.conflictBuiltinName);
                    }
                    if (cfg.conflictBuiltinSearchUrl != null) {
                        obj.put("conflictBuiltinSearchUrl", cfg.conflictBuiltinSearchUrl);
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
                cfg.hasUpdate = obj.optBoolean("hasUpdate", false);
                cfg.pendingName = obj.has("pendingName") ? obj.optString("pendingName") : null;
                cfg.pendingSearchUrl = obj.has("pendingSearchUrl") ? obj.optString("pendingSearchUrl") : null;
                cfg.isRemovedFromBrowser = obj.optBoolean("isRemovedFromBrowser", false);
                cfg.hasBuiltinConflict = obj.optBoolean("hasBuiltinConflict", false);
                cfg.conflictBuiltinName = obj.has("conflictBuiltinName") ? obj.optString("conflictBuiltinName") : null;
                cfg.conflictBuiltinSearchUrl = obj.has("conflictBuiltinSearchUrl") ? obj.optString("conflictBuiltinSearchUrl") : null;

                if (!cfg.key.isEmpty()) {
                    list.add(cfg);
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }
}