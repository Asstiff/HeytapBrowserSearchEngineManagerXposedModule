package com.upuaut.xposedsearch.hooks;

import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.upuaut.xposedsearch.HotSitePrefsCache;

public class HotSitesHook {

    private static final String TAG = "XposedSearch";
    private static final String PROVIDER_HOTSITES_DISCOVER_URI = "content://com.upuaut.xposedsearch.provider/hotsites_discover";

    private XC_LoadPackage.LoadPackageParam lpparam;
    private Context mContext;

    // 未混淆的类路径（关键入口点）
    private static final String SIMPLE_HOTS_CONTAINER_CLASS =
            "com.heytap.browser.browser_navi.simple.ui.SimpleHotsContainer";

    // Target class names (may be obfuscated differently)
    private static final String ADAPTER_CLASS = "x71.c";
    private static final String DATA_CONTAINER_CLASS = "j71.c";
    private static final String ENTITY_CLASS = "j71.b";

    // Resolved classes
    private Class<?> adapterClass = null;
    private Class<?> dataContainerClass = null;
    private Class<?> entityClass = null;
    private Class<?> viewModelClass = null;

    // Entity field cache
    private Field entityNameField = null;
    private Field entityUrlField = null;
    private Field entityIconField = null;
    private Constructor<?> entityConstructor = null;

    // Adapter field cache
    private Field adapterDataField = null;

    private boolean classesResolved = false;
    private boolean hasReportedSites = false;
    private boolean isProcessing = false;

    public HotSitesHook(XC_LoadPackage.LoadPackageParam lpparam) {
        this.lpparam = lpparam;
    }

    public void setAppContext(Context context) {
        this.mContext = context;
    }

    public void hook() {
        // 1. Try direct class hooking first
        if (tryDirectHook()) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Direct hook successful");
            return;
        }

        XposedBridge.log("[" + TAG + "] HotSitesHook: Direct hook failed, trying dynamic discovery");

        // 2. Try dynamic discovery via SimpleHotsContainer
        if (tryDynamicDiscoveryViaContainer()) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Dynamic discovery successful");
            return;
        }

        // 3. Fallback to RecyclerView hook
        XposedBridge.log("[" + TAG + "] HotSitesHook: Falling back to RecyclerView hook");
        hookRecyclerViewFallback();
    }

    /**
     * 通过未混淆的 SimpleHotsContainer 动态发现混淆后的类
     */
    private boolean tryDynamicDiscoveryViaContainer() {
        try {
            // 1. 加载未混淆的 SimpleHotsContainer
            Class<?> containerClass = XposedHelpers.findClass(
                    SIMPLE_HOTS_CONTAINER_CLASS, lpparam.classLoader);
            XposedBridge.log("[" + TAG + "] HotSitesHook: Found SimpleHotsContainer");

            // 2. 通过方法 f(e) 找到 ViewModel 类 (w71.e)
            viewModelClass = findViewModelClass(containerClass);
            if (viewModelClass == null) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Could not find ViewModel class");
                return false;
            }
            XposedBridge.log("[" + TAG + "] HotSitesHook: Found ViewModel class: " + viewModelClass.getName());

            // 3. 通过 ViewModel.Z() 找到 Adapter 类 (x71.c)
            adapterClass = findAdapterClass(viewModelClass);
            if (adapterClass == null) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Could not find Adapter class");
                return false;
            }
            XposedBridge.log("[" + TAG + "] HotSitesHook: Found Adapter class: " + adapterClass.getName());

            // 4. 通过 Adapter 字段找到 Entity 类 (j71.b)
            entityClass = findEntityClass(adapterClass);
            if (entityClass == null) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Could not find Entity class");
                return false;
            }
            XposedBridge.log("[" + TAG + "] HotSitesHook: Found Entity class: " + entityClass.getName());

            // 5. 解析 Entity 字段
            if (!resolveEntityFields()) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Could not resolve Entity fields");
                return false;
            }

            // 6. 解析 Adapter 字段
            if (!resolveAdapterFields()) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Could not resolve Adapter fields");
                return false;
            }

            // 7. 找到数据容器类并 hook
            if (!hookAdapterDataMethod()) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Could not hook Adapter data method");
                return false;
            }

            classesResolved = true;
            return true;

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Dynamic discovery failed: " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    /**
     * 通过 SimpleHotsContainer.f(e) 或字段 b 找到 ViewModel 类
     */
    private Class<?> findViewModelClass(Class<?> containerClass) {
        // 方法1: 通过方法 f(e) 的参数类型
        for (Method method : containerClass.getDeclaredMethods()) {
            if (method.getName().equals("f") && method.getParameterCount() == 1) {
                Class<?> paramType = method.getParameterTypes()[0];
                // 验证：该类应该有 Z() 方法
                if (hasMethodReturningAdapter(paramType)) {
                    return paramType;
                }
            }
        }

        // 方法2: 通过方法 g(e) 的参数类型
        for (Method method : containerClass.getDeclaredMethods()) {
            if (method.getName().equals("g") && method.getParameterCount() == 1) {
                Class<?> paramType = method.getParameterTypes()[0];
                if (hasMethodReturningAdapter(paramType)) {
                    return paramType;
                }
            }
        }

        // 方法3: 通过方法 b(e) (attachViewModel) 的参数类型
        for (Method method : containerClass.getDeclaredMethods()) {
            if (method.getName().equals("b") && method.getParameterCount() == 1) {
                Class<?> paramType = method.getParameterTypes()[0];
                if (!paramType.isPrimitive() && hasMethodReturningAdapter(paramType)) {
                    return paramType;
                }
            }
        }

        // 方法4: 通过字段 b 的类型
        try {
            Field fieldB = containerClass.getDeclaredField("b");
            Class<?> type = fieldB.getType();
            if (hasMethodReturningAdapter(type)) {
                return type;
            }
        } catch (NoSuchFieldException ignored) {}

        // 方法5: 遍历所有字段，找有 Z() 方法的
        for (Field field : containerClass.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (!type.isPrimitive() && !type.getName().startsWith("android.")
                    && !type.getName().startsWith("java.")) {
                if (hasMethodReturningAdapter(type)) {
                    return type;
                }
            }
        }

        return null;
    }

    /**
     * 检查类是否有返回 RecyclerView.Adapter 的方法（可能叫 Z）
     */
    private boolean hasMethodReturningAdapter(Class<?> clazz) {
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getParameterCount() == 0) {
                    Class<?> returnType = method.getReturnType();
                    // 检查返回类型是否是 RecyclerView.Adapter 的子类
                    if (isRecyclerViewAdapter(returnType)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 检查类是否是 RecyclerView.Adapter 的子类
     */
    private boolean isRecyclerViewAdapter(Class<?> clazz) {
        if (clazz == null) return false;
        try {
            Class<?> adapterBase = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$Adapter", lpparam.classLoader);
            return adapterBase.isAssignableFrom(clazz);
        } catch (Throwable t) {
            // 备选：检查类名
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                if (current.getName().contains("Adapter")) {
                    return true;
                }
                current = current.getSuperclass();
            }
        }
        return false;
    }

    /**
     * 通过 ViewModel 的方法找到 Adapter 类（Z() 方法的返回类型）
     */
    private Class<?> findAdapterClass(Class<?> viewModelClass) {
        // 优先找 Z() 方法
        try {
            Method zMethod = viewModelClass.getDeclaredMethod("Z");
            Class<?> returnType = zMethod.getReturnType();
            if (isRecyclerViewAdapter(returnType)) {
                return returnType;
            }
        } catch (NoSuchMethodException ignored) {}

        // 遍历所有无参方法，找返回 Adapter 的
        for (Method method : viewModelClass.getDeclaredMethods()) {
            if (method.getParameterCount() == 0) {
                Class<?> returnType = method.getReturnType();
                if (isRecyclerViewAdapter(returnType)) {
                    XposedBridge.log("[" + TAG + "] HotSitesHook: Found adapter via method: " + method.getName());
                    return returnType;
                }
            }
        }

        return null;
    }

    /**
     * 通过 Adapter 的 List 字段找到 Entity 类
     */
    private Class<?> findEntityClass(Class<?> adapterClass) {
        for (Field field : adapterClass.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                // 尝试通过泛型获取元素类型
                java.lang.reflect.Type genericType = field.getGenericType();
                if (genericType instanceof java.lang.reflect.ParameterizedType) {
                    java.lang.reflect.ParameterizedType pt =
                            (java.lang.reflect.ParameterizedType) genericType;
                    java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                    if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                        Class<?> elementClass = (Class<?>) typeArgs[0];
                        // 验证：Entity 应该有 String 字段（name, url）
                        if (hasStringFields(elementClass)) {
                            adapterDataField = field;
                            adapterDataField.setAccessible(true);
                            return elementClass;
                        }
                    }
                }
            }
        }

        // 备选：通过方法签名推断
        for (Method method : adapterClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && !params[0].isPrimitive()
                    && !params[0].getName().startsWith("android.")
                    && !params[0].getName().startsWith("java.")) {

                // 检查该参数类型是否有获取 List 的方法
                for (Method m : params[0].getDeclaredMethods()) {
                    if (m.getReturnType() == List.class && m.getParameterCount() == 0) {
                        dataContainerClass = params[0];
                        // 尝试从该方法的泛型返回类型获取 Entity 类
                        java.lang.reflect.Type genericReturn = m.getGenericReturnType();
                        if (genericReturn instanceof java.lang.reflect.ParameterizedType) {
                            java.lang.reflect.ParameterizedType pt =
                                    (java.lang.reflect.ParameterizedType) genericReturn;
                            java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                                Class<?> elementClass = (Class<?>) typeArgs[0];
                                if (hasStringFields(elementClass)) {
                                    return elementClass;
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean hasStringFields(Class<?> clazz) {
        int stringFieldCount = 0;
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == String.class) {
                stringFieldCount++;
            }
        }
        return stringFieldCount >= 2; // 至少有 name 和 url
    }

    /**
     * 解析 Entity 类的字段（name, url, icon）
     */
    private boolean resolveEntityFields() {
        if (entityClass == null) return false;

        // 首先尝试已知字段名
        String[] nameFieldCandidates = {"e", "name", "title", "a", "b", "c", "d"};
        String[] urlFieldCandidates = {"f", "url", "link", "href", "a", "b", "c", "d", "e"};
        String[] iconFieldCandidates = {"h", "icon", "iconUrl", "image", "img", "g", "i", "j", "k"};

        // 尝试固定字段名
        for (String name : nameFieldCandidates) {
            try {
                Field f = entityClass.getDeclaredField(name);
                if (f.getType() == String.class) {
                    entityNameField = f;
                    entityNameField.setAccessible(true);
                    break;
                }
            } catch (NoSuchFieldException ignored) {}
        }

        for (String name : urlFieldCandidates) {
            try {
                Field f = entityClass.getDeclaredField(name);
                if (f.getType() == String.class && f != entityNameField) {
                    entityUrlField = f;
                    entityUrlField.setAccessible(true);
                    break;
                }
            } catch (NoSuchFieldException ignored) {}
        }

        for (String name : iconFieldCandidates) {
            try {
                Field f = entityClass.getDeclaredField(name);
                if (f.getType() == String.class && f != entityNameField && f != entityUrlField) {
                    entityIconField = f;
                    entityIconField.setAccessible(true);
                    break;
                }
            } catch (NoSuchFieldException ignored) {}
        }

        // 尝试找构造函数
        try {
            entityConstructor = entityClass.getConstructor(long.class);
        } catch (NoSuchMethodException e) {
            try {
                entityConstructor = entityClass.getDeclaredConstructor();
            } catch (NoSuchMethodException e2) {
                Constructor<?>[] constructors = entityClass.getDeclaredConstructors();
                if (constructors.length > 0) {
                    entityConstructor = constructors[0];
                }
            }
        }

        if (entityConstructor != null) {
            entityConstructor.setAccessible(true);
        }

        XposedBridge.log("[" + TAG + "] HotSitesHook: Entity fields resolved - name=" +
                (entityNameField != null ? entityNameField.getName() : "null") +
                ", url=" + (entityUrlField != null ? entityUrlField.getName() : "null") +
                ", icon=" + (entityIconField != null ? entityIconField.getName() : "null"));

        return entityUrlField != null; // URL 是必需的
    }

    /**
     * 解析 Adapter 的数据字段
     */
    private boolean resolveAdapterFields() {
        if (adapterClass == null) return false;

        if (adapterDataField != null) {
            return true; // 已经在 findEntityClass 中找到
        }

        // 查找 List 类型的字段
        for (Field field : adapterClass.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                adapterDataField = field;
                adapterDataField.setAccessible(true);
                XposedBridge.log("[" + TAG + "] HotSitesHook: Found adapter data field: " + field.getName());
                return true;
            }
        }

        return false;
    }

    /**
     * Hook Adapter 的数据设置方法
     */
    private boolean hookAdapterDataMethod() {
        if (adapterClass == null) return false;

        boolean hooked = false;

        // 查找接受单个参数的方法（数据容器）
        for (Method method : adapterClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) continue;

            Class<?> paramType = params[0];
            if (paramType.isPrimitive() || paramType == String.class) continue;

            // 检查参数类型是否有返回 List 的方法
            boolean hasListGetter = false;
            for (Method m : paramType.getDeclaredMethods()) {
                if (m.getReturnType() == List.class && m.getParameterCount() == 0) {
                    hasListGetter = true;
                    dataContainerClass = paramType;
                    break;
                }
            }

            if (hasListGetter) {
                final String methodName = method.getName();
                try {
                    XposedHelpers.findAndHookMethod(adapterClass, methodName, paramType,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    XposedBridge.log("[" + TAG + "] HotSitesHook: " + methodName + "() called");
                                    processAdapter(param.thisObject);
                                }
                            });
                    XposedBridge.log("[" + TAG + "] HotSitesHook: Hooked " +
                            adapterClass.getName() + "." + methodName + "(" + paramType.getName() + ")");
                    hooked = true;
                    break;
                } catch (Throwable t) {
                    XposedBridge.log("[" + TAG + "] HotSitesHook: Failed to hook " + methodName + ": " + t.getMessage());
                }
            }
        }

        // 备选：hook notifyDataSetChanged
        if (!hooked) {
            try {
                XposedHelpers.findAndHookMethod(adapterClass, "notifyDataSetChanged",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                processAdapter(param.thisObject);
                            }
                        });
                XposedBridge.log("[" + TAG + "] HotSitesHook: Hooked notifyDataSetChanged (fallback)");
                hooked = true;
            } catch (Throwable t) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Failed to hook notifyDataSetChanged: " + t.getMessage());
            }
        }

        return hooked;
    }

    private boolean tryDirectHook() {
        try {
            adapterClass = XposedHelpers.findClass(ADAPTER_CLASS, lpparam.classLoader);
            dataContainerClass = XposedHelpers.findClass(DATA_CONTAINER_CLASS, lpparam.classLoader);
            entityClass = XposedHelpers.findClass(ENTITY_CLASS, lpparam.classLoader);

            XposedBridge.log("[" + TAG + "] HotSitesHook: Found classes - adapter: " + ADAPTER_CLASS +
                    ", container: " + DATA_CONTAINER_CLASS + ", entity: " + ENTITY_CLASS);

            entityNameField = entityClass.getDeclaredField("e");
            entityNameField.setAccessible(true);

            entityUrlField = entityClass.getDeclaredField("f");
            entityUrlField.setAccessible(true);

            try {
                entityIconField = entityClass.getDeclaredField("h");
                entityIconField.setAccessible(true);
            } catch (NoSuchFieldException ignored) {
                for (String fieldName : new String[]{"g", "i", "k"}) {
                    try {
                        entityIconField = entityClass.getDeclaredField(fieldName);
                        entityIconField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored2) {}
                }
            }

            entityConstructor = entityClass.getConstructor(long.class);
            entityConstructor.setAccessible(true);

            adapterDataField = adapterClass.getDeclaredField("e");
            adapterDataField.setAccessible(true);

            classesResolved = true;

            XposedHelpers.findAndHookMethod(adapterClass, "U", dataContainerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("[" + TAG + "] HotSitesHook: U() method called");
                    processAdapter(param.thisObject);
                }
            });

            XposedBridge.log("[" + TAG + "] HotSitesHook: Hooked " + ADAPTER_CLASS + ".U()");
            return true;

        } catch (NoSuchFieldException e) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Direct hook failed - Field not found: " + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Direct hook failed - Method not found: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Direct hook failed: " + t.getMessage());
            return false;
        }
    }

    private void hookRecyclerViewFallback() {
        try {
            XposedHelpers.findAndHookMethod(
                    "androidx.recyclerview.widget.RecyclerView",
                    lpparam.classLoader,
                    "setAdapter",
                    "androidx.recyclerview.widget.RecyclerView.Adapter",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object adapter = param.args[0];
                            if (adapter == null) return;

                            String className = adapter.getClass().getName();

                            if (className.startsWith("android.") || className.startsWith("androidx.")) {
                                return;
                            }

                            if (isLikelyHotSitesAdapter(adapter)) {
                                XposedBridge.log("[" + TAG + "] HotSitesHook: Found likely adapter: " + className);
                                hookAdapterDataSetter(adapter);
                            }
                        }
                    }
            );
            XposedBridge.log("[" + TAG + "] HotSitesHook: RecyclerView fallback hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: RecyclerView fallback failed: " + t.getMessage());
        }
    }

    private boolean isLikelyHotSitesAdapter(Object adapter) {
        String className = adapter.getClass().getName();

        if (className.equals(ADAPTER_CLASS)) return true;

        try {
            for (Field field : adapter.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;

                field.setAccessible(true);
                Object value = field.get(adapter);

                if (!(value instanceof List)) continue;
                List<?> list = (List<?>) value;
                if (list.isEmpty()) continue;

                Object entity = list.get(0);
                if (entity != null && hasUrlField(entity)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private boolean hasUrlField(Object entity) {
        if (entity == null) return false;

        for (Field field : entity.getClass().getDeclaredFields()) {
            if (field.getType() != String.class) continue;

            try {
                field.setAccessible(true);
                String value = (String) field.get(entity);
                if (value != null && (value.startsWith("http://") || value.startsWith("https://"))) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        return false;
    }

    private void hookAdapterDataSetter(Object adapter) {
        try {
            Class<?> cls = adapter.getClass();

            for (Method method : cls.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) continue;

                Class<?> paramType = params[0];
                if (paramType.isPrimitive() || paramType == String.class) continue;

                boolean hasListGetter = false;
                for (Method m : paramType.getDeclaredMethods()) {
                    if (m.getReturnType() == List.class && m.getParameterCount() == 0) {
                        hasListGetter = true;
                        break;
                    }
                }

                if (hasListGetter) {
                    final String methodName = method.getName();
                    XposedHelpers.findAndHookMethod(cls, methodName, paramType, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log("[" + TAG + "] HotSitesHook: " + methodName + "() called (fallback)");
                            processAdapterDynamic(param.thisObject);
                        }
                    });
                    XposedBridge.log("[" + TAG + "] HotSitesHook: Hooked " + cls.getName() + "." + methodName);
                    return;
                }
            }

            XposedHelpers.findAndHookMethod(cls, "notifyDataSetChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    processAdapterDynamic(param.thisObject);
                }
            });
            XposedBridge.log("[" + TAG + "] HotSitesHook: Hooked notifyDataSetChanged on " + cls.getName());

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: hookAdapterDataSetter failed: " + t.getMessage());
        }
    }

    private void processAdapter(Object adapter) {
        if (isProcessing) return;
        isProcessing = true;

        try {
            Context context = getContext();
            if (context == null) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: No context available");
                return;
            }

            // 如果字段未解析，尝试动态发现
            if (adapterDataField == null) {
                for (Field field : adapter.getClass().getDeclaredFields()) {
                    if (List.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object value = field.get(adapter);
                        if (value instanceof List && !((List<?>) value).isEmpty()) {
                            adapterDataField = field;
                            break;
                        }
                    }
                }
            }

            if (adapterDataField == null) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Could not find data field");
                return;
            }

            @SuppressWarnings("unchecked")
            List<Object> originalList = (List<Object>) adapterDataField.get(adapter);
            if (originalList == null || originalList.isEmpty()) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Data list is null or empty");
                return;
            }

            XposedBridge.log("[" + TAG + "] HotSitesHook: Processing " + originalList.size() + " items");

            // 动态解析字段（如果尚未解析）
            if (entityUrlField == null) {
                Object sample = originalList.get(0);
                resolveEntityFieldsFromSample(sample);
            }

            // Report original sites
            if (!hasReportedSites) {
                reportDiscoveredSites(context, originalList);
                hasReportedSites = true;
            }

            // Check module
            HotSitePrefsCache.refresh(context);
            if (!HotSitePrefsCache.isModuleEnabled()) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Module disabled");
                return;
            }

            List<HotSitePrefsCache.SiteConfig> configs = HotSitePrefsCache.getSiteConfigs(context);
            if (configs.isEmpty()) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: No custom configs");
                return;
            }

            // Build replacement
            List<Object> newList = new ArrayList<>();
            for (HotSitePrefsCache.SiteConfig config : configs) {
                if (!config.enabled) continue;

                try {
                    Object site = createEntity(entityConstructor, config.id);
                    if (site == null) continue;

                    if (entityNameField != null) entityNameField.set(site, config.name);
                    if (entityUrlField != null) entityUrlField.set(site, config.url);
                    if (entityIconField != null && config.iconUrl != null && !config.iconUrl.isEmpty()) {
                        entityIconField.set(site, config.iconUrl);
                    }
                    newList.add(site);
                    XposedBridge.log("[" + TAG + "] HotSitesHook: Created: " + config.name + " -> " + config.url);
                } catch (Exception e) {
                    XposedBridge.log("[" + TAG + "] HotSitesHook: Entity creation failed: " + e.getMessage());
                }
            }

            if (!newList.isEmpty()) {
                originalList.clear();
                originalList.addAll(newList);
                XposedBridge.log("[" + TAG + "] HotSitesHook: Replaced with " + newList.size() + " sites");
            }

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: processAdapter failed: " + t.getMessage());
        } finally {
            isProcessing = false;
        }
    }

    /**
     * 从样本对象动态解析字段
     */
    private void resolveEntityFieldsFromSample(Object sample) {
        if (sample == null) return;

        Class<?> cls = sample.getClass();
        entityClass = cls;

        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            f.setAccessible(true);

            try {
                String val = (String) f.get(sample);
                if (val == null) continue;

                if (val.startsWith("http://") || val.startsWith("https://")) {
                    if (entityUrlField == null) entityUrlField = f;
                } else if (val.length() > 0 && val.length() < 50 && !val.contains("/")) {
                    if (entityNameField == null) entityNameField = f;
                } else if (val.contains(".png") || val.contains(".jpg") ||
                        val.contains(".ico") || val.contains(".webp")) {
                    if (entityIconField == null) entityIconField = f;
                }
            } catch (Throwable ignored) {}
        }

        // 获取构造函数
        if (entityConstructor == null) {
            entityConstructor = findSuitableConstructor(cls);
            if (entityConstructor != null) {
                entityConstructor.setAccessible(true);
            }
        }

        XposedBridge.log("[" + TAG + "] HotSitesHook: Dynamic fields resolved - name=" +
                (entityNameField != null ? entityNameField.getName() : "null") +
                ", url=" + (entityUrlField != null ? entityUrlField.getName() : "null") +
                ", icon=" + (entityIconField != null ? entityIconField.getName() : "null"));
    }

    private void processAdapterDynamic(Object adapter) {
        if (isProcessing) return;
        isProcessing = true;

        try {
            Context context = getContext();
            if (context == null) return;

            Field listField = null;
            List<Object> dataList = null;

            for (Field field : adapter.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;

                field.setAccessible(true);
                Object value = field.get(adapter);

                if (!(value instanceof List)) continue;
                List<?> list = (List<?>) value;
                if (list.isEmpty()) continue;

                Object entity = list.get(0);
                if (entity != null && hasUrlField(entity)) {
                    listField = field;
                    dataList = (List<Object>) value;
                    break;
                }
            }

            if (listField == null || dataList == null || dataList.isEmpty()) {
                return;
            }

            XposedBridge.log("[" + TAG + "] HotSitesHook: Dynamic processing " + dataList.size() + " items");

            Object sampleEntity = dataList.get(0);
            Class<?> entityCls = sampleEntity.getClass();

            Field nameField = null;
            Field urlField = null;
            Field iconField = null;

            for (Field f : entityCls.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);

                try {
                    String val = (String) f.get(sampleEntity);
                    if (val == null) continue;

                    if (val.startsWith("http://") || val.startsWith("https://")) {
                        if (urlField == null) urlField = f;
                    } else if (val.length() > 0 && val.length() < 30 && !val.contains("/") && !val.contains(".")) {
                        if (nameField == null) nameField = f;
                    } else if (val.contains(".png") || val.contains(".jpg") || val.contains(".ico") || val.contains(".webp")) {
                        if (iconField == null) iconField = f;
                    }
                } catch (Throwable ignored) {}
            }

            if (urlField == null) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: Could not find URL field");
                return;
            }

            if (!hasReportedSites) {
                reportDiscoveredSitesDynamic(context, dataList, nameField, urlField, iconField);
                hasReportedSites = true;
            }

            HotSitePrefsCache.refresh(context);
            if (!HotSitePrefsCache.isModuleEnabled()) return;

            List<HotSitePrefsCache.SiteConfig> configs = HotSitePrefsCache.getSiteConfigs(context);
            if (configs.isEmpty()) return;

            Constructor<?> constructor = findSuitableConstructor(entityCls);
            if (constructor == null) {
                XposedBridge.log("[" + TAG + "] HotSitesHook: No suitable constructor");
                return;
            }
            constructor.setAccessible(true);

            final Field fNameField = nameField;
            final Field fUrlField = urlField;
            final Field fIconField = iconField;

            List<Object> newList = new ArrayList<>();
            for (HotSitePrefsCache.SiteConfig config : configs) {
                if (!config.enabled) continue;

                try {
                    Object site = createEntity(constructor, config.id);
                    if (site == null) continue;

                    if (fNameField != null) fNameField.set(site, config.name);
                    fUrlField.set(site, config.url);
                    if (fIconField != null && config.iconUrl != null && !config.iconUrl.isEmpty()) {
                        fIconField.set(site, config.iconUrl);
                    }

                    newList.add(site);
                } catch (Exception e) {
                    XposedBridge.log("[" + TAG + "] HotSitesHook: Dynamic entity creation failed: " + e.getMessage());
                }
            }

            if (!newList.isEmpty()) {
                dataList.clear();
                dataList.addAll(newList);
                XposedBridge.log("[" + TAG + "] HotSitesHook: Dynamic replacement: " + newList.size() + " sites");
            }

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Dynamic processing failed: " + t.getMessage());
        } finally {
            isProcessing = false;
        }
    }

    private Constructor<?> findSuitableConstructor(Class<?> entityCls) {
        try {
            return entityCls.getConstructor(long.class);
        } catch (NoSuchMethodException ignored) {}

        try {
            return entityCls.getDeclaredConstructor();
        } catch (NoSuchMethodException ignored) {}

        Constructor<?>[] constructors = entityCls.getDeclaredConstructors();
        if (constructors.length > 0) {
            return constructors[0];
        }

        return null;
    }

    private Object createEntity(Constructor<?> constructor, long id) {
        if (constructor == null) return null;

        try {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length == 0) {
                return constructor.newInstance();
            } else if (paramTypes.length == 1 && paramTypes[0] == long.class) {
                return constructor.newInstance(id);
            } else {
                Object[] args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    args[i] = getDefaultValue(paramTypes[i]);
                    if (i == 0 && paramTypes[i] == long.class) {
                        args[i] = id;
                    }
                }
                return constructor.newInstance(args);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0;
        return null;
    }

    private void reportDiscoveredSites(Context context, List<Object> sites) {
        try {
            JSONArray array = new JSONArray();
            long baseId = System.currentTimeMillis();

            for (int i = 0; i < sites.size(); i++) {
                Object site = sites.get(i);
                JSONObject obj = new JSONObject();
                obj.put("id", baseId + i);
                if (entityNameField != null) {
                    obj.put("name", entityNameField.get(site));
                }
                if (entityUrlField != null) {
                    obj.put("url", entityUrlField.get(site));
                }
                if (entityIconField != null) {
                    String icon = (String) entityIconField.get(site);
                    if (icon != null && !icon.isEmpty()) {
                        obj.put("iconUrl", icon);
                    }
                }
                array.put(obj);
            }

            ContentValues values = new ContentValues();
            values.put("sites", array.toString());
            context.getContentResolver().insert(Uri.parse(PROVIDER_HOTSITES_DISCOVER_URI), values);

            XposedBridge.log("[" + TAG + "] HotSitesHook: Reported " + sites.size() + " discovered sites");

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Report failed: " + t.getMessage());
        }
    }

    private void reportDiscoveredSitesDynamic(Context context, List<Object> sites,
                                              Field nameField, Field urlField, Field iconField) {
        try {
            JSONArray array = new JSONArray();
            long baseId = System.currentTimeMillis();

            for (int i = 0; i < sites.size(); i++) {
                Object site = sites.get(i);
                JSONObject obj = new JSONObject();
                obj.put("id", baseId + i);
                if (nameField != null) {
                    obj.put("name", nameField.get(site));
                }
                obj.put("url", urlField.get(site));
                if (iconField != null) {
                    String icon = (String) iconField.get(site);
                    if (icon != null && !icon.isEmpty()) {
                        obj.put("iconUrl", icon);
                    }
                }
                array.put(obj);
            }

            ContentValues values = new ContentValues();
            values.put("sites", array.toString());
            context.getContentResolver().insert(Uri.parse(PROVIDER_HOTSITES_DISCOVER_URI), values);

            XposedBridge.log("[" + TAG + "] HotSitesHook: Reported " + sites.size() + " discovered sites (dynamic)");

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] HotSitesHook: Dynamic report failed: " + t.getMessage());
        }
    }

    private Context getContext() {
        if (mContext != null) return mContext;
        mContext = AndroidAppHelper.currentApplication();
        return mContext;
    }
}