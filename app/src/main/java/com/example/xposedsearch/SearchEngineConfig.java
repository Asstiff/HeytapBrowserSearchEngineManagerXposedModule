package com.example.xposedsearch;

public class SearchEngineConfig {
    public String key;
    public String name;
    public String searchUrl;
    public boolean enabled;

    // 原有字段
    public boolean isBuiltin;        // 是否是浏览器内置引擎
    public boolean isModified;       // 用户是否修改过
    public String originalName;      // 原始名称（用于恢复）
    public String originalSearchUrl; // 原始 URL（用于恢复）

    // 新增字段 - 用于追踪浏览器更新
    public boolean hasUpdate;              // 是否有可用更新（浏览器数据已更新但未同步）
    public String pendingName;             // 待更新的名称
    public String pendingSearchUrl;        // 待更新的 URL
    public boolean isRemovedFromBrowser;   // 是否已从浏览器内置列表消失

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
        return isBuiltin && isModified && !isRemovedFromBrowser;
    }

    /** 是否可以删除（只有用户自定义引擎可以删除） */
    public boolean canDelete() {
        return !isBuiltin;
    }

    /** 检查是否有待更新的信息 */
    public boolean hasPendingUpdate() {
        return hasUpdate && (pendingName != null || pendingSearchUrl != null);
    }

    /** 获取更新描述 */
    public String getUpdateDescription() {
        StringBuilder sb = new StringBuilder();
        if (pendingName != null && !pendingName.equals(originalName)) {
            sb.append("名称: ").append(originalName).append(" → ").append(pendingName);
        }
        if (pendingSearchUrl != null && !pendingSearchUrl.equals(originalSearchUrl)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("URL: ").append(originalSearchUrl != null ? originalSearchUrl : "(无)");
            sb.append(" → ").append(pendingSearchUrl);
        }
        return sb.toString();
    }
}