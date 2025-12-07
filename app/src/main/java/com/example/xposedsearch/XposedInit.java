package com.example.xposedsearch;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.heytap.browser";
    private static final String MODULE_PACKAGE = "com.example.xposedsearch";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("XposedSearch: loaded in " + lpparam.packageName);

        try {
            hookDefaultSearchEngines(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: hook failed: " + t);
        }
    }

    private void hookDefaultSearchEngines(final XC_LoadPackage.LoadPackageParam lpparam)
            throws ClassNotFoundException {

        final Class<?> defaultSearchEnginesClass =
                XposedHelpers.findClass(
                        "com.heytap.browser.search.impl.engine.DefaultSearchEngines",
                        lpparam.classLoader
                );

        try {
            XposedHelpers.findAndHookMethod(
                    defaultSearchEnginesClass,
                    "n0",
                    List.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object listObj = param.args[0];
                            if (!(listObj instanceof List)) {
                                return;
                            }
                            List<?> engineList = (List<?>) listObj;
                            if (engineList.isEmpty()) {
                                return;
                            }

                            List<SearchEngineConfig> configs = ConfigManager.loadEnginesForXposed();
                            if (configs == null || configs.isEmpty()) {
                                return;
                            }

                            Iterator<?> it = engineList.iterator();
                            while (it.hasNext()) {
                                Object engine = it.next(); // com.heytap.browser.api.search.engine.c
                                String key;
                                try {
                                    key = (String) XposedHelpers.callMethod(engine, "getKey");
                                } catch (Throwable e) {
                                    XposedBridge.log("XposedSearch: getKey failed in n0: " + e);
                                    continue;
                                }

                                SearchEngineConfig cfg = ConfigManager.findByKey(configs, key);
                                if (cfg != null && !cfg.enabled) {
                                    XposedBridge.log("XposedSearch: remove engine in n0, key=" + key);
                                    it.remove();
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: hook n0 failed: " + t);
        }

        try {
            final Class<?> searchEngineProtoClass = XposedHelpers.findClass(
                    "com.heytap.browser.platform.proto.PbOperationSearchEngine$SearchEngine",
                    lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(
                    defaultSearchEnginesClass,
                    "i0",
                    searchEngineProtoClass,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object searchEngine = param.args[0];
                            if (searchEngine == null) return;

                            String key;
                            try {
                                key = (String) XposedHelpers.callMethod(searchEngine, "getKey");
                            } catch (Throwable e) {
                                XposedBridge.log("XposedSearch: getKey failed in i0: " + e);
                                return;
                            }
                            if (key == null) return;

                            List<SearchEngineConfig> configs = ConfigManager.loadEnginesForXposed();
                            if (configs == null || configs.isEmpty()) {
                                return;
                            }
                            SearchEngineConfig cfg = ConfigManager.findByKey(configs, key);
                            if (cfg == null) {
                                return;
                            }

                            boolean hasCustomUrl = cfg.searchUrl != null && !cfg.searchUrl.isEmpty();
                            boolean hasCustomName = cfg.name != null && !cfg.name.isEmpty();

                            if (!hasCustomUrl && !hasCustomName) {
                                return;
                            }

                            try {
                                Object builder = XposedHelpers.callMethod(searchEngine, "toBuilder");
                                if (builder == null) return;

                                if (hasCustomUrl) {
                                    Object linkBuilder = XposedHelpers.callMethod(builder, "getLinkBuilder");
                                    if (linkBuilder != null) {
                                        XposedHelpers.callMethod(linkBuilder, "setUrl", cfg.searchUrl);
                                    }
                                }

                                if (hasCustomName) {
                                    try {
                                        Object labelBuilder = XposedHelpers.callMethod(builder, "getLabelBuilder");
                                        if (labelBuilder != null) {
                                            XposedHelpers.callMethod(labelBuilder, "setText", cfg.name);
                                        }
                                    } catch (Throwable e) {
                                        XposedBridge.log("XposedSearch: modify label failed in i0: " + e);
                                    }
                                }

                                Object newSearchEngine = XposedHelpers.callMethod(builder, "build");
                                if (newSearchEngine == null) return;

                                param.args[0] = newSearchEngine;

                                XposedBridge.log(
                                        "XposedSearch: override in i0, key=" + key +
                                                (hasCustomUrl ? (", url=" + cfg.searchUrl) : "") +
                                                (hasCustomName ? (", label=" + cfg.name) : "")
                                );
                            } catch (Throwable e) {
                                XposedBridge.log("XposedSearch: modify SearchEngine failed in i0: " + e);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("XposedSearch: hook i0 failed: " + t);
        }
    }

    @SuppressWarnings("unused")
    private Context getModuleContext(Context browserContext) {
        if (browserContext == null) {
            return null;
        }
        try {
            return browserContext.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE
            );
        } catch (PackageManager.NameNotFoundException e) {
            XposedBridge.log("XposedSearch: module context not found: " + e);
            return null;
        }
    }
}