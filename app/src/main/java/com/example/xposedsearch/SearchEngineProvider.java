package com.example.xposedsearch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class SearchEngineProvider extends ContentProvider {

    private static final String TAG = "XposedSearch";
    public static final String AUTHORITY = "com.example.xposedsearch.engines";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/engines");
    public static final Uri DISCOVER_URI = Uri.parse("content://" + AUTHORITY + "/discover");

    private static final int CODE_ENGINES = 1;
    private static final int CODE_DISCOVER = 2;
    private static final int CODE_ENGINE_ITEM = 3;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, "engines", CODE_ENGINES);
        uriMatcher.addURI(AUTHORITY, "discover", CODE_DISCOVER);
        uriMatcher.addURI(AUTHORITY, "engines/*", CODE_ENGINE_ITEM);
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

    @Override
    public boolean onCreate() {
        Log.d(TAG, "[Provider] onCreate");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Log.d(TAG, "[Provider] query called, uri=" + uri);

        int match = uriMatcher.match(uri);

        if (match == CODE_ENGINES) {
            List<SearchEngineConfig> engines = ConfigManager.loadEngines(getContext());
            Log.d(TAG, "[Provider] loaded engines count=" + engines.size());
            return buildCursor(engines);

        } else if (match == CODE_ENGINE_ITEM) {
            String key = uri.getLastPathSegment();
            List<SearchEngineConfig> engines = ConfigManager.loadEngines(getContext());
            SearchEngineConfig engine = ConfigManager.findByKey(engines, key);

            if (engine != null) {
                List<SearchEngineConfig> single = new ArrayList<>();
                single.add(engine);
                return buildCursor(single);
            }
            return null;
        }

        return null;
    }

    private MatrixCursor buildCursor(List<SearchEngineConfig> engines) {
        String[] columns = {
                COL_KEY, COL_NAME, COL_SEARCH_URL, COL_ENABLED,
                COL_IS_BUILTIN, COL_IS_MODIFIED, COL_ORIGINAL_NAME, COL_ORIGINAL_SEARCH_URL
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
                    cfg.originalSearchUrl != null ? cfg.originalSearchUrl : ""
            });
        }

        Log.d(TAG, "[Provider] returning cursor with " + cursor.getCount() + " rows");
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Log.d(TAG, "[Provider] insert called, uri=" + uri);
        if (values == null) return null;

        int match = uriMatcher.match(uri);

        if (match == CODE_DISCOVER) {
            // 从浏览器发现引擎（包含 URL）
            String key = values.getAsString("key");
            String name = values.getAsString("name");
            String searchUrl = values.getAsString("searchUrl");

            if (key != null && !key.isEmpty()) {
                boolean updated = ConfigManager.handleDiscoveredEngine(getContext(), key, name, searchUrl);
                if (updated && getContext() != null) {
                    getContext().getContentResolver().notifyChange(CONTENT_URI, null);
                }
                return Uri.withAppendedPath(CONTENT_URI, key);
            }

        } else if (match == CODE_ENGINES) {
            // 添加自定义引擎
            String key = values.getAsString("key");
            String name = values.getAsString("name");
            String searchUrl = values.getAsString("searchUrl");

            if (key != null && !key.isEmpty()) {
                boolean success = ConfigManager.addCustomEngine(getContext(), key, name, searchUrl);
                if (success && getContext() != null) {
                    getContext().getContentResolver().notifyChange(CONTENT_URI, null);
                    return Uri.withAppendedPath(CONTENT_URI, key);
                }
            }
        }

        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Log.d(TAG, "[Provider] update called, uri=" + uri);
        if (values == null) return 0;

        if (uriMatcher.match(uri) == CODE_ENGINE_ITEM) {
            String key = uri.getLastPathSegment();
            if (key == null) return 0;

            Boolean reset = values.getAsBoolean("reset");
            if (reset != null && reset) {
                boolean success = ConfigManager.resetEngine(getContext(), key);
                if (success && getContext() != null) {
                    getContext().getContentResolver().notifyChange(uri, null);
                    return 1;
                }
                return 0;
            }

            String name = values.getAsString("name");
            String searchUrl = values.getAsString("searchUrl");
            Boolean enabled = values.getAsBoolean("enabled");

            List<SearchEngineConfig> engines = ConfigManager.loadEngines(getContext());
            SearchEngineConfig engine = ConfigManager.findByKey(engines, key);
            if (engine == null) return 0;

            if (name != null || searchUrl != null) {
                ConfigManager.updateEngineByUser(getContext(), key,
                        name != null ? name : engine.name,
                        searchUrl != null ? searchUrl : engine.searchUrl,
                        enabled != null ? enabled : engine.enabled);
            } else if (enabled != null) {
                ConfigManager.updateEngineEnabled(getContext(), key, enabled);
            }

            if (getContext() != null) {
                getContext().getContentResolver().notifyChange(uri, null);
            }
            return 1;
        }

        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Log.d(TAG, "[Provider] delete called, uri=" + uri);

        if (uriMatcher.match(uri) == CODE_ENGINE_ITEM) {
            String key = uri.getLastPathSegment();
            boolean success = ConfigManager.deleteEngine(getContext(), key);
            if (success && getContext() != null) {
                getContext().getContentResolver().notifyChange(uri, null);
                return 1;
            }
        }

        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.xposedsearch.engine";
    }
}