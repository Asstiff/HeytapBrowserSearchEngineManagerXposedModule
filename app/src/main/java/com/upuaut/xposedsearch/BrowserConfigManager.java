package com.upuaut.xposedsearch;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 在浏览器数据目录中管理配置文件
 * 
 * 此类用于在浏览器进程中读写配置，以及在主应用中通过 root 读写配置
 * 
 * 配置文件位置: /data/data/com.heytap.browser/files/xposed_search_config.json
 */
public class BrowserConfigManager {

    private static final String TAG = "XposedSearch";
    public static final String BROWSER_PACKAGE = "com.heytap.browser";
    public static final String CONFIG_FILE_NAME = "xposed_search_config.json";
    
    // 配置文件完整路径
    public static final String CONFIG_FILE_PATH = "/data/data/" + BROWSER_PACKAGE + "/files/" + CONFIG_FILE_NAME;

    // ======================== 在 Hook 中使用（浏览器进程内） ========================

    /**
     * 在浏览器进程中保存配置
     * Hook 运行在浏览器进程内，有权限直接写入
     */
    public static boolean saveConfigInBrowser(Context browserContext, List<SearchEngineConfig> engines) {
        if (browserContext == null || engines == null) return false;

        try {
            File filesDir = browserContext.getFilesDir();
            File configFile = new File(filesDir, CONFIG_FILE_NAME);
            
            String json = toJson(engines);
            
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(json);
            }
            
            Log.d(TAG, "[Hook] Saved config to browser: " + configFile.getAbsolutePath() + ", count=" + engines.size());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "[Hook] Failed to save config: " + e.getMessage());
            return false;
        }
    }

    /**
     * 在浏览器进程中加载配置
     * Hook 运行在浏览器进程内，有权限直接读取
     */
    public static List<SearchEngineConfig> loadConfigInBrowser(Context browserContext) {
        if (browserContext == null) return new ArrayList<>();

        try {
            File filesDir = browserContext.getFilesDir();
            File configFile = new File(filesDir, CONFIG_FILE_NAME);
            
            if (!configFile.exists()) {
                Log.d(TAG, "[Hook] Config file not found, returning empty list");
                return new ArrayList<>();
            }
            
            String json = readFile(configFile);
            if (json == null || json.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<SearchEngineConfig> engines = fromJson(json);
            Log.d(TAG, "[Hook] Loaded config from browser: count=" + engines.size());
            return engines;
        } catch (Exception e) {
            Log.e(TAG, "[Hook] Failed to load config: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ======================== 在主应用中使用（通过 Root） ========================

    /**
     * 通过 Root 保存配置到浏览器数据目录
     * 使用 base64 编码避免 shell 特殊字符问题
     */
    public static boolean saveConfigWithRoot(List<SearchEngineConfig> engines) {
        if (engines == null) engines = new ArrayList<>();

        try {
            String json = toJson(engines);
            
            // 使用 base64 编码避免 shell 注入问题
            String base64Json = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            
            // 确保目录存在，然后使用 base64 解码写入文件
            String[] commands = {
                "mkdir -p /data/data/" + BROWSER_PACKAGE + "/files",
                "echo '" + base64Json + "' | base64 -d > " + CONFIG_FILE_PATH,
                "chmod 644 " + CONFIG_FILE_PATH
            };
            
            for (String command : commands) {
                RootUtils.CommandResult result = RootUtils.executeRootCommand(command);
                if (!result.success) {
                    Log.e(TAG, "[App] Command failed: " + command + ", error: " + result.error);
                    return false;
                }
            }
            
            Log.d(TAG, "[App] Saved config with root, count=" + engines.size());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "[App] Error saving config with root: " + e.getMessage());
            return false;
        }
    }

    /**
     * 通过 Root 从浏览器数据目录加载配置
     */
    public static List<SearchEngineConfig> loadConfigWithRoot() {
        try {
            // 使用 root 读取文件
            String command = "cat " + CONFIG_FILE_PATH + " 2>/dev/null";
            RootUtils.CommandResult result = RootUtils.executeRootCommand(command);
            
            if (result.success && result.output != null && !result.output.isEmpty()) {
                List<SearchEngineConfig> engines = fromJson(result.output);
                Log.d(TAG, "[App] Loaded config with root, count=" + engines.size());
                return engines;
            } else {
                Log.d(TAG, "[App] Config file not found or empty");
                return new ArrayList<>();
            }
        } catch (Exception e) {
            Log.e(TAG, "[App] Error loading config with root: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 检查配置文件是否存在（通过 Root）
     */
    public static boolean configExistsWithRoot() {
        try {
            String command = "test -f " + CONFIG_FILE_PATH + " && echo 'exists'";
            RootUtils.CommandResult result = RootUtils.executeRootCommand(command);
            return result.success && result.output.contains("exists");
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== 工具方法 ========================

    private static String readFile(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading file: " + e.getMessage());
        }
        return sb.toString();
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
