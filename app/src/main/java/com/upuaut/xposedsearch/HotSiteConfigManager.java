// app/src/main/java/com/upuaut/xposedsearch/HotSiteConfigManager.java
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HotSiteConfigManager {

    private static final String TAG = "XposedSearch";
    public static final String PREF_NAME = "xposed_hot_sites";
    private static final String KEY_SITES = "sites";
    private static final String KEY_DEFAULT_SITES = "default_sites"; // 浏览器默认网站（用于恢复）
    private static final String KEY_ENABLED = "module_enabled";

    public static final String AUTHORITY = "com.upuaut.xposedsearch.provider";

    // ------------------------- 读写配置 -------------------------

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 加载用户网站列表
     */
    public static List<HotSiteConfig> loadSites(Context context) {
        if (context == null) return new ArrayList<>();

        SharedPreferences sp = getPrefs(context);
        String json = sp.getString(KEY_SITES, null);

        if (json == null || json.isEmpty()) {
            Log.d(TAG, "[APP] loadSites: no user config, return empty");
            return new ArrayList<>();
        }

        List<HotSiteConfig> list = fromJson(json);
        // 按 order 排序
        Collections.sort(list, Comparator.comparingInt(a -> a.order));
        Log.d(TAG, "[APP] loadSites size=" + list.size());
        return list;
    }

    /**
     * 保存用户网站列表
     */
    public static void saveSites(Context context, List<HotSiteConfig> list) {
        if (context == null) return;
        if (list == null) list = new ArrayList<>();

        // 更新 order
        for (int i = 0; i < list.size(); i++) {
            list.get(i).order = i;
        }

        String json = toJson(list);
        Log.d(TAG, "[APP] saveSites size=" + list.size());

        SharedPreferences sp = getPrefs(context);
        sp.edit().putString(KEY_SITES, json).commit();

        makePrefsWorldReadable(context);
        notifyChange(context);
    }

    /**
     * 加载默认网站列表（浏览器内置的）
     */
    public static List<HotSiteConfig> loadDefaultSites(Context context) {
        if (context == null) return new ArrayList<>();

        SharedPreferences sp = getPrefs(context);
        String json = sp.getString(KEY_DEFAULT_SITES, null);

        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }

        return fromJson(json);
    }

    /**
     * 保存默认网站列表（静默更新，不通知）
     */
    private static void saveDefaultSites(Context context, List<HotSiteConfig> list) {
        if (context == null || list == null) return;

        String json = toJson(list);
        SharedPreferences sp = getPrefs(context);
        sp.edit().putString(KEY_DEFAULT_SITES, json).commit();
        makePrefsWorldReadable(context);

        Log.d(TAG, "[APP] saveDefaultSites size=" + list.size());
    }

    public static boolean isModuleEnabled(Context context) {
        if (context == null) return true;
        return getPrefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setModuleEnabled(Context context, boolean enabled) {
        if (context == null) return;
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
        makePrefsWorldReadable(context);
        notifyChange(context);
    }

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

    private static void notifyChange(Context context) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/hotsites");
            context.getContentResolver().notifyChange(uri, null);
            Log.d(TAG, "[APP] notifyChange hotsites sent");
        } catch (Exception e) {
            Log.e(TAG, "notifyChange failed: " + e.getMessage());
        }
    }

    // 在 HotSiteConfigManager.java 中添加此方法

    /**
     * 根据 ID 列表重新排序网站
     */
    public static void reorderSites(Context context, List<Long> orderedIds) {
        if (context == null || orderedIds == null || orderedIds.isEmpty()) return;

        List<HotSiteConfig> sites = loadSites(context);

        // 创建 ID 到网站的映射
        Map<Long, HotSiteConfig> idToSite = new HashMap<>();
        for (HotSiteConfig site : sites) {
            idToSite.put(site.id, site);
        }

        // 按新顺序重建列表
        List<HotSiteConfig> reorderedSites = new ArrayList<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            HotSiteConfig site = idToSite.get(id);
            if (site != null) {
                site.order = i;
                reorderedSites.add(site);
                idToSite.remove(id);
            }
        }

        // 将剩余的网站添加到末尾
        for (HotSiteConfig site : idToSite.values()) {
            site.order = reorderedSites.size();
            reorderedSites.add(site);
        }

        saveSites(context, reorderedSites);
    }

    // ------------------------- 网站发现（静默更新默认列表） -------------------------

    /**
     * 处理从浏览器发现的网站 - 静默更新默认列表
     */
    public static void handleDiscoveredSites(Context context, List<HotSiteConfig> discoveredSites) {
        if (context == null || discoveredSites == null || discoveredSites.isEmpty()) return;

        // 更新默认网站列表
        saveDefaultSites(context, discoveredSites);

        // 如果用户还没有自定义配置，则初始化用户列表
        List<HotSiteConfig> userSites = loadSites(context);
        if (userSites.isEmpty()) {
            // 复制默认列表作为用户初始配置
            List<HotSiteConfig> initialSites = new ArrayList<>();
            for (int i = 0; i < discoveredSites.size(); i++) {
                HotSiteConfig site = discoveredSites.get(i);
                HotSiteConfig copy = new HotSiteConfig();
                copy.id = site.id;
                copy.name = site.name;
                copy.url = site.url;
                copy.iconUrl = site.iconUrl;
                copy.enabled = true;
                copy.order = i;
                initialSites.add(copy);
            }
            saveSites(context, initialSites);
            Log.d(TAG, "[APP] Initialized user sites from browser defaults");
        }
    }

    // ------------------------- 用户操作 -------------------------

    /**
     * 添加网站
     */
    public static boolean addSite(Context context, String name, String url, String iconUrl) {
        if (context == null || name == null || name.isEmpty() || url == null || url.isEmpty()) {
            return false;
        }

        List<HotSiteConfig> sites = loadSites(context);

        // 检查是否已存在相同 URL
        if (findByUrl(sites, url) != null) {
            return false;
        }

        HotSiteConfig newSite = new HotSiteConfig();
        newSite.id = System.currentTimeMillis();
        newSite.name = name;
        newSite.url = url;
        newSite.iconUrl = iconUrl != null ? iconUrl : "";
        newSite.enabled = true;
        newSite.order = sites.size(); // 添加到末尾

        sites.add(newSite);
        saveSites(context, sites);
        return true;
    }

    /**
     * 更新网站
     */
    public static void updateSite(Context context, long id, String name, String url, String iconUrl, boolean enabled) {
        if (context == null) return;

        List<HotSiteConfig> sites = loadSites(context);
        HotSiteConfig site = findById(sites, id);

        if (site == null) return;

        site.name = name;
        site.url = url;
        site.iconUrl = iconUrl;
        site.enabled = enabled;

        saveSites(context, sites);
    }

    /**
     * 更新网站启用状态
     */
    public static void updateSiteEnabled(Context context, long id, boolean enabled) {
        if (context == null) return;

        List<HotSiteConfig> sites = loadSites(context);
        HotSiteConfig site = findById(sites, id);

        if (site == null) return;

        site.enabled = enabled;
        saveSites(context, sites);
    }

    /**
     * 删除网站
     */
    public static boolean deleteSite(Context context, long id) {
        if (context == null) return false;

        List<HotSiteConfig> sites = loadSites(context);
        HotSiteConfig site = findById(sites, id);

        if (site == null) return false;

        sites.remove(site);
        saveSites(context, sites);
        return true;
    }

    /**
     * 移动网站（排序）
     */
    public static void moveSite(Context context, int fromPosition, int toPosition) {
        if (context == null) return;

        List<HotSiteConfig> sites = loadSites(context);
        if (fromPosition < 0 || fromPosition >= sites.size() ||
                toPosition < 0 || toPosition >= sites.size()) {
            return;
        }

        HotSiteConfig site = sites.remove(fromPosition);
        sites.add(toPosition, site);
        saveSites(context, sites);
    }

    /**
     * 恢复默认
     */
    public static void resetToDefault(Context context) {
        if (context == null) return;

        List<HotSiteConfig> defaultSites = loadDefaultSites(context);
        if (defaultSites.isEmpty()) {
            Log.d(TAG, "[APP] resetToDefault: no default sites available");
            return;
        }

        // 复制默认列表
        List<HotSiteConfig> resetSites = new ArrayList<>();
        for (int i = 0; i < defaultSites.size(); i++) {
            HotSiteConfig site = defaultSites.get(i);
            HotSiteConfig copy = new HotSiteConfig();
            copy.id = site.id;
            copy.name = site.name;
            copy.url = site.url;
            copy.iconUrl = site.iconUrl;
            copy.enabled = true;
            copy.order = i;
            resetSites.add(copy);
        }

        saveSites(context, resetSites);
        Log.d(TAG, "[APP] resetToDefault: restored " + resetSites.size() + " sites");
    }

    /**
     * 检查是否有可用的默认网站
     */
    public static boolean hasDefaultSites(Context context) {
        if (context == null) return false;
        List<HotSiteConfig> defaultSites = loadDefaultSites(context);
        return !defaultSites.isEmpty();
    }

    // ------------------------- 工具方法 -------------------------

    public static HotSiteConfig findById(List<HotSiteConfig> list, long id) {
        if (list == null) return null;
        for (HotSiteConfig cfg : list) {
            if (cfg.id == id) {
                return cfg;
            }
        }
        return null;
    }

    public static HotSiteConfig findByUrl(List<HotSiteConfig> list, String url) {
        if (list == null || url == null) return null;
        for (HotSiteConfig cfg : list) {
            if (cfg.matchesUrl(url)) {
                return cfg;
            }
        }
        return null;
    }

    public static String toJson(List<HotSiteConfig> list) {
        JSONArray array = new JSONArray();
        if (list != null) {
            for (HotSiteConfig cfg : list) {
                if (cfg == null) continue;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("id", cfg.id);
                    obj.put("name", cfg.name);
                    obj.put("url", cfg.url);
                    obj.put("iconUrl", cfg.iconUrl);
                    obj.put("enabled", cfg.enabled);
                    obj.put("order", cfg.order);
                    array.put(obj);
                } catch (JSONException ignored) {
                }
            }
        }
        return array.toString();
    }

    public static List<HotSiteConfig> fromJson(String json) {
        List<HotSiteConfig> list = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;

                HotSiteConfig cfg = new HotSiteConfig();
                cfg.id = obj.optLong("id", System.currentTimeMillis());
                cfg.name = obj.optString("name", "");
                cfg.url = obj.optString("url", "");
                cfg.iconUrl = obj.optString("iconUrl", "");
                cfg.enabled = obj.optBoolean("enabled", true);
                cfg.order = obj.optInt("order", i);

                if (!cfg.url.isEmpty()) {
                    list.add(cfg);
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }
}