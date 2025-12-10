// app/src/main/java/com/upuaut/xposedsearch/hooks/SearchEngineHook.java
package com.upuaut.xposedsearch.hooks;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.upuaut.xposedsearch.PrefsCache;

public class SearchEngineHook {

    private static final String TAG = "XposedSearch";
    private static final String PROVIDER_DISCOVER_URI = "content://com.upuaut.xposedsearch.provider/discover";
    private static final String PROVIDER_DISCOVER_COMPLETE_URI = "content://com.upuaut.xposedsearch.provider/discover_complete";

    private Context appContext;
    private XC_LoadPackage.LoadPackageParam lpparam;
    private Set<String> reportedEngines = new HashSet<>();
    private Set<String> currentDiscoveredKeys = new HashSet<>();

    private Class<?> searchEngineImplClass = null;
    private Class<?> searchEngineInterface = null;

    private Map<String, Object> customEngineProxies = new HashMap<>();

    private Map<String, String> originalLabels = new HashMap<>();
    private Map<String, String> originalSearchUrls = new HashMap<>();

    public SearchEngineHook(XC_LoadPackage.LoadPackageParam lpparam) {
        this.lpparam = lpparam;
    }

    public void setAppContext(Context context) {
        this.appContext = context;
        PrefsCache.refresh(appContext);
    }

    public void hook() {
        XposedBridge.log("[" + TAG + "] SearchEngineHook: Initializing");

        try {
            searchEngineInterface = XposedHelpers.findClass(
                    "com.heytap.browser.api.search.engine.c",
                    lpparam.classLoader
            );
            XposedBridge.log("[" + TAG + "] SearchEngineHook: found SearchEngine interface");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] SearchEngineHook: failed to find interface: " + t.getMessage());
        }

        try {
            searchEngineImplClass = XposedHelpers.findClass(
                    "com.heytap.browser.search.impl.engine.c",
                    lpparam.classLoader
            );
            XposedBridge.log("[" + TAG + "] SearchEngineHook: found SearchEngine impl class");
            hookSearchEngineImplClass(searchEngineImplClass);
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] SearchEngineHook: failed to find impl class: " + t.getMessage());
        }

        try {
            Class<?> defaultSearchEnginesClass = XposedHelpers.findClass(
                    "com.heytap.browser.search.impl.engine.DefaultSearchEngines",
                    lpparam.classLoader
            );
            XposedBridge.log("[" + TAG + "] SearchEngineHook: found DefaultSearchEngines class");

            hookMethodByName(defaultSearchEnginesClass, "o", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (!(result instanceof List)) return;

                    @SuppressWarnings("unchecked")
                    List<Object> engineList = new ArrayList<>((List<Object>) result);

                    XposedBridge.log("[" + TAG + "] o - original count: " + engineList.size());

                    currentDiscoveredKeys.clear();
                    PrefsCache.refresh(appContext);

                    for (Object engine : engineList) {
                        reportDiscoveredEngine(engine);
                    }

                    notifyDiscoverComplete();

                    List<Object> additionalEngines = createAdditionalEngines(engineList);
                    if (!additionalEngines.isEmpty()) {
                        engineList.addAll(additionalEngines);
                    }

                    List<Object> filteredList = filterEngineList(engineList);

                    XposedBridge.log("[" + TAG + "] o - final count: " + filteredList.size());
                    param.setResult(filteredList);
                }
            });

            hookMethodByName(defaultSearchEnginesClass, "d0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (!(result instanceof List)) return;

                    @SuppressWarnings("unchecked")
                    List<Object> engineList = new ArrayList<>((List<Object>) result);

                    currentDiscoveredKeys.clear();
                    PrefsCache.refresh(appContext);

                    for (Object engine : engineList) {
                        reportDiscoveredEngine(engine);
                    }

                    notifyDiscoverComplete();

                    List<Object> additionalEngines = createAdditionalEngines(engineList);
                    if (!additionalEngines.isEmpty()) {
                        engineList.addAll(additionalEngines);
                    }

                    List<Object> filteredList = filterEngineList(engineList);
                    param.setResult(filteredList);
                }
            });

            hookMethodByName(defaultSearchEnginesClass, "n0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.args.length == 0) return;
                        Object arg0 = param.args[0];
                        if (!(arg0 instanceof List)) return;

                        @SuppressWarnings("unchecked")
                        List<Object> engineList = (List<Object>) arg0;

                        PrefsCache.refresh(appContext);

                        for (Object engine : engineList) {
                            reportDiscoveredEngine(engine);
                        }

                        List<Object> resultList = filterEngineList(engineList);
                        param.args[0] = resultList;

                    } catch (Throwable t) {
                        XposedBridge.log("[" + TAG + "] n0 - error: " + t.getMessage());
                    }
                }
            });

            String[] listMethods = {"R", "c0", "f0"};
            for (String methodName : listMethods) {
                hookMethodByName(defaultSearchEnginesClass, methodName, createListHook(methodName));
            }

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
                            PrefsCache.EngineConfig config = PrefsCache.getEngineConfig(appContext, requestedKey);
                            if (config != null && config.enabled &&
                                    (!config.isBuiltin || config.isRemovedFromBrowser)) {
                                Object customEngine = getOrCreateCustomEngineProxy(config);
                                if (customEngine != null) {
                                    param.setResult(customEngine);
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

                    PrefsCache.EngineConfig config = PrefsCache.getEngineConfig(appContext, key);
                    if (config != null && !config.enabled) {
                        param.setResult(null);
                    }
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] SearchEngineHook: failed to find DefaultSearchEngines: " + t.getMessage());
        }
    }

    private boolean isProxy(Object obj) {
        if (obj == null) return false;
        if (Proxy.isProxyClass(obj.getClass())) {
            return true;
        }
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
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] failed to notify discover complete: " + t.getMessage());
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

        Map<String, PrefsCache.EngineConfig> configs = PrefsCache.getEngineConfigs(appContext);

        for (PrefsCache.EngineConfig config : configs.values()) {
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
                }
            }
        }

        return additionalEngines;
    }

    private Object getOrCreateCustomEngineProxy(PrefsCache.EngineConfig config) {
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
        Map<String, PrefsCache.EngineConfig> configs = PrefsCache.getEngineConfigs(appContext);

        for (Object engine : engineList) {
            String key = getKey(engine);
            if (key != null) {
                PrefsCache.EngineConfig config = configs.get(key);
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

                    String originalLabel = (String) param.getResult();

                    if (originalLabel != null) {
                        originalLabels.put(key, originalLabel);
                    }

                    PrefsCache.EngineConfig config = PrefsCache.getEngineConfig(appContext, key);
                    if (config != null && config.name != null && !config.name.isEmpty()) {
                        if (!config.name.equals(originalLabel)) {
                            param.setResult(config.name);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] failed to hook getLabel: " + t.getMessage());
        }

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
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] failed to hook v: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "q", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String query = (String) param.args[0];
                    String key = getKey(param.thisObject);
                    if (key == null) return;

                    PrefsCache.EngineConfig config = PrefsCache.getEngineConfig(appContext, key);
                    if (config != null && config.searchUrl != null && !config.searchUrl.isEmpty()) {
                        String newUrl = buildSearchUrl(config.searchUrl, query);
                        param.setResult(newUrl);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] failed to hook q: " + t.getMessage());
        }
    }

    private Object createCustomEngineProxy(final PrefsCache.EngineConfig config) {
        if (searchEngineInterface == null) {
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
            XposedBridge.log("[" + TAG + "] failed to create proxy: " + t.getMessage());
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

                PrefsCache.refresh(appContext);

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

    private void reportDiscoveredEngine(Object engine) {
        if (appContext == null || engine == null) return;

        if (isProxy(engine)) {
            return;
        }

        String key = getKey(engine);
        if (key == null) return;

        currentDiscoveredKeys.add(key);

        if (reportedEngines.contains(key)) {
            return;
        }

        getLabel(engine);
        getSearchUrl(engine);

        String label = originalLabels.get(key);
        String searchUrl = originalSearchUrls.get(key);

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

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] failed to report engine: " + t.getMessage());
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
            XposedBridge.log("[" + TAG + "] no methods found for " + methodName);
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
}