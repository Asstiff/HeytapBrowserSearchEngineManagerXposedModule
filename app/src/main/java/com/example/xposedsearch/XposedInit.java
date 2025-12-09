package com.example.xposedsearch;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.heytap.browser";
    private static final String PROVIDER_URI = "content://com.example.xposedsearch.engines/engines";
    private static final String PROVIDER_DISCOVER_URI = "content://com.example.xposedsearch.engines/discover";

    private Context appContext;
    private Map<String, EngineConfig> engineConfigs = new HashMap<>();
    private Set<String> reportedEngines = new HashSet<>();
    private Set<String> builtinEngineKeys = new HashSet<>();

    private Class<?> searchEngineImplClass = null;
    private Class<?> searchEngineInterface = null;

    private Map<String, Object> customEngineProxies = new HashMap<>();

    private static class EngineConfig {
        String key;
        String name;
        String searchUrl;
        boolean enabled;
        boolean isBuiltin;

        EngineConfig(String key, String name, String searchUrl, boolean enabled, boolean isBuiltin) {
            this.key = key;
            this.name = name;
            this.searchUrl = searchUrl;
            this.enabled = enabled;
            this.isBuiltin = isBuiltin;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log("XposedSearch: loaded in " + lpparam.packageName);

        try {
            searchEngineInterface = XposedHelpers.findClass(
                    "com.heytap.browser.api.search.engine.c",
                    lpparam.classLoader
            );
            XposedBridge.log("XposedSearch: found SearchEngine interface");
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to find interface: " + t.getMessage());
        }

        try {
            searchEngineImplClass = XposedHelpers.findClass(
                    "com.heytap.browser.search.impl.engine.c",
                    lpparam.classLoader
            );
            XposedBridge.log("XposedSearch: found SearchEngine impl class");
            hookSearchEngineImplClass(searchEngineImplClass);
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to find impl class: " + t.getMessage());
        }

        XposedHelpers.findAndHookMethod(
                Application.class,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Application app = (Application) param.thisObject;
                        appContext = app.getApplicationContext();
                        XposedBridge.log("XposedSearch: got Application context");
                        refreshConfig();
                    }
                }
        );

        try {
            Class<?> defaultSearchEnginesClass = XposedHelpers.findClass(
                    "com.heytap.browser.search.impl.engine.DefaultSearchEngines",
                    lpparam.classLoader
            );
            XposedBridge.log("XposedSearch: found DefaultSearchEngines class");

            // Hook o 方法
            hookMethodByName(defaultSearchEnginesClass, "o", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (!(result instanceof List)) return;

                    @SuppressWarnings("unchecked")
                    List<Object> engineList = new ArrayList<>((List<Object>) result);

                    XposedBridge.log("XposedSearch: o - original count: " + engineList.size());

                    refreshConfig();

                    for (Object engine : engineList) {
                        reportDiscoveredEngine(engine);
                    }

                    // 修复：传入现有列表，避免重复添加
                    List<Object> customEngines = createCustomEngines(engineList);
                    if (!customEngines.isEmpty()) {
                        engineList.addAll(customEngines);
                        XposedBridge.log("XposedSearch: o - added " + customEngines.size() + " custom engines");
                    }

                    List<Object> filteredList = filterEngineList(engineList);

                    XposedBridge.log("XposedSearch: o - final count: " + filteredList.size());
                    param.setResult(filteredList);
                }
            });

            // Hook d0
            hookMethodByName(defaultSearchEnginesClass, "d0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (!(result instanceof List)) return;

                    @SuppressWarnings("unchecked")
                    List<Object> engineList = new ArrayList<>((List<Object>) result);

                    XposedBridge.log("XposedSearch: d0 - original count: " + engineList.size());

                    refreshConfig();

                    for (Object engine : engineList) {
                        reportDiscoveredEngine(engine);
                    }

                    // 修复：传入现有列表，避免重复添加
                    List<Object> customEngines = createCustomEngines(engineList);
                    if (!customEngines.isEmpty()) {
                        engineList.addAll(customEngines);
                    }

                    List<Object> filteredList = filterEngineList(engineList);

                    XposedBridge.log("XposedSearch: d0 - final count: " + filteredList.size());
                    param.setResult(filteredList);
                }
            });

            // Hook n0
            hookMethodByName(defaultSearchEnginesClass, "n0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.args.length == 0) return;
                        Object arg0 = param.args[0];
                        if (!(arg0 instanceof List)) return;

                        @SuppressWarnings("unchecked")
                        List<Object> engineList = (List<Object>) arg0;

                        refreshConfig();

                        for (Object engine : engineList) {
                            reportDiscoveredEngine(engine);
                        }

                        List<Object> resultList = filterEngineList(engineList);
                        param.args[0] = resultList;

                    } catch (Throwable t) {
                        XposedBridge.log("XposedSearch: n0 - error: " + t.getMessage());
                    }
                }
            });

            // Hook 其他列表方法
            String[] listMethods = {"R", "c0", "f0"};
            for (String methodName : listMethods) {
                hookMethodByName(defaultSearchEnginesClass, methodName, createListHook(methodName));
            }

            // Hook i0
            hookMethodByName(defaultSearchEnginesClass, "i0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();

                    if (result == null && param.args.length > 0) {
                        String requestedKey = null;
                        if (param.args[0] instanceof String) {
                            requestedKey = (String) param.args[0];
                        }

                        if (requestedKey != null) {
                            refreshConfig();
                            EngineConfig config = engineConfigs.get(requestedKey);
                            if (config != null && config.enabled && !config.isBuiltin) {
                                Object customEngine = getOrCreateCustomEngineProxy(config);
                                if (customEngine != null) {
                                    param.setResult(customEngine);
                                    XposedBridge.log("XposedSearch: i0 - returned custom engine: " + requestedKey);
                                    return;
                                }
                            }
                        }
                        return;
                    }

                    if (result == null) return;

                    reportDiscoveredEngine(result);

                    String key = getKey(result);
                    if (key == null) return;

                    refreshConfig();
                    EngineConfig config = engineConfigs.get(key);
                    if (config != null && !config.enabled) {
                        param.setResult(null);
                        XposedBridge.log("XposedSearch: i0 - blocked: " + key);
                    }
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to find DefaultSearchEngines: " + t.getMessage());
        }
    }

    /**
     * 创建自定义引擎列表（修复：检查是否已存在，避免重复）
     * @param existingEngines 当前已存在的引擎列表
     */
    private List<Object> createCustomEngines(List<Object> existingEngines) {
        List<Object> customEngines = new ArrayList<>();

        // 收集已存在的所有 key
        Set<String> existingKeys = new HashSet<>();
        for (Object engine : existingEngines) {
            String key = getKey(engine);
            if (key != null) {
                existingKeys.add(key);
            }
        }

        for (EngineConfig config : engineConfigs.values()) {
            if (config.enabled &&
                    !config.isBuiltin &&
                    config.searchUrl != null &&
                    !config.searchUrl.isEmpty()) {

                // 关键修复：检查是否已存在，避免重复添加
                if (existingKeys.contains(config.key)) {
                    XposedBridge.log("XposedSearch: custom engine already exists, skip: " + config.key);
                    continue;
                }

                Object proxy = getOrCreateCustomEngineProxy(config);
                if (proxy != null) {
                    customEngines.add(proxy);
                    existingKeys.add(config.key); // 防止同一次调用中重复
                    XposedBridge.log("XposedSearch: created custom engine: " + config.key);
                }
            }
        }

        return customEngines;
    }

    private Object getOrCreateCustomEngineProxy(EngineConfig config) {
        if (customEngineProxies.containsKey(config.key)) {
            return customEngineProxies.get(config.key);
        }

        Object proxy = createCustomEngineProxy(config);
        if (proxy != null) {
            customEngineProxies.put(config.key, proxy);
        }
        return proxy;
    }

    private List<Object> filterEngineList(List<Object> engineList) {
        List<Object> filteredList = new ArrayList<>();

        for (Object engine : engineList) {
            String key = getKey(engine);
            if (key != null) {
                EngineConfig config = engineConfigs.get(key);
                if (config != null && config.enabled) {
                    filteredList.add(engine);
                } else if (config == null) {
                    filteredList.add(engine);
                }
            } else {
                filteredList.add(engine);
            }
        }

        return filteredList;
    }

    private void hookSearchEngineImplClass(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "getLabel", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = getKey(param.thisObject);
                    if (key == null) return;

                    EngineConfig config = engineConfigs.get(key);
                    if (config != null && config.name != null && !config.name.isEmpty()) {
                        String original = (String) param.getResult();
                        if (!config.name.equals(original)) {
                            param.setResult(config.name);
                            XposedBridge.log("XposedSearch: getLabel " + key + ": " + original + " -> " + config.name);
                        }
                    }
                }
            });
            XposedBridge.log("XposedSearch: hooked getLabel()");
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to hook getLabel: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "q", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String query = (String) param.args[0];
                    String key = getKey(param.thisObject);
                    if (key == null) return;

                    EngineConfig config = engineConfigs.get(key);
                    if (config != null && config.searchUrl != null && !config.searchUrl.isEmpty()) {
                        String newUrl = buildSearchUrl(config.searchUrl, query);
                        param.setResult(newUrl);
                    }
                }
            });
            XposedBridge.log("XposedSearch: hooked q(String)");
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to hook q: " + t.getMessage());
        }
    }

    private Object createCustomEngineProxy(final EngineConfig config) {
        if (searchEngineInterface == null) {
            XposedBridge.log("XposedSearch: interface not found, cannot create proxy");
            return null;
        }

        try {
            return Proxy.newProxyInstance(
                    searchEngineInterface.getClassLoader(),
                    new Class<?>[]{searchEngineInterface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String methodName = method.getName();
                            Class<?> returnType = method.getReturnType();

                            if ("getKey".equals(methodName)) {
                                return config.key;
                            }
                            if ("getLabel".equals(methodName)) {
                                return config.name != null ? config.name : config.key;
                            }
                            if ("getHost".equals(methodName)) {
                                try {
                                    return new URL(config.searchUrl).getHost();
                                } catch (Exception e) {
                                    return "custom.search";
                                }
                            }
                            if ("q".equals(methodName)) {
                                String query = (args != null && args.length > 0) ? (String) args[0] : "";
                                return buildSearchUrl(config.searchUrl, query);
                            }

                            if ("getDefaultEnginesId".equals(methodName)) return null;
                            if ("getIconUrl".equals(methodName)) return null;
                            if ("getSearchPageType".equals(methodName)) return null;
                            if ("w".equals(methodName)) return null;
                            if ("y".equals(methodName)) return null;

                            if ("v".equals(methodName)) return config.searchUrl != null ? config.searchUrl : "";
                            if ("z".equals(methodName)) return config.searchUrl != null ? config.searchUrl : "";

                            if ("getIcon".equals(methodName)) return null;

                            if ("r".equals(methodName)) return true;
                            if ("s".equals(methodName)) return false;
                            if ("t".equals(methodName)) return false;
                            if ("u".equals(methodName)) return false;
                            if ("x".equals(methodName)) return false;

                            if ("toString".equals(methodName)) {
                                return "CustomSearchEngine{key=" + config.key + ", name=" + config.name + "}";
                            }
                            if ("hashCode".equals(methodName)) {
                                return config.key.hashCode();
                            }
                            if ("equals".equals(methodName)) {
                                if (args == null || args.length == 0) return false;
                                if (args[0] == proxy) return true;
                                try {
                                    String otherKey = getKey(args[0]);
                                    return config.key.equals(otherKey);
                                } catch (Exception e) {
                                    return false;
                                }
                            }

                            if (returnType == boolean.class) return false;
                            if (returnType == int.class) return 0;
                            if (returnType == String.class) return "";
                            return null;
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to create proxy: " + t.getMessage());
            return null;
        }
    }

    private XC_MethodHook createListHook(final String methodName) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object result = param.getResult();
                if (!(result instanceof List)) return;

                @SuppressWarnings("unchecked")
                List<Object> engineList = new ArrayList<>((List<Object>) result);
                if (engineList.isEmpty()) return;

                refreshConfig();

                for (Object engine : engineList) {
                    reportDiscoveredEngine(engine);
                }

                // 修复：传入现有列表，避免重复添加
                List<Object> customEngines = createCustomEngines(engineList);
                if (!customEngines.isEmpty()) {
                    engineList.addAll(customEngines);
                }

                List<Object> filteredList = filterEngineList(engineList);

                if (filteredList.size() != ((List<?>) result).size() || !customEngines.isEmpty()) {
                    param.setResult(filteredList);
                    XposedBridge.log("XposedSearch: " + methodName + " - " +
                            ((List<?>) result).size() + " -> " + filteredList.size());
                }
            }
        };
    }

    private String buildSearchUrl(String template, String query) {
        if (template == null) return "";
        if (query == null) query = "";

        String encodedQuery;
        try {
            encodedQuery = URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            encodedQuery = query;
        }

        if (template.contains("{searchTerms}")) {
            return template.replace("{searchTerms}", encodedQuery);
        } else if (template.contains("%s")) {
            return template.replace("%s", encodedQuery);
        } else {
            if (template.contains("?")) {
                return template + "&q=" + encodedQuery;
            } else {
                return template + "?q=" + encodedQuery;
            }
        }
    }

    /**
     * 上报发现的引擎（包含完整信息）
     */
    private void reportDiscoveredEngine(Object engine) {
        if (appContext == null || engine == null) return;

        String key = getKey(engine);
        if (key == null || reportedEngines.contains(key)) {
            return;
        }

        String label = getLabel(engine);
        String searchUrl = getSearchUrl(engine);

        builtinEngineKeys.add(key);

        try {
            ContentResolver resolver = appContext.getContentResolver();
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("name", label != null ? label : key);
            if (searchUrl != null && !searchUrl.isEmpty()) {
                values.put("searchUrl", searchUrl);
            }

            resolver.insert(Uri.parse(PROVIDER_DISCOVER_URI), values);
            reportedEngines.add(key);

            String urlPreview = searchUrl != null ?
                    searchUrl.substring(0, Math.min(50, searchUrl.length())) + "..." : "null";
            XposedBridge.log("XposedSearch: reported engine: " + key + " (" + label + ") url=" + urlPreview);
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to report engine: " + t.getMessage());
        }
    }

    /**
     * 获取引擎的搜索 URL 模板
     */
    private String getSearchUrl(Object obj) {
        if (obj == null) return null;

        // 尝试调用 v() 方法
        try {
            Object result = XposedHelpers.callMethod(obj, "v");
            if (result instanceof String && !((String) result).isEmpty()) {
                return (String) result;
            }
        } catch (Throwable ignored) {}

        // 尝试调用 z() 方法
        try {
            Object result = XposedHelpers.callMethod(obj, "z");
            if (result instanceof String && !((String) result).isEmpty()) {
                return (String) result;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private void hookMethodByName(Class<?> clazz, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(clazz, methodName, callback);
        if (unhooks.isEmpty()) {
            XposedBridge.log("XposedSearch: no methods found for " + methodName);
        } else {
            XposedBridge.log("XposedSearch: hooked " + unhooks.size() + " method(s) named " + methodName);
        }
    }

    private String getKey(Object obj) {
        if (obj == null) return null;
        try {
            Object result = XposedHelpers.callMethod(obj, "getKey");
            if (result instanceof String) return (String) result;
        } catch (Throwable ignored) {}
        return null;
    }

    private String getLabel(Object obj) {
        if (obj == null) return null;
        try {
            Object result = XposedHelpers.callMethod(obj, "getLabel");
            if (result instanceof String) return (String) result;
        } catch (Throwable ignored) {}
        return null;
    }

    private void refreshConfig() {
        if (appContext == null) return;

        try {
            ContentResolver resolver = appContext.getContentResolver();
            Uri uri = Uri.parse(PROVIDER_URI);

            Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    engineConfigs.clear();
                    // 注意：不再清空 customEngineProxies，保持代理对象缓存
                    // 这样可以确保同一个 key 始终返回同一个代理对象

                    while (cursor.moveToNext()) {
                        String key = cursor.getString(cursor.getColumnIndexOrThrow("key"));
                        String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                        String searchUrl = cursor.getString(cursor.getColumnIndexOrThrow("searchUrl"));
                        boolean enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1;
                        boolean isBuiltin = cursor.getInt(cursor.getColumnIndexOrThrow("isBuiltin")) == 1;

                        engineConfigs.put(key, new EngineConfig(key, name, searchUrl, enabled, isBuiltin));

                        if (isBuiltin) {
                            builtinEngineKeys.add(key);
                        }
                    }

                    // 清理不再存在的自定义引擎代理
                    Set<String> keysToRemove = new HashSet<>();
                    for (String proxyKey : customEngineProxies.keySet()) {
                        EngineConfig config = engineConfigs.get(proxyKey);
                        if (config == null || config.isBuiltin || !config.enabled) {
                            keysToRemove.add(proxyKey);
                        }
                    }
                    for (String key : keysToRemove) {
                        customEngineProxies.remove(key);
                    }

                    XposedBridge.log("XposedSearch: refreshed config, count=" + engineConfigs.size());
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: [Provider] failed: " + t.getMessage());
        }
    }
}