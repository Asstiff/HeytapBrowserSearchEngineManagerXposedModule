// app/src/main/java/com/upuaut/xposedsearch/SearchEngineProvider.java
package com.upuaut.xposedsearch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SearchEngineProvider extends ContentProvider {

    private static final String TAG = "XposedSearch";

    public static final String AUTHORITY = ConfigManager.AUTHORITY;

    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/engines");
    public static final Uri DISCOVER_URI = Uri.parse("content://" + AUTHORITY + "/discover");
    public static final Uri DISCOVER_COMPLETE_URI = Uri.parse("content://" + AUTHORITY + "/discover_complete");

    public static final Uri HOTSITES_URI = Uri.parse("content://" + AUTHORITY + "/hotsites");
    public static final Uri HOTSITES_DISCOVER_URI = Uri.parse("content://" + AUTHORITY + "/hotsites_discover");

    public static final Uri DARKWORD_URI = Uri.parse("content://" + AUTHORITY + "/darkword");

    private static final int CODE_ENGINES = 1;
    private static final int CODE_DISCOVER = 2;
    private static final int CODE_DISCOVER_COMPLETE = 3;
    private static final int CODE_HOTSITES = 4;
    private static final int CODE_HOTSITES_DISCOVER = 5;
    private static final int CODE_DARKWORD = 6;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, "engines", CODE_ENGINES);
        uriMatcher.addURI(AUTHORITY, "discover", CODE_DISCOVER);
        uriMatcher.addURI(AUTHORITY, "discover_complete", CODE_DISCOVER_COMPLETE);
        uriMatcher.addURI(AUTHORITY, "hotsites", CODE_HOTSITES);
        uriMatcher.addURI(AUTHORITY, "hotsites_discover", CODE_HOTSITES_DISCOVER);
        uriMatcher.addURI(AUTHORITY, "darkword", CODE_DARKWORD);
    }

    private Set<String> currentDiscoveredKeys = new HashSet<>();

    @Override
    public boolean onCreate() {
        Log.d(TAG, "[Provider] onCreate");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        int match = uriMatcher.match(uri);

        switch (match) {
            case CODE_ENGINES: {
                List<SearchEngineConfig> engines = ConfigManager.loadEngines(getContext());
                return buildEnginesCursor(engines);
            }
            case CODE_HOTSITES: {
                List<HotSiteConfig> sites = HotSiteConfigManager.loadSites(getContext());
                boolean moduleEnabled = HotSiteConfigManager.isModuleEnabled(getContext());
                return buildHotSitesCursor(sites, moduleEnabled);
            }
            case CODE_DARKWORD: {
                boolean moduleEnabled = DarkWordConfigManager.isModuleEnabled(getContext());
                boolean darkWordDisabled = DarkWordConfigManager.isDarkWordDisabled(getContext());
                return buildDarkWordCursor(moduleEnabled, darkWordDisabled);
            }
        }

        return null;
    }

    private MatrixCursor buildEnginesCursor(List<SearchEngineConfig> engines) {
        String[] columns = {
                "key", "name", "searchUrl", "enabled",
                "isBuiltin", "isModified", "originalName", "originalSearchUrl",
                "hasUpdate", "pendingName", "pendingSearchUrl", "isRemovedFromBrowser",
                "hasBuiltinConflict", "conflictBuiltinName", "conflictBuiltinSearchUrl"
        };
        MatrixCursor cursor = new MatrixCursor(columns);

        for (SearchEngineConfig cfg : engines) {
            cursor.addRow(new Object[]{
                    cfg.key, cfg.name, cfg.searchUrl, cfg.enabled ? 1 : 0,
                    cfg.isBuiltin ? 1 : 0, cfg.isModified ? 1 : 0,
                    cfg.originalName != null ? cfg.originalName : "",
                    cfg.originalSearchUrl != null ? cfg.originalSearchUrl : "",
                    cfg.hasUpdate ? 1 : 0,
                    cfg.pendingName != null ? cfg.pendingName : "",
                    cfg.pendingSearchUrl != null ? cfg.pendingSearchUrl : "",
                    cfg.isRemovedFromBrowser ? 1 : 0,
                    cfg.hasBuiltinConflict ? 1 : 0,
                    cfg.conflictBuiltinName != null ? cfg.conflictBuiltinName : "",
                    cfg.conflictBuiltinSearchUrl != null ? cfg.conflictBuiltinSearchUrl : ""
            });
        }

        return cursor;
    }

    private MatrixCursor buildHotSitesCursor(List<HotSiteConfig> sites, boolean moduleEnabled) {
        String[] columns = {"id", "name", "url", "iconUrl", "enabled", "order", "moduleEnabled"};
        MatrixCursor cursor = new MatrixCursor(columns);

        for (HotSiteConfig cfg : sites) {
            cursor.addRow(new Object[]{
                    cfg.id,
                    cfg.name != null ? cfg.name : "",
                    cfg.url != null ? cfg.url : "",
                    cfg.iconUrl != null ? cfg.iconUrl : "",
                    cfg.enabled ? 1 : 0,
                    cfg.order,
                    moduleEnabled ? 1 : 0
            });
        }

        return cursor;
    }

    private MatrixCursor buildDarkWordCursor(boolean moduleEnabled, boolean darkWordDisabled) {
        String[] columns = {"moduleEnabled", "darkWordDisabled"};
        MatrixCursor cursor = new MatrixCursor(columns);

        // 只返回一行，包含状态信息
        cursor.addRow(new Object[]{
                moduleEnabled ? 1 : 0,
                darkWordDisabled ? 1 : 0
        });

        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values == null) return null;

        int match = uriMatcher.match(uri);

        switch (match) {
            case CODE_DISCOVER: {
                String key = values.getAsString("key");
                String name = values.getAsString("name");
                String searchUrl = values.getAsString("searchUrl");

                if (key != null && !key.isEmpty()) {
                    currentDiscoveredKeys.add(key);
                    ConfigManager.handleDiscoveredEngine(getContext(), key, name, searchUrl);
                    return Uri.withAppendedPath(CONTENT_URI, key);
                }
                break;
            }

            case CODE_DISCOVER_COMPLETE: {
                if (!currentDiscoveredKeys.isEmpty()) {
                    ConfigManager.markMissingEnginesAsRemoved(getContext(), currentDiscoveredKeys);
                    currentDiscoveredKeys.clear();
                }
                return DISCOVER_COMPLETE_URI;
            }

            case CODE_HOTSITES_DISCOVER: {
                String sitesJson = values.getAsString("sites");
                if (sitesJson != null && !sitesJson.isEmpty()) {
                    try {
                        JSONArray array = new JSONArray(sitesJson);
                        List<HotSiteConfig> discoveredSites = new ArrayList<>();

                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            HotSiteConfig site = new HotSiteConfig();
                            site.id = obj.optLong("id", System.currentTimeMillis());
                            site.name = obj.optString("name", "");
                            site.url = obj.optString("url", "");
                            site.iconUrl = obj.optString("iconUrl", "");
                            site.enabled = true;
                            site.order = i;

                            if (!site.url.isEmpty()) {
                                discoveredSites.add(site);
                            }
                        }

                        HotSiteConfigManager.handleDiscoveredSites(getContext(), discoveredSites);

                    } catch (Exception e) {
                        Log.e(TAG, "[Provider] Failed to parse discovered sites: " + e.getMessage());
                    }
                }
                return HOTSITES_DISCOVER_URI;
            }
        }

        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.xposedsearch";
    }
}