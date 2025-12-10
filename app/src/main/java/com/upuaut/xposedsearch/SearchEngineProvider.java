// SearchEngineProvider.java
package com.upuaut.xposedsearch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SearchEngineProvider extends ContentProvider {

    private static final String TAG = "XposedSearch";

    public static final String AUTHORITY = ConfigManager.AUTHORITY;
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/engines");
    public static final Uri DISCOVER_URI = Uri.parse("content://" + AUTHORITY + "/discover");
    public static final Uri DISCOVER_COMPLETE_URI = Uri.parse("content://" + AUTHORITY + "/discover_complete");

    private static final int CODE_ENGINES = 1;
    private static final int CODE_DISCOVER = 2;
    private static final int CODE_DISCOVER_COMPLETE = 3;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, "engines", CODE_ENGINES);
        uriMatcher.addURI(AUTHORITY, "discover", CODE_DISCOVER);
        uriMatcher.addURI(AUTHORITY, "discover_complete", CODE_DISCOVER_COMPLETE);
    }

    // 列名
    public static final String COL_KEY = "key";
    public static final String COL_NAME = "name";
    public static final String COL_SEARCH_URL = "searchUrl";
    public static final String COL_ENABLED = "enabled";
    public static final String COL_IS_BUILTIN = "isBuiltin";
    public static final String COL_IS_MODIFIED = "isModified";
    public static final String COL_ORIGINAL_NAME = "originalName";
    public static final String COL_ORIGINAL_SEARCH_URL = "originalSearchUrl";
    public static final String COL_HAS_UPDATE = "hasUpdate";
    public static final String COL_PENDING_NAME = "pendingName";
    public static final String COL_PENDING_SEARCH_URL = "pendingSearchUrl";
    public static final String COL_IS_REMOVED = "isRemovedFromBrowser";
    public static final String COL_HAS_BUILTIN_CONFLICT = "hasBuiltinConflict";
    public static final String COL_CONFLICT_BUILTIN_NAME = "conflictBuiltinName";
    public static final String COL_CONFLICT_BUILTIN_SEARCH_URL = "conflictBuiltinSearchUrl";

    // 用于追踪本次发现的引擎
    private Set<String> currentDiscoveredKeys = new HashSet<>();

    @Override
    public boolean onCreate() {
        Log.d(TAG, "[Provider] onCreate");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Log.d(TAG, "[Provider] query called, uri=" + uri);

        if (uriMatcher.match(uri) == CODE_ENGINES) {
            List<SearchEngineConfig> engines = ConfigManager.loadEngines(getContext());
            Log.d(TAG, "[Provider] loaded engines count=" + engines.size());
            return buildCursor(engines);
        }

        return null;
    }

    private MatrixCursor buildCursor(List<SearchEngineConfig> engines) {
        String[] columns = {
                COL_KEY, COL_NAME, COL_SEARCH_URL, COL_ENABLED,
                COL_IS_BUILTIN, COL_IS_MODIFIED, COL_ORIGINAL_NAME, COL_ORIGINAL_SEARCH_URL,
                COL_HAS_UPDATE, COL_PENDING_NAME, COL_PENDING_SEARCH_URL, COL_IS_REMOVED,
                COL_HAS_BUILTIN_CONFLICT, COL_CONFLICT_BUILTIN_NAME, COL_CONFLICT_BUILTIN_SEARCH_URL
        };
        MatrixCursor cursor = new MatrixCursor(columns);

        for (SearchEngineConfig cfg : engines) {
            cursor.addRow(new Object[]{
                    cfg.key,
                    cfg.name,
                    cfg.searchUrl,
                    cfg.enabled ? 1 : 0,
                    cfg.isBuiltin ? 1 : 0,
                    cfg.isModified ? 1 : 0,
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

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Log.d(TAG, "[Provider] insert called, uri=" + uri);
        if (values == null) return null;

        int match = uriMatcher.match(uri);

        if (match == CODE_DISCOVER) {
            // 从浏览器发现引擎
            String key = values.getAsString("key");
            String name = values.getAsString("name");
            String searchUrl = values.getAsString("searchUrl");

            if (key != null && !key.isEmpty()) {
                currentDiscoveredKeys.add(key);
                ConfigManager.handleDiscoveredEngine(getContext(), key, name, searchUrl);
                return Uri.withAppendedPath(CONTENT_URI, key);
            }

        } else if (match == CODE_DISCOVER_COMPLETE) {
            // 发现完成
            if (!currentDiscoveredKeys.isEmpty()) {
                ConfigManager.markMissingEnginesAsRemoved(getContext(), currentDiscoveredKeys);
                currentDiscoveredKeys.clear();
            }
            return DISCOVER_COMPLETE_URI;
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
        return "vnd.android.cursor.dir/vnd.xposedsearch.engine";
    }
}