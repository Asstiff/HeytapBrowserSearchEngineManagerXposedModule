package com.example.xposedsearch;

public class SearchEngineConfig {
    public String key;
    public String name;
    public String searchUrl;
    public boolean enabled;

    public SearchEngineConfig() {
    }

    public SearchEngineConfig(String key, String name, String searchUrl, boolean enabled) {
        this.key = key;
        this.name = name;
        this.searchUrl = searchUrl;
        this.enabled = enabled;
    }
}