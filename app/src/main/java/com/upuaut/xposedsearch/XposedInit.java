// app/src/main/java/com/upuaut/xposedsearch/XposedInit.java
package com.upuaut.xposedsearch;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.upuaut.xposedsearch.hooks.SearchEngineHook;
import com.upuaut.xposedsearch.hooks.HotSitesHook;
import com.upuaut.xposedsearch.hooks.DarkWordHook;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "XposedSearch";
    private static final String TARGET_PACKAGE = "com.heytap.browser";

    private SearchEngineHook searchEngineHook;
    private HotSitesHook hotSitesHook;
    private DarkWordHook darkWordHook;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.upuaut.xposedsearch")) {
            hookSelf(lpparam);
            return;
        }

        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log("[" + TAG + "] Loaded in " + lpparam.packageName);

        searchEngineHook = new SearchEngineHook(lpparam);
        hotSitesHook = new HotSitesHook(lpparam);
        darkWordHook = new DarkWordHook(lpparam);

        XposedHelpers.findAndHookMethod(
                Application.class,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Application app = (Application) param.thisObject;
                        Context appContext = app.getApplicationContext();
                        XposedBridge.log("[" + TAG + "] Got Application context");

                        searchEngineHook.setAppContext(appContext);
                        hotSitesHook.setAppContext(appContext);
                        darkWordHook.setAppContext(appContext);
                    }
                }
        );

        try {
            searchEngineHook.hook();
            XposedBridge.log("[" + TAG + "] SearchEngineHook initialized");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Failed to init SearchEngineHook: " + t.getMessage());
        }

        try {
            hotSitesHook.hook();
            XposedBridge.log("[" + TAG + "] HotSitesHook initialized");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Failed to init HotSitesHook: " + t.getMessage());
        }

        try {
            darkWordHook.hook();
            XposedBridge.log("[" + TAG + "] DarkWordHook initialized");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Failed to init DarkWordHook: " + t.getMessage());
        }
    }

    private void hookSelf(XC_LoadPackage.LoadPackageParam lpparam) {
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
            XposedBridge.log("[" + TAG + "] Hooked isXposedModuleActive in self");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] Failed to hook self: " + t.getMessage());
        }
    }
}