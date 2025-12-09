package com.example.xposedsearch;

public class SearchEngineConfig {
    public String key;
    public String name;
    public String searchUrl;
    public boolean enabled;

    // 新增字段
    public boolean isBuiltin;        // 是否是浏览器内置引擎
    public boolean isModified;       // 用户是否修改过
    public String originalName;      // 原始名称（用于恢复）
    public String originalSearchUrl; // 原始 URL（用于恢复）

    public SearchEngineConfig() {
    }

    public SearchEngineConfig(String key, String name, String searchUrl, boolean enabled) {
        this.key = key;
        this.name = name;
        this.searchUrl = searchUrl;
        this.enabled = enabled;
        this.isBuiltin = false;
        this.isModified = false;
    }

    public SearchEngineConfig(String key, String name, String searchUrl, boolean enabled,
                              boolean isBuiltin, boolean isModified,
                              String originalName, String originalSearchUrl) {
        this.key = key;
        this.name = name;
        this.searchUrl = searchUrl;
        this.enabled = enabled;
        this.isBuiltin = isBuiltin;
        this.isModified = isModified;
        this.originalName = originalName;
        this.originalSearchUrl = originalSearchUrl;
    }

    /** 是否可以恢复默认 */
    public boolean canReset() {
        return isBuiltin && isModified;
    }

    /** 是否可以删除（只有用户自定义引擎可以删除） */
    public boolean canDelete() {
        return !isBuiltin;
    }
}