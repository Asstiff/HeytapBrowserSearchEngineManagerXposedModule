package com.upuaut.xposedsearch;

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
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.heytap.browser";
    private static final String PROVIDER_URI = "content://com.upuaut.xposedsearch.engines/engines";
    private static final String PROVIDER_DISCOVER_URI = "content://com.upuaut.xposedsearch.engines/discover";
    private static final String PROVIDER_DISCOVER_COMPLETE_URI = "content://com.upuaut.xposedsearch.engines/discover_complete";

    private Context appContext;
    private Map<String, EngineConfig> engineConfigs = new HashMap<>();
    private Set<String> reportedEngines = new HashSet<>();
    private Set<String> currentDiscoveredKeys = new HashSet<>();

    private Class<?> searchEngineImplClass = null;
    private Class<?> searchEngineInterface = null;

    private Map<String, Object> customEngineProxies = new HashMap<>();

    // 缓存浏览器原始 label（在 hook 修改之前）
    private Map<String, String> originalLabels = new HashMap<>();
    // 缓存浏览器原始 searchUrl
    private Map<String, String> originalSearchUrls = new HashMap<>();

    private static class EngineConfig {
        String key;
        String name;
        String searchUrl;
        boolean enabled;
        boolean isBuiltin;
        boolean isRemovedFromBrowser;
        boolean hasBuiltinConflict;

        EngineConfig(String key, String name, String searchUrl, boolean enabled,
                     boolean isBuiltin, boolean isRemovedFromBrowser, boolean hasBuiltinConflict) {
            this.key = key;
            this.name = name;
            this.searchUrl = searchUrl;
            this.enabled = enabled;
            this.isBuiltin = isBuiltin;
            this.isRemovedFromBrowser = isRemovedFromBrowser;
            this.hasBuiltinConflict = hasBuiltinConflict;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Hook 自身应用
        if (lpparam.packageName.equals("com.upuaut.xposedsearch")) {
            try {
                XposedHelpers.findAndHookMethod(
                        "com.upuaut.xposedsearch.ui.MainViewModelKt",
                        lpparam.classLoader,
                        "isXposedModuleActive",
                        new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) {
                                return true;
                            }
                        }
                );
                XposedBridge.log("XposedSearch: hooked isXposedModuleActive in self");
            } catch (Throwable t) {
                XposedBridge.log("XposedSearch: failed to hook self: " + t.getMessage());
            }
            return;
        }

        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log("XposedSearch: loaded in " + lpparam.packageName);

        // 初始化 XSharedPreferences，用于读取配置
        XposedPrefsManager.init();

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

                    // 开始新一轮发现
                    currentDiscoveredKeys.clear();

                    refreshConfig();

                    for (Object engine : engineList) {
                        reportDiscoveredEngine(engine);
                    }

                    // 发现完成，通知 Provider
                    notifyDiscoverComplete();

                    // 添加自定义引擎和已消失但仍启用的内置引擎
                    List<Object> additionalEngines = createAdditionalEngines(engineList);
                    if (!additionalEngines.isEmpty()) {
                        engineList.addAll(additionalEngines);
                        XposedBridge.log("XposedSearch: o - added " + additionalEngines.size() + " additional engines");
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

                    currentDiscoveredKeys.clear();

                    refreshConfig();

                    for (Object engine : engineList) {
                        reportDiscoveredEngine(engine);
                    }

                    notifyDiscoverComplete();

                    List<Object> additionalEngines = createAdditionalEngines(engineList);
                    if (!additionalEngines.isEmpty()) {
                        engineList.addAll(additionalEngines);
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
                            if (config != null && config.enabled &&
                                    (!config.isBuiltin || config.isRemovedFromBrowser)) {
                                Object customEngine = getOrCreateCustomEngineProxy(config);
                                if (customEngine != null) {
                                    param.setResult(customEngine);
                                    XposedBridge.log("XposedSearch: i0 - returned proxy engine: " + requestedKey);
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
     * 检查对象是否是我们创建的 proxy
     */
    private boolean isProxy(Object obj) {
        if (obj == null) return false;
        // 检查是否是 Java Proxy
        if (Proxy.isProxyClass(obj.getClass())) {
            return true;
        }
        // 检查是否是浏览器的内置引擎实现类
        if (searchEngineImplClass != null) {
            return !searchEngineImplClass.isInstance(obj);
        }
        return false;
    }

    private void notifyDiscoverComplete() {
        if (appContext == null) return;
        try {
            ContentResolver resolver = appContext.getContentResolver();
            resolver.insert(Uri.parse(PROVIDER_DISCOVER_COMPLETE_URI), new ContentValues());
            XposedBridge.log("XposedSearch: notified discover complete, keys=" + currentDiscoveredKeys.size());
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to notify discover complete: " + t.getMessage());
        }
    }

    private List<Object> createAdditionalEngines(List<Object> existingEngines) {
        List<Object> additionalEngines = new ArrayList<>();

        Set<String> existingKeys = new HashSet<>();
        for (Object engine : existingEngines) {
            String key = getKey(engine);
            if (key != null) {
                existingKeys.add(key);
            }
        }

        for (EngineConfig config : engineConfigs.values()) {
            if (existingKeys.contains(config.key)) {
                continue;
            }

            if (!config.enabled || config.searchUrl == null || config.searchUrl.isEmpty()) {
                continue;
            }

            if (!config.isBuiltin || config.isRemovedFromBrowser) {
                Object proxy = getOrCreateCustomEngineProxy(config);
                if (proxy != null) {
                    additionalEngines.add(proxy);
                    existingKeys.add(config.key);
                    XposedBridge.log("XposedSearch: created proxy for: " + config.key +
                            (config.isRemovedFromBrowser ? " (removed builtin)" : " (custom)"));
                }
            }
        }

        return additionalEngines;
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

                    // 获取浏览器原始返回值（在我们修改之前）
                    String originalLabel = (String) param.getResult();

                    // 始终缓存原始 label
                    if (originalLabel != null) {
                        originalLabels.put(key, originalLabel);
                    }

                    EngineConfig config = engineConfigs.get(key);
                    if (config != null && config.name != null && !config.name.isEmpty()) {
                        if (!config.name.equals(originalLabel)) {
                            param.setResult(config.name);
                            XposedBridge.log("XposedSearch: getLabel " + key + ": " + originalLabel + " -> " + config.name);
                        }
                    }
                }
            });
            XposedBridge.log("XposedSearch: hooked getLabel()");
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to hook getLabel: " + t.getMessage());
        }

        // Hook v() 方法获取原始 searchUrl
        try {
            XposedHelpers.findAndHookMethod(clazz, "v", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = getKey(param.thisObject);
                    if (key == null) return;

                    String originalUrl = (String) param.getResult();
                    if (originalUrl != null && !originalUrl.isEmpty()) {
                        originalSearchUrls.put(key, originalUrl);
                    }
                }
            });
            XposedBridge.log("XposedSearch: hooked v() for original URL");
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to hook v: " + t.getMessage());
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

                List<Object> additionalEngines = createAdditionalEngines(engineList);
                if (!additionalEngines.isEmpty()) {
                    engineList.addAll(additionalEngines);
                }

                List<Object> filteredList = filterEngineList(engineList);

                if (filteredList.size() != ((List<?>) result).size() || !additionalEngines.isEmpty()) {
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
     * 上报发现的引擎
     * 【关键修复】只报告真正的内置引擎，跳过我们创建的 proxy
     */
    private void reportDiscoveredEngine(Object engine) {
        if (appContext == null || engine == null) return;

        // 【关键修复】跳过 proxy，只报告浏览器的原生引擎
        if (isProxy(engine)) {
            XposedBridge.log("XposedSearch: skipping proxy in reportDiscoveredEngine");
            return;
        }

        String key = getKey(engine);
        if (key == null) return;

        // 追踪发现的 key
        currentDiscoveredKeys.add(key);

        if (reportedEngines.contains(key)) {
            return;
        }

        // 触发 hook 缓存原始值
        getLabel(engine);
        getSearchUrl(engine);

        // 从缓存获取原始值
        String label = originalLabels.get(key);
        String searchUrl = originalSearchUrls.get(key);

        // 如果缓存没有，用当前值（但这不应该发生）
        if (label == null) {
            label = getLabel(engine);
        }
        if (searchUrl == null) {
            searchUrl = getSearchUrl(engine);
        }

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
            XposedBridge.log("XposedSearch: reported engine (original): " + key + " (" + label + ") url=" + urlPreview);
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to report engine: " + t.getMessage());
        }
    }

    private String getSearchUrl(Object obj) {
        if (obj == null) return null;

        try {
            Object result = XposedHelpers.callMethod(obj, "v");
            if (result instanceof String && !((String) result).isEmpty()) {
                return (String) result;
            }
        } catch (Throwable ignored) {}

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

    /**
     * 刷新配置
     * 优先使用 XSharedPreferences 读取配置（更可靠）
     * 如果失败，则回退到 ContentProvider
     */
    private void refreshConfig() {
        // 方法1: 使用 XSharedPreferences（更可靠，不依赖主应用进程）
        boolean loadedFromPrefs = refreshConfigFromPrefs();
        
        if (loadedFromPrefs) {
            return;
        }

        // 方法2: 回退到 ContentProvider（需要主应用运行）
        refreshConfigFromProvider();
    }

    /**
     * 从 XSharedPreferences 读取配置
     * @return true 如果成功读取，false 如果失败
     */
    private boolean refreshConfigFromPrefs() {
        try {
            List<XposedPrefsManager.EngineConfigData> configs = XposedPrefsManager.loadEngines();
            
            if (configs.isEmpty()) {
                XposedBridge.log("XposedSearch: XSharedPreferences returned empty config");
                return false;
            }

            engineConfigs.clear();
            for (XposedPrefsManager.EngineConfigData data : configs) {
                engineConfigs.put(data.key, new EngineConfig(
                        data.key,
                        data.name,
                        data.searchUrl,
                        data.enabled,
                        data.isBuiltin,
                        data.isRemovedFromBrowser,
                        data.hasBuiltinConflict
                ));
            }

            // 清理不再存在的代理
            cleanupProxies();

            XposedBridge.log("XposedSearch: refreshed config from XSharedPreferences, count=" + engineConfigs.size());
            return true;
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: [XSharedPreferences] failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * 从 ContentProvider 读取配置（回退方案）
     */
    private void refreshConfigFromProvider() {
        if (appContext == null) return;

        try {
            ContentResolver resolver = appContext.getContentResolver();
            Uri uri = Uri.parse(PROVIDER_URI);

            Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    engineConfigs.clear();

                    while (cursor.moveToNext()) {
                        String key = cursor.getString(cursor.getColumnIndexOrThrow("key"));
                        String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                        String searchUrl = cursor.getString(cursor.getColumnIndexOrThrow("searchUrl"));
                        boolean enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1;
                        boolean isBuiltin = cursor.getInt(cursor.getColumnIndexOrThrow("isBuiltin")) == 1;
                        boolean isRemoved = cursor.getInt(cursor.getColumnIndexOrThrow("isRemovedFromBrowser")) == 1;
                        boolean hasConflict = cursor.getInt(cursor.getColumnIndexOrThrow("hasBuiltinConflict")) == 1;

                        engineConfigs.put(key, new EngineConfig(key, name, searchUrl, enabled,
                                isBuiltin, isRemoved, hasConflict));
                    }

                    // 清理不再存在的代理
                    cleanupProxies();

                    XposedBridge.log("XposedSearch: refreshed config from Provider, count=" + engineConfigs.size());
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: [Provider] failed: " + t.getMessage());
        }
    }

    /**
     * 清理不再存在或已禁用的代理
     */
    private void cleanupProxies() {
        Set<String> keysToRemove = new HashSet<>();
        for (String proxyKey : customEngineProxies.keySet()) {
            EngineConfig config = engineConfigs.get(proxyKey);
            if (config == null || !config.enabled) {
                keysToRemove.add(proxyKey);
            }
        }
        for (String key : keysToRemove) {
            customEngineProxies.remove(key);
        }
    }
}