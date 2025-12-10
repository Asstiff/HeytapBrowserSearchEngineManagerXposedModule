// app/src/main/java/com/upuaut/xposedsearch/hooks/HotSitesHook.java
package com.upuaut.xposedsearch.hooks;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.upuaut.xposedsearch.HotSitePrefsCache;

public class HotSitesHook {

    private static final String TAG = "XposedSearch";
    private static final String PROVIDER_DISCOVER_URI = "content://com.upuaut.xposedsearch.provider/hotsites_discover";

    private Context appContext;
    private XC_LoadPackage.LoadPackageParam lpparam;

    public HotSitesHook(XC_LoadPackage.LoadPackageParam lpparam) {
        this.lpparam = lpparam;
    }

    public void setAppContext(Context context) {
        this.appContext = context;
        HotSitePrefsCache.refresh(appContext);
    }

    public void hook() {
        XposedBridge.log("[" + TAG + "] HotSitesHook: Initializing");

        try {
            Class<?> adapterClass = XposedHelpers.findClass("x71.c", lpparam.classLoader);
            Class<?> dataClass = XposedHelpers.findClass("j71.c", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(adapterClass, "U", dataClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object dataContainer = param.args[0];

                    if (appContext == null) {
                        try {
                            Object repository = XposedHelpers.getObjectField(param.thisObject, "d");
                            appContext = (Context) XposedHelpers.callMethod(repository, "getContext");
                        } catch (Throwable t) {
                            XposedBridge.log("[" + TAG + "] HotSitesHook: Failed to get context: " + t.getMessage());
                            return;
                        }
                    }

                    HotSitePrefsCache.refresh(appContext);

                    if (!HotSitePrefsCache.isModuleEnabled()) {
                        XposedBridge.log("[" + TAG + "] HotSitesHook: Module disabled, skipping");
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    List<Object> originalList = (List<Object>) XposedHelpers.callMethod(dataContainer, "c");

                    if (originalList == null || originalList.isEmpty()) {
                        return;
                    }

                    // 上报发现的网站（静默更新默认列表）
                    reportDiscoveredSites(originalList);

                    // 应用用户配置
                    List<Object> modifiedList = applyUserConfig(originalList);

                    XposedHelpers.callMethod(dataContainer, "g", modifiedList);

                    XposedBridge.log("[" + TAG + "] HotSitesHook: Modified list, original=" +
                            originalList.size() + ", modified=" + modifiedList.size());
                }
            });

            XposedBridge.log("[" + TAG + "] HotSitesHook: Hooked x71.c.U()");

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Failed to hook: " + t.getMessage());
        }
    }

    private void reportDiscoveredSites(List<Object> originalList) {
        if (appContext == null) return;

        try {
            ContentResolver resolver = appContext.getContentResolver();

            // 构建发现的网站数据
            StringBuilder sitesJson = new StringBuilder("[");
            boolean first = true;

            for (Object site : originalList) {
                try {
                    long id = XposedHelpers.getLongField(site, "a");
                    String name = (String) XposedHelpers.getObjectField(site, "e");
                    String url = (String) XposedHelpers.getObjectField(site, "f");
                    String iconUrl = (String) XposedHelpers.getObjectField(site, "h");

                    if (url == null || url.isEmpty()) continue;

                    if (!first) sitesJson.append(",");
                    first = false;

                    sitesJson.append("{")
                            .append("\"id\":").append(id).append(",")
                            .append("\"name\":\"").append(escapeJson(name)).append("\",")
                            .append("\"url\":\"").append(escapeJson(url)).append("\",")
                            .append("\"iconUrl\":\"").append(escapeJson(iconUrl != null ? iconUrl : "")).append("\"")
                            .append("}");

                } catch (Throwable t) {
                    XposedBridge.log("[" + TAG + "] HotSitesHook: Error reading site: " + t.getMessage());
                }
            }

            sitesJson.append("]");

            ContentValues values = new ContentValues();
            values.put("sites", sitesJson.toString());
            resolver.insert(Uri.parse(PROVIDER_DISCOVER_URI), values);

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Failed to report sites: " + t.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private List<Object> applyUserConfig(List<Object> originalList) {
        List<HotSitePrefsCache.SiteConfig> configs = HotSitePrefsCache.getSiteConfigs(appContext);

        if (configs.isEmpty()) {
            return originalList;
        }

        // 创建 URL 到原始对象的映射
        java.util.Map<String, Object> urlToObject = new java.util.HashMap<>();
        for (Object site : originalList) {
            try {
                String url = (String) XposedHelpers.getObjectField(site, "f");
                if (url != null) {
                    urlToObject.put(normalizeUrl(url), site);
                }
            } catch (Throwable t) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Error mapping url: " + t.getMessage());
            }
        }

        List<Object> result = new ArrayList<>();

        // 按用户配置顺序添加启用的网站
        for (HotSitePrefsCache.SiteConfig config : configs) {
            if (!config.enabled) {
                continue;
            }

            String normalizedUrl = normalizeUrl(config.url);
            Object originalObj = urlToObject.get(normalizedUrl);

            if (originalObj != null) {
                // 应用用户的修改
                applyConfigToSite(originalObj, config);
                result.add(originalObj);
                urlToObject.remove(normalizedUrl);
            } else {
                // 用户添加的新网站，需要创建对象
                Object newSite = createHotSiteObject(config);
                if (newSite != null) {
                    result.add(newSite);
                }
            }
        }

        return result;
    }

    private void applyConfigToSite(Object site, HotSitePrefsCache.SiteConfig config) {
        try {
            if (config.name != null && !config.name.isEmpty()) {
                XposedHelpers.setObjectField(site, "e", config.name);
            }
            if (config.iconUrl != null && !config.iconUrl.isEmpty()) {
                XposedHelpers.setObjectField(site, "h", config.iconUrl);
            }
            if (config.url != null && !config.url.isEmpty()) {
                XposedHelpers.setObjectField(site, "f", config.url);
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Failed to apply config: " + t.getMessage());
        }
    }

    private Object createHotSiteObject(HotSitePrefsCache.SiteConfig config) {
        try {
            Class<?> siteClass = XposedHelpers.findClass("j71.b", lpparam.classLoader);
            Object site = XposedHelpers.newInstance(siteClass, config.id);

            XposedHelpers.setObjectField(site, "e", config.name != null ? config.name : "");
            XposedHelpers.setObjectField(site, "f", config.url != null ? config.url : "");
            XposedHelpers.setObjectField(site, "h", config.iconUrl != null ? config.iconUrl : "");

            return site;
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Failed to create site object: " + t.getMessage());
            return null;
        }
    }

    private String normalizeUrl(String url) {
        if (url == null) return "";
        return url.toLowerCase()
                .replaceAll("^https?://", "")
                .replaceAll("^www\\.", "")
                .replaceAll("/$", "");
    }
}