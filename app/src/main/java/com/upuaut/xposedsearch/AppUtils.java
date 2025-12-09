package com.upuaut.xposedsearch;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

public class AppUtils {

    private static final String PREF_NAME = "app_settings";
    private static final String KEY_ICON_HIDDEN = "icon_hidden";

    /**
     * 设置是否隐藏桌面图标
     */
    public static void setIconHidden(Context context, boolean hidden) {
        PackageManager pm = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, "com.upuaut.xposedsearch.MainActivityAlias");

        int newState = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

        pm.setComponentEnabledSetting(
                componentName,
                newState,
                PackageManager.DONT_KILL_APP
        );

        // 保存设置
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_ICON_HIDDEN, hidden).apply();
    }

    /**
     * 获取图标是否隐藏
     */
    public static boolean isIconHidden(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ICON_HIDDEN, false);
    }

    /**
     * 检查图标实际状态
     */
    public static boolean isIconActuallyHidden(Context context) {
        PackageManager pm = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, "com.upuaut.xposedsearch.MainActivityAlias");

        int state = pm.getComponentEnabledSetting(componentName);
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}