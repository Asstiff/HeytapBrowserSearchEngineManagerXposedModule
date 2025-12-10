// app/src/main/java/com/upuaut/xposedsearch/hooks/SearchEngineHook.java
package com.upuaut.xposedsearch.hooks;

import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
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
    private static final String FIELD_CUSTOM_CONFIG = "xposed_custom_config";

    private Context appContext;
    private XC_LoadPackage.LoadPackageParam lpparam;
    private Set<String> reportedEngines = new HashSet<>();
    private Set<String> currentDiscoveredKeys = new HashSet<>();
    private Map<String, Object> customInstanceCache = new HashMap<>();

    private Class<?> searchEngineImplClass = null;

    // Dynamic method names
    private String methodGetLabel = null;
    private String methodGetSearchUrl = null;
    private String methodGetQuery = null;
    private String methodGetKey = null;

    // Original template engine info
    private String templateKeyOriginalValue = null;
    private String templateLabelOriginalValue = null;

    // 原始值缓存
    private Map<String, String> originalLabels = new HashMap<>();
    private Map<String, String> originalSearchUrls = new HashMap<>();

    private boolean isMappingResolved = false;
    private Object cachedDataModel = null;
    private Constructor<?> cachedConstructor = null;

    private int reportFailureCount = 0;
    private static final int MAX_REPORT_FAILURES = 3;

    public SearchEngineHook(XC_LoadPackage.LoadPackageParam lpparam) {
        this.lpparam = lpparam;
    }

    public void setAppContext(Context context) {
        this.appContext = context;
        if (context != null) {
            PrefsCache.refresh(context);
        }
    }

    private Context getContextOrFallback() {
        if (appContext != null) return appContext;
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx != null) {
            appContext = ctx;
            PrefsCache.refresh(appContext);
        }
        return appContext;
    }

    public void hook() {
        try {
            Class<?> defaultSearchEnginesClass = XposedHelpers.findClass(
                    "com.heytap.browser.search.impl.engine.DefaultSearchEngines",
                    lpparam.classLoader
            );

            // Hook n0 (refreshEngineList)
            XposedHelpers.findAndHookMethod(defaultSearchEnginesClass, "n0", List.class, boolean.class, boolean.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    processEngineList(param, 0);
                }
            });

            // Hook o (getEngineList)
            XposedHelpers.findAndHookMethod(defaultSearchEnginesClass, "o", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    processEngineList(param, -1);
                }
            });

            // Hook v (getEngineByKey)
            XposedHelpers.findAndHookMethod(defaultSearchEnginesClass, "v", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String requestedKey = (String) param.args[0];
                    if (getContextOrFallback() == null) return;

                    // 1. Emergency Init
                    if (!isMappingResolved || cachedConstructor == null) {
                        emergencyInit(param.thisObject);
                    }

                    // 2. Replace Return Value
                    if (isMappingResolved && cachedConstructor != null && requestedKey != null) {
                        PrefsCache.EngineConfig config = PrefsCache.getEngineConfig(appContext, requestedKey);

                        if (config != null && config.enabled) {
                            // 检查是否需要接管：
                            // 1. 不是内置引擎 (config.isBuiltin == false)
                            // 2. 是内置引擎，但当前发现列表里没有 (missing built-in)
                            boolean isMissingBuiltin = config.isBuiltin && !currentDiscoveredKeys.contains(config.key);

                            if (!config.isBuiltin || isMissingBuiltin) {
                                Object customInstance = getOrCreateCustomInstance(config);
                                if (customInstance != null) {
                                    param.setResult(customInstance);
                                    return;
                                }
                            }
                        }
                    }

                    // 3. Fallback Analysis
                    Object result = param.getResult();
                    if (result != null) {
                        resolveMappings(result);
                    }
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Failed to hook DefaultSearchEngines: " + t.getMessage());
        }
    }

    private void emergencyInit(Object defaultSearchEnginesInstance) {
        try {
            Field[] fields = defaultSearchEnginesInstance.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                if (List.class.isAssignableFrom(field.getType())) {
                    List<?> list = (List<?>) field.get(defaultSearchEnginesInstance);
                    if (list != null && !list.isEmpty()) {
                        Object sample = list.get(0);
                        if (sample != null && !Proxy.isProxyClass(sample.getClass())) {
                            resolveMappings(sample);
                            extractConstructorData(sample);
                            if (isMappingResolved) return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private void processEngineList(XC_MethodHook.MethodHookParam param, int index) {
        List<Object> list;
        if (index >= 0) list = (List<Object>) param.args[index];
        else list = (List<Object>) param.getResult();

        if (list == null || list.isEmpty()) return;
        getContextOrFallback();

        Object sample = list.get(0);
        resolveMappings(sample);
        extractConstructorData(sample);

        // 1. 统计当前浏览器真正返回了哪些 Key
        // 关键修改：只在 n0 (index >= 0) 时进行上报。
        // n0 是刷新列表，传入的是全新的、未被污染的列表。
        // o (index < 0) 是获取列表，返回的可能是已经被我们污染过（注入了自定义引擎）的列表。
        // 如果在 o 中上报，会导致我们注入的“消失的内置引擎”被误报为“存在”，从而无法标记为“已移除”。

        if (index >= 0) {
            reportedEngines.clear(); // 清除已上报缓存，确保全量上报给 Provider，以便计算差集
            currentDiscoveredKeys.clear();
        }

        Set<String> keysInCurrentList = new HashSet<>();
        for (Object engine : list) {
            String key = getKey(engine);
            if (key != null) {
                keysInCurrentList.add(key);

                // 只在 n0 (index >= 0) 时上报发现，保证数据源的纯净性
                if (index >= 0) {
                    reportDiscoveredEngine(engine);
                }
            }
        }

        // 只在 n0 时通知完成
        if (index >= 0) {
            notifyDiscoverComplete();
        }

        // 2. 创建额外的引擎（自定义 + 消失的内置）
        List<Object> additionalEngines = createAdditionalEngines(keysInCurrentList);

        List<Object> combinedList = new ArrayList<>(list);
        combinedList.addAll(additionalEngines);

        // 3. 过滤列表（处理禁用逻辑）
        List<Object> finalFilteredList = filterEngineList(combinedList);

        if (index >= 0) {
            try {
                list.clear();
                list.addAll(finalFilteredList);
            } catch (UnsupportedOperationException e) {
                param.args[index] = finalFilteredList;
            }
        } else {
            param.setResult(finalFilteredList);
        }
    }

    // ... resolveMappings, analyzeClassFeatures, extractConstructorData 保持不变 ...
    private synchronized void resolveMappings(Object sampleEngine) {
        if (isMappingResolved || sampleEngine == null || getContextOrFallback() == null) return;
        if (Proxy.isProxyClass(sampleEngine.getClass())) return;

        try {
            Class<?> clazz = sampleEngine.getClass();
            analyzeClassFeatures(clazz, sampleEngine);

            if (methodGetKey != null) {
                searchEngineImplClass = clazz;
                hookSearchEngineImplClass(searchEngineImplClass);
                isMappingResolved = true;
                XposedBridge.log("[" + TAG + "] Mappings resolved. Key method: " + methodGetKey);
            }
        } catch (Exception e) {
            XposedBridge.log("[" + TAG + "] Failed to resolve mappings: " + e.getMessage());
        }
    }

    private void analyzeClassFeatures(Class<?> clazz, Object instance) {
        Method[] methods = clazz.getDeclaredMethods();

        // 1. Find getQuery
        if (methodGetQuery == null) {
            for (Method m : methods) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                if (m.getReturnType() == String.class && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class) {
                    try {
                        String token = "XPOSED_TEST_TOKEN";
                        Object res = m.invoke(instance, token);
                        if (res instanceof String && ((String) res).contains(token)) {
                            methodGetQuery = m.getName();
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        // 2. Run all no-arg String methods
        Map<String, String> noArgResults = new HashMap<>();
        for (Method m : methods) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class && Modifier.isPublic(m.getModifiers())) {
                try {
                    Object res = m.invoke(instance);
                    if (res instanceof String) {
                        noArgResults.put(m.getName(), (String) res);
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 3. Analyze return values
        String foundUrl = null;
        String foundKey = null;
        String foundLabel = null;

        // Find URL
        for (Map.Entry<String, String> entry : noArgResults.entrySet()) {
            String val = entry.getValue();
            if (val.startsWith("http") && (val.contains("%s") || val.contains("{searchTerms}"))) {
                foundUrl = entry.getKey();
                break;
            }
        }

        // Find Key
        for (Map.Entry<String, String> entry : noArgResults.entrySet()) {
            String name = entry.getKey();
            String val = entry.getValue();
            if (name.equals(foundUrl) || name.equals("toString") || name.equals("hashCode")) continue;

            if (val.length() < 20 && val.matches("^[a-zA-Z0-9_]+$")) {
                if (foundKey == null || name.toLowerCase().contains("key")) {
                    foundKey = name;
                    templateKeyOriginalValue = val;
                }
            }
        }

        // Find Label
        for (Map.Entry<String, String> entry : noArgResults.entrySet()) {
            String name = entry.getKey();
            String val = entry.getValue();
            if (name.equals(foundUrl) || name.equals(foundKey) || name.equals("toString") || name.equals("hashCode")) continue;

            if (foundLabel == null || name.toLowerCase().contains("label") || name.toLowerCase().contains("name")) {
                foundLabel = name;
                templateLabelOriginalValue = val;
            }
        }

        if (foundUrl != null) methodGetSearchUrl = foundUrl;
        if (foundKey != null) methodGetKey = foundKey;
        if (foundLabel != null) methodGetLabel = foundLabel;
    }

    private void extractConstructorData(Object sampleEngine) {
        if (cachedConstructor != null && cachedDataModel != null) return;
        try {
            Constructor<?>[] constructors = sampleEngine.getClass().getDeclaredConstructors();
            for (Constructor<?> c : constructors) {
                Class<?>[] types = c.getParameterTypes();
                if (types.length == 2 && types[0] == Context.class) {
                    cachedConstructor = c;
                    cachedConstructor.setAccessible(true);

                    Class<?> dataModelType = types[1];
                    Method[] methods = sampleEngine.getClass().getDeclaredMethods();
                    for(Method m : methods) {
                        if(m.getReturnType() == dataModelType && m.getParameterTypes().length == 0) {
                            m.setAccessible(true);
                            cachedDataModel = m.invoke(sampleEngine);
                            break;
                        }
                    }
                    if (cachedDataModel == null) {
                        Field[] fields = sampleEngine.getClass().getDeclaredFields();
                        for (Field f : fields) {
                            f.setAccessible(true);
                            if (f.getType() == dataModelType) {
                                cachedDataModel = f.get(sampleEngine);
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookSearchEngineImplClass(Class<?> clazz) {
        // Hook getKey
        if (methodGetKey != null) {
            XposedHelpers.findAndHookMethod(clazz, methodGetKey, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    PrefsCache.EngineConfig config = (PrefsCache.EngineConfig)
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, FIELD_CUSTOM_CONFIG);
                    if (config != null) {
                        param.setResult(config.key);
                    }
                }
            });
        }

        // Hook getLabel
        if (methodGetLabel != null) {
            XposedHelpers.findAndHookMethod(clazz, methodGetLabel, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    PrefsCache.EngineConfig customConfig = (PrefsCache.EngineConfig)
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, FIELD_CUSTOM_CONFIG);

                    if (customConfig != null) {
                        // 自定义实例：直接返回配置名称
                        String label = customConfig.name;
                        if (label == null || label.isEmpty()) {
                            label = customConfig.key;
                        }
                        param.setResult(label);
                        return;
                    }

                    // 内置引擎：获取原始值并缓存
                    String key = getKeyInternal(param.thisObject);
                    if (key == null) return;

                    String originalLabel = (String) param.getResult();
                    if (originalLabel != null && !originalLabels.containsKey(key)) {
                        originalLabels.put(key, originalLabel);
                    }

                    // 检查是否有用户修改
                    if (getContextOrFallback() == null) return;
                    PrefsCache.EngineConfig config = PrefsCache.getEngineConfig(appContext, key);
                    if (config != null && config.name != null && !config.name.isEmpty()) {
                        param.setResult(config.name);
                    }
                }
            });
        }

        // Hook getSearchUrl
        if (methodGetSearchUrl != null) {
            XposedHelpers.findAndHookMethod(clazz, methodGetSearchUrl, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    PrefsCache.EngineConfig customConfig = (PrefsCache.EngineConfig)
                            XposedHelpers.getAdditionalInstanceField(param.thisObject, FIELD_CUSTOM_CONFIG);
                    if (customConfig != null) {
                        if (customConfig.searchUrl != null && !customConfig.searchUrl.isEmpty()) {
                            param.setResult(customConfig.searchUrl);
                        }
                        return;
                    }

                    String key = getKeyInternal(param.thisObject);
                    if (key == null) return;

                    String originalUrl = (String) param.getResult();
                    if (originalUrl != null && !originalUrl.isEmpty() && !originalSearchUrls.containsKey(key)) {
                        originalSearchUrls.put(key, originalUrl);
                    }

                    if (getContextOrFallback() == null) return;
                    PrefsCache.EngineConfig config = PrefsCache.getEngineConfig(appContext, key);
                    if (config != null && config.searchUrl != null && !config.searchUrl.isEmpty()) {
                        param.setResult(config.searchUrl);
                    }
                }
            });
        }

        // Hook getQuery
        if (methodGetQuery != null) {
            XposedHelpers.findAndHookMethod(clazz, methodGetQuery, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    PrefsCache.EngineConfig config = getEffectiveConfig(param.thisObject);
                    if (config != null && config.searchUrl != null && !config.searchUrl.isEmpty()) {
                        String query = (String) param.args[0];
                        param.setResult(buildSearchUrl(config.searchUrl, query));
                    }
                }
            });
        }
    }

    private String getKeyInternal(Object obj) {
        if (obj == null) return null;

        PrefsCache.EngineConfig config = (PrefsCache.EngineConfig)
                XposedHelpers.getAdditionalInstanceField(obj, FIELD_CUSTOM_CONFIG);
        if (config != null) {
            return config.key;
        }

        try {
            if (methodGetKey != null) {
                Object res = XposedHelpers.callMethod(obj, methodGetKey);
                return (res instanceof String) ? (String) res : null;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private PrefsCache.EngineConfig getEffectiveConfig(Object engineInstance) {
        PrefsCache.EngineConfig config = (PrefsCache.EngineConfig)
                XposedHelpers.getAdditionalInstanceField(engineInstance, FIELD_CUSTOM_CONFIG);
        if (config != null) {
            return config;
        }

        if (getContextOrFallback() == null) return null;

        String key = getKey(engineInstance);
        if (key == null) return null;

        return PrefsCache.getEngineConfig(appContext, key);
    }

    private Object getOrCreateCustomInstance(PrefsCache.EngineConfig config) {
        if (customInstanceCache.containsKey(config.key)) {
            return customInstanceCache.get(config.key);
        }

        if (cachedConstructor == null || cachedDataModel == null || getContextOrFallback() == null) return null;

        try {
            Object instance = cachedConstructor.newInstance(appContext, cachedDataModel);
            XposedHelpers.setAdditionalInstanceField(instance, FIELD_CUSTOM_CONFIG, config);

            if (templateKeyOriginalValue != null) {
                replaceStringField(instance, templateKeyOriginalValue, config.key);
            }

            if (templateLabelOriginalValue != null && config.name != null) {
                replaceStringField(instance, templateLabelOriginalValue, config.name);
            }

            clearResourceIds(instance);

            customInstanceCache.put(config.key, instance);
            return instance;
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Failed to create custom instance: " + t.getMessage());
            return null;
        }
    }

    private void clearResourceIds(Object engineObj) {
        if (engineObj == null) return;
        Field[] fields = engineObj.getClass().getDeclaredFields();
        for (Field field : fields) {
            try {
                field.setAccessible(true);
                if (field.getType() == int.class) {
                    int value = field.getInt(engineObj);
                    if (value > 10000) {
                        field.setInt(engineObj, 0);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void replaceStringField(Object instance, String targetValue, String newValue) {
        if (targetValue == null || newValue == null) return;
        Class<?> clazz = instance.getClass();
        while (clazz != null && clazz != Object.class) {
            Field[] fields = clazz.getDeclaredFields();
            for (Field f : fields) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    try {
                        String currentVal = (String) f.get(instance);
                        if (targetValue.equals(currentVal)) {
                            f.set(instance, newValue);
                        }
                    } catch (IllegalAccessException e) {
                        // ignore
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    // 核心修改：只要是启用的，且当前浏览器列表里没有的，统统补回去。
    private List<Object> createAdditionalEngines(Set<String> keysInCurrentList) {
        List<Object> additionalEngines = new ArrayList<>();

        // 用于防止重复添加
        Set<String> processedKeys = new HashSet<>(keysInCurrentList);

        Map<String, PrefsCache.EngineConfig> configs = PrefsCache.getEngineConfigs(appContext);
        for (PrefsCache.EngineConfig config : configs.values()) {
            // 如果已经在浏览器列表里，跳过
            if (processedKeys.contains(config.key)) continue;

            // 如果未启用或配置无效，跳过
            if (!config.enabled || config.searchUrl == null || config.searchUrl.isEmpty()) continue;

            // 只要 config.enabled == true，且当前列表里没有它，我们就手动注入。
            Object instance = getOrCreateCustomInstance(config);
            if (instance != null) {
                additionalEngines.add(instance);
                processedKeys.add(config.key);
            }
        }
        return additionalEngines;
    }

    private List<Object> filterEngineList(List<Object> engineList) {
        List<Object> filteredList = new ArrayList<>();
        Map<String, PrefsCache.EngineConfig> configs = PrefsCache.getEngineConfigs(appContext);

        for (Object engine : engineList) {
            String key;
            PrefsCache.EngineConfig customConfig = (PrefsCache.EngineConfig) XposedHelpers.getAdditionalInstanceField(engine, FIELD_CUSTOM_CONFIG);
            if (customConfig != null) {
                key = customConfig.key;
            } else {
                key = getKey(engine);
            }

            if (key != null) {
                PrefsCache.EngineConfig config = configs.get(key);
                // 如果配置存在，必须是 enabled 才能显示
                // 如果配置不存在（比如浏览器自带的新引擎），默认显示
                if (config == null || config.enabled) {
                    filteredList.add(engine);
                }
            } else {
                filteredList.add(engine);
            }
        }
        return filteredList;
    }

    private String buildSearchUrl(String template, String query) {
        if (template == null) return "";
        if (query == null) query = "";
        String encodedQuery;
        try { encodedQuery = URLEncoder.encode(query, "UTF-8"); } catch (Exception e) { encodedQuery = query; }
        if (template.contains("{searchTerms}")) return template.replace("{searchTerms}", encodedQuery);
        if (template.contains("%s")) return template.replace("%s", encodedQuery);
        return template + (template.contains("?") ? "&q=" : "?q=") + encodedQuery;
    }

    private void reportDiscoveredEngine(Object engine) {
        if (appContext == null || engine == null) return;

        if (XposedHelpers.getAdditionalInstanceField(engine, FIELD_CUSTOM_CONFIG) != null) return;

        if (reportFailureCount >= MAX_REPORT_FAILURES) return;

        String key = getKey(engine);
        if (key == null) return;

        currentDiscoveredKeys.add(key);

        if (reportedEngines.contains(key)) return;

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
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("name", label != null ? label : key);
            if (searchUrl != null && !searchUrl.isEmpty()) {
                values.put("searchUrl", searchUrl);
            }
            appContext.getContentResolver().insert(Uri.parse(PROVIDER_DISCOVER_URI), values);
            reportedEngines.add(key);
            reportFailureCount = 0;
        } catch (Throwable t) {
            reportFailureCount++;
            XposedBridge.log("[" + TAG + "] Failed to report engine: " + t.getMessage());
        }
    }

    private void notifyDiscoverComplete() {
        if (appContext == null || reportFailureCount >= MAX_REPORT_FAILURES) return;
        try {
            appContext.getContentResolver().insert(Uri.parse(PROVIDER_DISCOVER_COMPLETE_URI), new ContentValues());
            reportFailureCount = 0;
        } catch (Throwable t) {
            reportFailureCount++;
        }
    }

    private String getKey(Object obj) {
        if (obj == null) return null;
        try {
            if (methodGetKey != null) {
                Object res = XposedHelpers.callMethod(obj, methodGetKey);
                return (res instanceof String) ? (String) res : null;
            }
            return (String) XposedHelpers.callMethod(obj, "getKey");
        } catch (Throwable t) { return null; }
    }

    private String getLabel(Object obj) {
        if (obj == null) return null;
        try {
            if (methodGetLabel != null) {
                Object res = XposedHelpers.callMethod(obj, methodGetLabel);
                return (res instanceof String) ? (String) res : null;
            }
            return (String) XposedHelpers.callMethod(obj, "getLabel");
        } catch (Throwable t) { return null; }
    }

    private String getSearchUrl(Object obj) {
        if (obj == null) return null;
        try {
            if (methodGetSearchUrl != null) {
                Object res = XposedHelpers.callMethod(obj, methodGetSearchUrl);
                return (res instanceof String) ? (String) res : null;
            }
            return (String) XposedHelpers.callMethod(obj, "getSearchUrl");
        } catch (Throwable t) { return null; }
    }
}