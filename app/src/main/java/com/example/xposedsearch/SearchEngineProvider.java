package com.example.xposedsearch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SearchEngineProvider extends ContentProvider {

    private static final String TAG = "XposedSearch";
    public static final String AUTHORITY = "com.example.xposedsearch.engines";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/engines");
    public static final Uri DISCOVER_URI = Uri.parse("content://" + AUTHORITY + "/discover");
    public static final Uri DISCOVER_COMPLETE_URI = Uri.parse("content://" + AUTHORITY + "/discover_complete");

    private static final int CODE_ENGINES = 1;
    private static final int CODE_DISCOVER = 2;
    private static final int CODE_ENGINE_ITEM = 3;
    private static final int CODE_DISCOVER_COMPLETE = 4;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, "engines", CODE_ENGINES);
        uriMatcher.addURI(AUTHORITY, "discover", CODE_DISCOVER);
        uriMatcher.addURI(AUTHORITY, "engines/*", CODE_ENGINE_ITEM);
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
    // 新增列
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

        Log.d(TAG, "[Provider] returning cursor with " + cursor.getCount() + " rows");
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
                // 追踪发现的引擎
                currentDiscoveredKeys.add(key);

                boolean updated = ConfigManager.handleDiscoveredEngine(getContext(), key, name, searchUrl);
                if (updated && getContext() != null) {
                    getContext().getContentResolver().notifyChange(CONTENT_URI, null);
                }
                return Uri.withAppendedPath(CONTENT_URI, key);
            }

        } else if (match == CODE_DISCOVER_COMPLETE) {
            // 发现完成，标记未发现的引擎为已消失
            if (!currentDiscoveredKeys.isEmpty()) {
                ConfigManager.markMissingEnginesAsRemoved(getContext(), currentDiscoveredKeys);
                currentDiscoveredKeys.clear();
                if (getContext() != null) {
                    getContext().getContentResolver().notifyChange(CONTENT_URI, null);
                }
            }
            return DISCOVER_COMPLETE_URI;

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

            // 重置操作
            Boolean reset = values.getAsBoolean("reset");
            if (reset != null && reset) {
                boolean success = ConfigManager.resetEngine(getContext(), key);
                if (success && getContext() != null) {
                    getContext().getContentResolver().notifyChange(uri, null);
                    return 1;
                }
                return 0;
            }

            // 应用更新操作
            Boolean applyUpdate = values.getAsBoolean("applyUpdate");
            if (applyUpdate != null && applyUpdate) {
                ConfigManager.applyPendingUpdate(getContext(), key);
                if (getContext() != null) {
                    getContext().getContentResolver().notifyChange(uri, null);
                }
                return 1;
            }

            // 忽略更新操作
            Boolean ignoreUpdate = values.getAsBoolean("ignoreUpdate");
            if (ignoreUpdate != null && ignoreUpdate) {
                ConfigManager.ignorePendingUpdate(getContext(), key);
                if (getContext() != null) {
                    getContext().getContentResolver().notifyChange(uri, null);
                }
                return 1;
            }

            // 转换为自定义引擎
            Boolean convertToCustom = values.getAsBoolean("convertToCustom");
            if (convertToCustom != null && convertToCustom) {
                ConfigManager.convertToCustomEngine(getContext(), key);
                if (getContext() != null) {
                    getContext().getContentResolver().notifyChange(uri, null);
                }
                return 1;
            }

            // 转换为内置引擎（处理冲突）
            Boolean convertToBuiltin = values.getAsBoolean("convertToBuiltin");
            if (convertToBuiltin != null && convertToBuiltin) {
                ConfigManager.convertCustomToBuiltin(getContext(), key);
                if (getContext() != null) {
                    getContext().getContentResolver().notifyChange(uri, null);
                }
                return 1;
            }

            // 创建副本（处理冲突）
            Boolean createCopy = values.getAsBoolean("createCopy");
            if (createCopy != null && createCopy) {
                String newKey = ConfigManager.createCustomEngineCopy(getContext(), key);
                if (newKey != null && getContext() != null) {
                    getContext().getContentResolver().notifyChange(CONTENT_URI, null);
                }
                return newKey != null ? 1 : 0;
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