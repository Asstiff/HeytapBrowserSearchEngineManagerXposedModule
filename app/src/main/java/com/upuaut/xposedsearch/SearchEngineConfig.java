package com.upuaut.xposedsearch;

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

    // 用于追踪浏览器更新
    public boolean hasUpdate;              // 是否有可用更新（浏览器数据已更新但未同步）
    public String pendingName;             // 待更新的名称
    public String pendingSearchUrl;        // 待更新的 URL
    public boolean isRemovedFromBrowser;   // 是否已从浏览器内置列表消失

    // 自定义引擎与内置引擎冲突
    public boolean hasBuiltinConflict;     // 自定义引擎的 key 与新出现的内置引擎冲突
    public String conflictBuiltinName;     // 冲突的内置引擎名称
    public String conflictBuiltinSearchUrl; // 冲突的内置引擎 URL

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
        if (!hasUpdate) return false;
        // 检查是否真的有变化
        boolean nameChanged = pendingName != null && !pendingName.equals(originalName);
        boolean urlChanged = pendingSearchUrl != null && !pendingSearchUrl.isEmpty()
                && !pendingSearchUrl.equals(originalSearchUrl);
        return nameChanged || urlChanged;
    }

    /** 检查是否有内置引擎冲突 */
    public boolean hasBuiltinConflict() {
        return !isBuiltin && hasBuiltinConflict;
    }

    /** 获取更新描述 */
    public String getUpdateDescription() {
        StringBuilder sb = new StringBuilder();

        // 检查名称是否有变化
        boolean nameChanged = pendingName != null && !pendingName.equals(originalName);
        if (nameChanged) {
            sb.append("名称: ").append(originalName != null ? originalName : "(无)");
            sb.append(" → ").append(pendingName);
        }

        // 检查 URL 是否有变化
        boolean urlChanged = pendingSearchUrl != null && !pendingSearchUrl.isEmpty()
                && !pendingSearchUrl.equals(originalSearchUrl);
        if (urlChanged) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("URL: ").append(originalSearchUrl != null ? originalSearchUrl : "(无)");
            sb.append(" → ").append(pendingSearchUrl);
        }

        return sb.toString();
    }

    /** 获取冲突描述 */
    public String getConflictDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("浏览器新增了同名的内置引擎：\n\n");
        if (conflictBuiltinName != null) {
            sb.append("名称: ").append(conflictBuiltinName).append("\n");
        }
        if (conflictBuiltinSearchUrl != null) {
            sb.append("URL: ").append(conflictBuiltinSearchUrl);
        }
        return sb.toString();
    }
}