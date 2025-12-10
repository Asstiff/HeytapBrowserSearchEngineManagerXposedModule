// app/src/main/java/com/upuaut/xposedsearch/HotSiteConfig.java
package com.upuaut.xposedsearch;

public class HotSiteConfig {
    public long id;
    public String name;
    public String url;
    public String iconUrl;
    public boolean enabled;
    public int order; // 排序顺序

    public HotSiteConfig() {
        this.enabled = true;
        this.order = 0;
    }

    public HotSiteConfig(long id, String name, String url, String iconUrl) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.iconUrl = iconUrl;
        this.enabled = true;
        this.order = 0;
    }

    /** 用于匹配（通过URL匹配） */
    public boolean matchesUrl(String otherUrl) {
        if (url == null || otherUrl == null) return false;
        return normalizeUrl(url).equals(normalizeUrl(otherUrl));
    }

    public static String normalizeUrl(String url) {
        if (url == null) return "";
        return url.toLowerCase()
                .replaceAll("^https?://", "")
                .replaceAll("^www\\.", "")
                .replaceAll("/$", "");
    }
}