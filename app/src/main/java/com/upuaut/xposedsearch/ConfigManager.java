// ConfigManager.java
package com.upuaut.xposedsearch;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ConfigManager {

    private static final String TAG = "XposedSearch";
    public static final String PREF_NAME = "xposed_search_engines";
    private static final String KEY_ENGINES = "engines";

    // Provider authority
    public static final String AUTHORITY = "com.upuaut.xposedsearch.provider";

    // ------------------------- 读写配置 -------------------------

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static List<SearchEngineConfig> loadEngines(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }

        SharedPreferences sp = getPrefs(context);
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

        SharedPreferences sp = getPrefs(context);
        sp.edit().putString(KEY_ENGINES, json).commit(); // 用 commit 确保立即写入

        // 确保文件可读
        makePrefsWorldReadable(context);

        // 通知变更
        notifyChange(context);
    }

    /**
     * 确保 SharedPreferences 文件可被其他进程读取
     */
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

    /**
     * 通知 ContentProvider 数据变更
     */
    private static void notifyChange(Context context) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/engines");
            context.getContentResolver().notifyChange(uri, null);
            Log.d(TAG, "[APP] notifyChange sent");
        } catch (Exception e) {
            Log.e(TAG, "notifyChange failed: " + e.getMessage());
        }
    }

    // ... 其余方法保持不变（handleDiscoveredEngine, markMissingEnginesAsRemoved 等）
    // 只需要确保所有修改配置的方法最后都调用 saveEngines()

    // ------------------------- 引擎发现与同步 -------------------------

    public static boolean handleDiscoveredEngine(Context context, String key, String name, String searchUrl) {
        if (context == null || key == null || key.isEmpty()) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig existing = findByKey(engines, key);

        if (existing == null) {
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

            if (existing.isRemovedFromBrowser) {
                existing.isRemovedFromBrowser = false;
                changed = true;
            }

            boolean nameChanged = name != null && !name.equals(existing.originalName);
            boolean urlChanged = searchUrl != null && !searchUrl.isEmpty()
                    && !searchUrl.equals(existing.originalSearchUrl);

            if (nameChanged || urlChanged) {
                if (existing.isModified) {
                    if (name != null) existing.originalName = name;
                    if (searchUrl != null && !searchUrl.isEmpty()) existing.originalSearchUrl = searchUrl;
                    existing.hasUpdate = false;
                    existing.pendingName = null;
                    existing.pendingSearchUrl = null;
                    changed = true;
                } else {
                    existing.hasUpdate = true;
                    existing.pendingName = name;
                    existing.pendingSearchUrl = searchUrl;
                    changed = true;
                }
            }

            if (changed) {
                saveEngines(context, engines);
                return true;
            }

        } else {
            if (!existing.hasBuiltinConflict) {
                existing.hasBuiltinConflict = true;
                existing.conflictBuiltinName = name;
                existing.conflictBuiltinSearchUrl = searchUrl;
                saveEngines(context, engines);
                return true;
            }
        }

        return false;
    }

    public static void markMissingEnginesAsRemoved(Context context, Set<String> discoveredKeys) {
        if (context == null || discoveredKeys == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        boolean changed = false;

        for (SearchEngineConfig engine : engines) {
            if (engine.isBuiltin && !discoveredKeys.contains(engine.key)) {
                if (!engine.isRemovedFromBrowser) {
                    engine.isRemovedFromBrowser = true;
                    changed = true;
                }
            }

            if (!engine.isBuiltin && engine.hasBuiltinConflict && !discoveredKeys.contains(engine.key)) {
                engine.hasBuiltinConflict = false;
                engine.conflictBuiltinName = null;
                engine.conflictBuiltinSearchUrl = null;
                changed = true;
            }
        }

        if (changed) {
            saveEngines(context, engines);
        }
    }

    public static void applyPendingUpdate(Context context, String key) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.hasUpdate) return;

        if (engine.pendingName != null) {
            engine.name = engine.pendingName;
            engine.originalName = engine.pendingName;
        }
        if (engine.pendingSearchUrl != null && !engine.pendingSearchUrl.isEmpty()) {
            engine.searchUrl = engine.pendingSearchUrl;
            engine.originalSearchUrl = engine.pendingSearchUrl;
        }

        engine.hasUpdate = false;
        engine.pendingName = null;
        engine.pendingSearchUrl = null;
        engine.isModified = false;

        saveEngines(context, engines);
    }

    public static void ignorePendingUpdate(Context context, String key) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.hasUpdate) return;

        if (engine.pendingName != null) engine.originalName = engine.pendingName;
        if (engine.pendingSearchUrl != null && !engine.pendingSearchUrl.isEmpty()) {
            engine.originalSearchUrl = engine.pendingSearchUrl;
        }

        engine.isModified = true;
        engine.hasUpdate = false;
        engine.pendingName = null;
        engine.pendingSearchUrl = null;

        saveEngines(context, engines);
    }

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
    }

    public static void convertCustomToBuiltin(Context context, String key) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || engine.isBuiltin || !engine.hasBuiltinConflict) return;

        engine.isBuiltin = true;
        engine.isModified = true;
        engine.originalName = engine.conflictBuiltinName;
        engine.originalSearchUrl = engine.conflictBuiltinSearchUrl;

        engine.hasBuiltinConflict = false;
        engine.conflictBuiltinName = null;
        engine.conflictBuiltinSearchUrl = null;

        saveEngines(context, engines);
    }

    public static String createCustomEngineCopy(Context context, String key) {
        if (context == null || key == null) return null;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || engine.isBuiltin || !engine.hasBuiltinConflict) return null;

        String builtinName = engine.conflictBuiltinName;
        String builtinSearchUrl = engine.conflictBuiltinSearchUrl;

        String newKey = key + "Custom";
        int suffix = 1;
        while (findByKey(engines, newKey) != null) {
            newKey = key + "Custom" + suffix;
            suffix++;
        }

        engine.key = newKey;
        engine.hasBuiltinConflict = false;
        engine.conflictBuiltinName = null;
        engine.conflictBuiltinSearchUrl = null;

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
        return newKey;
    }

    public static boolean resetEngine(Context context, String key) {
        if (context == null || key == null) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null || !engine.canReset()) return false;

        if (engine.originalName != null) engine.name = engine.originalName;
        if (engine.originalSearchUrl != null) engine.searchUrl = engine.originalSearchUrl;
        engine.enabled = true;
        engine.isModified = false;

        saveEngines(context, engines);
        return true;
    }

    public static void updateEngineByUser(Context context, String key, String name, String searchUrl, boolean enabled) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null) return;

        if (engine.isBuiltin) {
            boolean nameChanged = !name.equals(engine.originalName);
            boolean urlChanged = (searchUrl != null && engine.originalSearchUrl != null)
                    ? !searchUrl.equals(engine.originalSearchUrl)
                    : (searchUrl != null || engine.originalSearchUrl != null);

            engine.name = name;
            engine.searchUrl = searchUrl;
            engine.enabled = enabled;
            engine.isModified = nameChanged || urlChanged;
        } else {
            engine.name = name;
            engine.searchUrl = searchUrl;
            engine.enabled = enabled;
        }

        saveEngines(context, engines);
    }

    public static boolean updateCustomEngineWithKey(Context context, String oldKey, String newKey, String name, String searchUrl, boolean enabled) {
        if (context == null || oldKey == null || newKey == null || newKey.isEmpty()) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, oldKey);

        if (engine == null || engine.isBuiltin) return false;

        if (oldKey.equals(newKey)) {
            engine.name = name;
            engine.searchUrl = searchUrl;
            engine.enabled = enabled;
            saveEngines(context, engines);
            return true;
        }

        if (findByKey(engines, newKey) != null) {
            return false;
        }

        engine.key = newKey;
        engine.name = name;
        engine.searchUrl = searchUrl;
        engine.enabled = enabled;

        saveEngines(context, engines);
        return true;
    }

    public static void updateEngineEnabled(Context context, String key, boolean enabled) {
        if (context == null || key == null) return;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null) return;

        engine.enabled = enabled;
        saveEngines(context, engines);
    }

    public static boolean addCustomEngine(Context context, String key, String name, String searchUrl) {
        if (context == null || key == null || key.isEmpty()) return false;

        List<SearchEngineConfig> engines = loadEngines(context);

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
        return true;
    }

    public static boolean deleteEngine(Context context, String key) {
        if (context == null || key == null) return false;

        List<SearchEngineConfig> engines = loadEngines(context);
        SearchEngineConfig engine = findByKey(engines, key);

        if (engine == null) {
            return false;
        }

        engines.remove(engine);
        saveEngines(context, engines);
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
                    if (cfg.originalName != null) obj.put("originalName", cfg.originalName);
                    if (cfg.originalSearchUrl != null) obj.put("originalSearchUrl", cfg.originalSearchUrl);
                    obj.put("hasUpdate", cfg.hasUpdate);
                    if (cfg.pendingName != null) obj.put("pendingName", cfg.pendingName);
                    if (cfg.pendingSearchUrl != null) obj.put("pendingSearchUrl", cfg.pendingSearchUrl);
                    obj.put("isRemovedFromBrowser", cfg.isRemovedFromBrowser);
                    obj.put("hasBuiltinConflict", cfg.hasBuiltinConflict);
                    if (cfg.conflictBuiltinName != null) obj.put("conflictBuiltinName", cfg.conflictBuiltinName);
                    if (cfg.conflictBuiltinSearchUrl != null) obj.put("conflictBuiltinSearchUrl", cfg.conflictBuiltinSearchUrl);
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