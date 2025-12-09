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

    // 缓存已创建的自定义引擎代理
    private Map<String, Object> customEngineProxies = new HashMap<>();

    private static class EngineConfig {
        String key;
        String name;
        String searchUrl;
        boolean enabled;

        EngineConfig(String key, String name, String searchUrl, boolean enabled) {
            this.key = key;
            this.name = name;
            this.searchUrl = searchUrl;
            this.enabled = enabled;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log("XposedSearch: loaded in " + lpparam.packageName);

        // 获取接口类
        try {
            searchEngineInterface = XposedHelpers.findClass(
                    "com.heytap.browser.api.search.engine.c",
                    lpparam.classLoader
            );
            XposedBridge.log("XposedSearch: found SearchEngine interface");
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to find interface: " + t.getMessage());
        }

        // 获取实现类并 hook
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

            // Hook o 方法 - 设置界面使用的核心方法
            hookMethodByName(defaultSearchEnginesClass, "o", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (!(result instanceof List)) return;

                    @SuppressWarnings("unchecked")
                    List<Object> engineList = new ArrayList<>((List<Object>) result);

                    XposedBridge.log("XposedSearch: o - original count: " + engineList.size());

                    refreshConfig();

                    // 记录内置引擎
                    for (Object engine : engineList) {
                        String key = getKey(engine);
                        String label = getLabel(engine);
                        if (key != null) {
                            reportDiscoveredEngine(key, label);
                            builtinEngineKeys.add(key);
                        }
                    }

                    // 添加自定义引擎
                    List<Object> customEngines = createCustomEngines();
                    if (!customEngines.isEmpty()) {
                        engineList.addAll(customEngines);
                        XposedBridge.log("XposedSearch: o - added " + customEngines.size() + " custom engines");
                    }

                    // 过滤禁用的引擎
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
                        String key = getKey(engine);
                        String label = getLabel(engine);
                        if (key != null) {
                            reportDiscoveredEngine(key, label);
                            builtinEngineKeys.add(key);
                        }
                    }

                    List<Object> customEngines = createCustomEngines();
                    if (!customEngines.isEmpty()) {
                        engineList.addAll(customEngines);
                        XposedBridge.log("XposedSearch: d0 - added " + customEngines.size() + " custom engines");
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
                            String key = getKey(engine);
                            String label = getLabel(engine);
                            if (key != null && !key.isEmpty()) {
                                reportDiscoveredEngine(key, label);
                                builtinEngineKeys.add(key);
                            }
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

            // Hook i0 - 获取单个引擎
            hookMethodByName(defaultSearchEnginesClass, "i0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();

                    // 如果原方法返回 null，检查是否是自定义引擎
                    if (result == null && param.args.length > 0) {
                        String requestedKey = null;
                        if (param.args[0] instanceof String) {
                            requestedKey = (String) param.args[0];
                        }

                        if (requestedKey != null) {
                            refreshConfig();
                            EngineConfig config = engineConfigs.get(requestedKey);
                            if (config != null && config.enabled && !builtinEngineKeys.contains(requestedKey)) {
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

                    String key = getKey(result);
                    if (key == null) return;

                    String label = getLabel(result);
                    reportDiscoveredEngine(key, label);
                    builtinEngineKeys.add(key);

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
     * 创建所有自定义引擎的代理
     */
    private List<Object> createCustomEngines() {
        List<Object> customEngines = new ArrayList<>();

        for (EngineConfig config : engineConfigs.values()) {
            // 只添加：启用的、非内置的、有 searchUrl 的引擎
            if (config.enabled &&
                    !builtinEngineKeys.contains(config.key) &&
                    config.searchUrl != null &&
                    !config.searchUrl.isEmpty()) {

                Object proxy = getOrCreateCustomEngineProxy(config);
                if (proxy != null) {
                    customEngines.add(proxy);
                    XposedBridge.log("XposedSearch: created custom engine: " + config.key);
                }
            }
        }

        return customEngines;
    }

    /**
     * 获取或创建自定义引擎代理（带缓存）
     */
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

    /**
     * 过滤引擎列表
     */
    private List<Object> filterEngineList(List<Object> engineList) {
        List<Object> filteredList = new ArrayList<>();

        for (Object engine : engineList) {
            String key = getKey(engine);
            if (key != null) {
                EngineConfig config = engineConfigs.get(key);
                if (config != null && config.enabled) {
                    filteredList.add(engine);
                } else if (config == null) {
                    // 未知引擎，保留
                    filteredList.add(engine);
                }
                // config != null && !config.enabled 的情况不添加，即过滤掉
            } else {
                filteredList.add(engine);
            }
        }

        return filteredList;
    }

    /**
     * Hook 实现类的 getLabel() 和 q() 方法
     */
    private void hookSearchEngineImplClass(Class<?> clazz) {
        // Hook getLabel()
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

        // Hook q(String)
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

    /**
     * 创建自定义搜索引擎的动态代理
     */
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

                            // 核心方法
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

                            // 可空字符串方法
                            if ("getDefaultEnginesId".equals(methodName)) return null;
                            if ("getIconUrl".equals(methodName)) return null;
                            if ("getSearchPageType".equals(methodName)) return null;
                            if ("w".equals(methodName)) return null;
                            if ("y".equals(methodName)) return null;

                            // 非空字符串方法
                            if ("v".equals(methodName)) return config.searchUrl != null ? config.searchUrl : "";
                            if ("z".equals(methodName)) return config.searchUrl != null ? config.searchUrl : "";

                            // Bitmap方法
                            if ("getIcon".equals(methodName)) return null;

                            // Boolean方法
                            if ("r".equals(methodName)) return true;   // showInList
                            if ("s".equals(methodName)) return false;  // preload
                            if ("t".equals(methodName)) return false;  // isRecEngine
                            if ("u".equals(methodName)) return false;  // display flag
                            if ("x".equals(methodName)) return false;  // some flag

                            // Object 方法
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

                            // 默认返回值
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
                    String key = getKey(engine);
                    String label = getLabel(engine);
                    if (key != null) {
                        reportDiscoveredEngine(key, label);
                        builtinEngineKeys.add(key);
                    }
                }

                // 添加自定义引擎
                List<Object> customEngines = createCustomEngines();
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

    private void reportDiscoveredEngine(String key, String label) {
        if (appContext == null || key == null || reportedEngines.contains(key)) {
            return;
        }

        try {
            ContentResolver resolver = appContext.getContentResolver();
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("name", label != null ? label : key);

            resolver.insert(Uri.parse(PROVIDER_DISCOVER_URI), values);
            reportedEngines.add(key);
            XposedBridge.log("XposedSearch: reported engine: " + key + " (" + label + ")");
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: failed to report engine: " + t.getMessage());
        }
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
                    customEngineProxies.clear(); // 清除缓存，重新创建

                    while (cursor.moveToNext()) {
                        String key = cursor.getString(cursor.getColumnIndexOrThrow("key"));
                        String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                        String searchUrl = cursor.getString(cursor.getColumnIndexOrThrow("searchUrl"));
                        boolean enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) == 1;

                        engineConfigs.put(key, new EngineConfig(key, name, searchUrl, enabled));
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