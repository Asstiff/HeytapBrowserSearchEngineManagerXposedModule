package com.upuaut.xposedsearch;

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
 * 用于在 Xposed hook 中读取配置的管理类
 * 使用 XSharedPreferences 作为主要读取方式，文件读取作为备用
 * 
 * 注意：XSharedPreferences 只能读取，不能写入
 * 写入操作仍需要通过 ContentProvider
 */
public class XposedPrefsManager {

    private static final String TAG = "XposedSearch";
    private static final String PACKAGE_NAME = "com.upuaut.xposedsearch";
    private static final String PREF_NAME = "xposed_search_engines";
    private static final String KEY_ENGINES = "engines";

    // 可能的配置文件路径
    private static final String[] POSSIBLE_PREFS_PATHS = {
        "/data/data/" + PACKAGE_NAME + "/shared_prefs/" + PREF_NAME + ".xml",
        "/data/user/0/" + PACKAGE_NAME + "/shared_prefs/" + PREF_NAME + ".xml",
        "/data/user_de/0/" + PACKAGE_NAME + "/shared_prefs/" + PREF_NAME + ".xml"
    };

    private static XSharedPreferences xPrefs;
    private static long lastModTime = 0;
    private static String cachedJson = null;

    /**
     * 初始化 XSharedPreferences
     */
    public static void init() {
        try {
            xPrefs = new XSharedPreferences(PACKAGE_NAME, PREF_NAME);
            if (xPrefs.getFile().canRead()) {
                XposedBridge.log(TAG + ": XSharedPreferences initialized successfully");
            } else {
                XposedBridge.log(TAG + ": XSharedPreferences file not readable, will use fallback");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to init XSharedPreferences: " + t.getMessage());
        }
    }

    /**
     * 从 XSharedPreferences 或文件加载配置
     * 
     * @return 配置列表，如果读取失败返回 null，如果成功但配置为空返回空列表
     */
    public static List<EngineConfigData> loadEngines() {
        String json = readConfigJson();
        if (json == null) {
            // 无法读取文件
            return null;
        }
        
        // 解析 JSON，如果解析结果为空列表则返回空列表
        List<EngineConfigData> result = parseJson(json);
        return result;
    }

    /**
     * 检查配置文件是否可读
     */
    public static boolean isPrefsReadable() {
        if (xPrefs != null) {
            try {
                File prefsFile = xPrefs.getFile();
                return prefsFile.exists() && prefsFile.canRead();
            } catch (Throwable t) {
                // ignore
            }
        }
        
        // 检查备用路径
        for (String path : POSSIBLE_PREFS_PATHS) {
            File file = new File(path);
            if (file.exists() && file.canRead()) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 读取配置 JSON
     */
    private static String readConfigJson() {
        // 方法1: 使用 XSharedPreferences
        if (xPrefs != null) {
            try {
                // 检查文件是否更新
                File prefsFile = xPrefs.getFile();
                if (prefsFile.exists() && prefsFile.canRead()) {
                    long currentModTime = prefsFile.lastModified();
                    if (currentModTime != lastModTime) {
                        xPrefs.reload();
                        lastModTime = currentModTime;
                        cachedJson = xPrefs.getString(KEY_ENGINES, null);
                        XposedBridge.log(TAG + ": Reloaded config from XSharedPreferences");
                    }
                    if (cachedJson != null) {
                        return cachedJson;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Error reading XSharedPreferences: " + t.getMessage());
            }
        }

        // 方法2: 直接读取文件（备用方案）
        return readConfigFromFile();
    }

    /**
     * 直接从文件读取配置（备用方案）
     * 
     * 注意: 此方法使用预定义路径，因为在 Xposed hook 环境中无法使用 Android 标准 API
     * 获取其他应用的数据目录。这些路径覆盖了大多数 Android 设备配置：
     * - /data/data/: 标准单用户路径
     * - /data/user/0/: 多用户设备的主用户路径
     * - /data/user_de/0/: 设备加密存储路径
     * 
     * 对于特殊的 ROM 或多用户场景，XSharedPreferences 是首选方案
     */
    private static String readConfigFromFile() {
        for (String path : POSSIBLE_PREFS_PATHS) {
            try {
                File file = new File(path);
                if (file.exists() && file.canRead()) {
                    String content = readFileContent(file);
                    String json = extractJsonFromXml(content);
                    if (json != null && !json.isEmpty()) {
                        XposedBridge.log(TAG + ": Loaded config from file: " + path);
                        return json;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Error reading file " + path + ": " + t.getMessage());
            }
        }

        return null;
    }

    /**
     * 读取文件内容
     */
    private static String readFileContent(File file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error reading file: " + e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 从 XML 内容中提取 JSON 字符串
     */
    private static String extractJsonFromXml(String xmlContent) {
        if (xmlContent == null || xmlContent.isEmpty()) {
            return null;
        }

        // 查找 engines 字段的值
        String searchKey = "name=\"" + KEY_ENGINES + "\"";
        int keyIndex = xmlContent.indexOf(searchKey);
        if (keyIndex == -1) {
            return null;
        }

        // 查找值的开始位置
        int valueStart = xmlContent.indexOf(">", keyIndex);
        if (valueStart == -1) {
            return null;
        }
        valueStart++;

        // 查找值的结束位置
        int valueEnd = xmlContent.indexOf("</string>", valueStart);
        if (valueEnd == -1) {
            return null;
        }

        String value = xmlContent.substring(valueStart, valueEnd);
        // 解码 XML 实体
        value = decodeXmlEntities(value);
        return value;
    }

    /**
     * 解码 XML 实体
     * 注意: &amp; 必须最后解码，因为其他实体解码后可能会引入 & 字符
     */
    private static String decodeXmlEntities(String text) {
        if (text == null) return null;
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&"); // 必须最后处理
    }

    /**
     * 解析 JSON 字符串为配置列表
     */
    private static List<EngineConfigData> parseJson(String json) {
        List<EngineConfigData> list = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return list;
        }

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;

                EngineConfigData cfg = new EngineConfigData();
                cfg.key = obj.optString("key", "");
                cfg.name = obj.optString("name", "");
                cfg.searchUrl = obj.optString("searchUrl", "");
                cfg.enabled = obj.optBoolean("enabled", true);
                cfg.isBuiltin = obj.optBoolean("isBuiltin", false);
                cfg.isRemovedFromBrowser = obj.optBoolean("isRemovedFromBrowser", false);
                cfg.hasBuiltinConflict = obj.optBoolean("hasBuiltinConflict", false);

                if (!cfg.key.isEmpty()) {
                    list.add(cfg);
                }
            }
            XposedBridge.log(TAG + ": Parsed " + list.size() + " engines from config");
        } catch (JSONException e) {
            XposedBridge.log(TAG + ": Error parsing JSON: " + e.getMessage());
        }

        return list;
    }

    /**
     * 轻量级配置数据类
     * 只包含 hook 需要的字段
     */
    public static class EngineConfigData {
        public String key;
        public String name;
        public String searchUrl;
        public boolean enabled;
        public boolean isBuiltin;
        public boolean isRemovedFromBrowser;
        public boolean hasBuiltinConflict;
    }
}
