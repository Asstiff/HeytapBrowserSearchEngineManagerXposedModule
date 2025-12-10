// app/src/main/java/com/upuaut/xposedsearch/hooks/DarkWordHook.java
package com.upuaut.xposedsearch.hooks;

import android.content.Context;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.upuaut.xposedsearch.DarkWordPrefsCache;

public class DarkWordHook {

    private static final String TAG = "XposedSearch";

    private Context appContext;
    private XC_LoadPackage.LoadPackageParam lpparam;

    public DarkWordHook(XC_LoadPackage.LoadPackageParam lpparam) {
        this.lpparam = lpparam;
    }

    public void setAppContext(Context context) {
        this.appContext = context;
        DarkWordPrefsCache.refresh(appContext);
    }

    public void hook() {
        XposedBridge.log("[" + TAG + "] DarkWordHook: Initializing");

        // Hook 主入口方法
        hookDarkWordUpdater();

        // Hook 容器类的 getter 方法作为备用
        hookContainerGetters();
    }

    private void hookDarkWordUpdater() {
        try {
            Class<?> darkWordUpdaterClass = XposedHelpers.findClass(
                    "com.heytap.browser.search.darkword.n",
                    lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(darkWordUpdaterClass, "w", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (appContext == null) return;

                    DarkWordPrefsCache.refresh(appContext);

                    if (!DarkWordPrefsCache.isModuleEnabled()) return;
                    if (!DarkWordPrefsCache.isDarkWordDisabled()) return;

                    Object result = param.getResult();
                    if (result == null) return;

                    // 直接清空返回对象中的热词列表
                    boolean cleared = clearDarkWordList(result);
                    if (cleared) {
                        int scene = (int) param.args[0];
                        XposedBridge.log("[" + TAG + "] DarkWordHook: Cleared dark words for scene " + scene);
                    }
                }
            });

            XposedBridge.log("[" + TAG + "] DarkWordHook: Hooked n.w()");

        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] DarkWordHook: Failed to hook n.w(): " + t.getMessage());
        }
    }

    private void hookContainerGetters() {
        try {
            // Hook 容器类 p 中返回 List 的方法
            Class<?> containerClass = XposedHelpers.findClass(
                    "com.heytap.browser.api.search.page.p",
                    lpparam.classLoader
            );

            for (java.lang.reflect.Method method : containerClass.getDeclaredMethods()) {
                if (method.getReturnType() == List.class && method.getParameterCount() == 0) {
                    final String methodName = method.getName();

                    XposedHelpers.findAndHookMethod(containerClass, methodName, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (appContext == null) return;

                            DarkWordPrefsCache.refresh(appContext);

                            if (!DarkWordPrefsCache.isModuleEnabled()) return;
                            if (!DarkWordPrefsCache.isDarkWordDisabled()) return;

                            Object result = param.getResult();
                            if (!(result instanceof List)) return;

                            List<?> list = (List<?>) result;
                            if (list.isEmpty()) return;

                            // 检查是否是 DarkWord 列表
                            Object firstItem = list.get(0);
                            if (firstItem != null && firstItem.getClass().getName().contains("DarkWord")) {
                                list.clear();
                                XposedBridge.log("[" + TAG + "] DarkWordHook: Cleared list from p." + methodName);
                            }
                        }
                    });

                    XposedBridge.log("[" + TAG + "] DarkWordHook: Hooked p." + methodName);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] DarkWordHook: Failed to hook container: " + t.getMessage());
        }
    }

    private boolean clearDarkWordList(Object container) {
        // 尝试多个可能的字段名
        String[] possibleFields = {"e", "d", "f", "list", "mList", "darkWords"};

        for (String fieldName : possibleFields) {
            try {
                Object fieldValue = XposedHelpers.getObjectField(container, fieldName);
                if (fieldValue instanceof List) {
                    List<?> list = (List<?>) fieldValue;
                    if (!list.isEmpty()) {
                        // 检查第一个元素是否是 DarkWord 类型
                        Object first = list.get(0);
                        if (first != null && first.getClass().getName().contains("DarkWord")) {
                            list.clear();
                            XposedBridge.log("[" + TAG + "] DarkWordHook: Cleared field '" + fieldName + "'");
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 字段不存在或类型不对，继续尝试下一个
            }
        }

        // 如果上面的方法都失败，尝试遍历所有 List 类型的字段
        try {
            for (java.lang.reflect.Field field : container.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object fieldValue = field.get(container);
                    if (fieldValue instanceof List) {
                        List<?> list = (List<?>) fieldValue;
                        if (!list.isEmpty()) {
                            Object first = list.get(0);
                            if (first != null && first.getClass().getName().contains("DarkWord")) {
                                list.clear();
                                XposedBridge.log("[" + TAG + "] DarkWordHook: Cleared field '" + field.getName() + "' (by reflection)");
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] DarkWordHook: Reflection failed: " + t.getMessage());
        }

        return false;
    }
}