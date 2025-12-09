package com.example.xposedsearch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.List;

/**
 * ContentProvider - 用于跨进程向 Xposed hook 提供配置数据
 */
public class SearchEngineProvider extends ContentProvider {

    private static final String TAG = "XposedSearch";
    public static final String AUTHORITY = "com.example.xposedsearch.engines";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/engines");
    public static final Uri DISCOVER_URI = Uri.parse("content://" + AUTHORITY + "/discover");

    private static final int CODE_ENGINES = 1;
    private static final int CODE_DISCOVER = 2;

    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        uriMatcher.addURI(AUTHORITY, "engines", CODE_ENGINES);
        uriMatcher.addURI(AUTHORITY, "discover", CODE_DISCOVER);
    }

    // 列名
    public static final String COL_KEY = "key";
    public static final String COL_NAME = "name";
    public static final String COL_SEARCH_URL = "searchUrl";
    public static final String COL_ENABLED = "enabled";

    @Override
    public boolean onCreate() {
        Log.d(TAG, "[Provider] onCreate");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {

        Log.d(TAG, "[Provider] query called, uri=" + uri);

        if (uriMatcher.match(uri) != CODE_ENGINES) {
            Log.w(TAG, "[Provider] unknown URI: " + uri);
            return null;
        }

        // 从 SharedPreferences 加载配置
        List<SearchEngineConfig> engines = ConfigManager.loadEngines(getContext());
        Log.d(TAG, "[Provider] loaded engines count=" + engines.size());

        // 构建 Cursor 返回
        String[] columns = {COL_KEY, COL_NAME, COL_SEARCH_URL, COL_ENABLED};
        MatrixCursor cursor = new MatrixCursor(columns);

        for (SearchEngineConfig cfg : engines) {
            cursor.addRow(new Object[]{
                    cfg.key,
                    cfg.name,
                    cfg.searchUrl,
                    cfg.enabled ? 1 : 0
            });
        }

        Log.d(TAG, "[Provider] returning cursor with " + cursor.getCount() + " rows");
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.xposedsearch.engine";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Log.d(TAG, "[Provider] insert called, uri=" + uri);

        if (uriMatcher.match(uri) == CODE_DISCOVER && values != null) {
            String key = values.getAsString("key");
            String name = values.getAsString("name");

            if (key != null && !key.isEmpty()) {
                // 从 SharedPreferences 加载现有配置
                List<SearchEngineConfig> engines = ConfigManager.loadEngines(getContext());

                // 检查是否已存在
                boolean exists = false;
                for (SearchEngineConfig cfg : engines) {
                    if (key.equals(cfg.key)) {
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    // 添加新发现的引擎（默认禁用，让用户自己启用）
                    SearchEngineConfig newEngine = new SearchEngineConfig(
                            key,
                            name != null ? name : key,
                            "", // 内置引擎不需要 URL
                            false // 默认禁用
                    );
                    engines.add(newEngine);
                    ConfigManager.saveEngines(getContext(), engines);
                    Log.d(TAG, "[Provider] discovered new engine: " + key + " (" + name + ")");

                    // 通知变化
                    if (getContext() != null) {
                        getContext().getContentResolver().notifyChange(CONTENT_URI, null);
                    }
                } else {
                    Log.d(TAG, "[Provider] engine already exists: " + key);
                }
            }
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // 不支持删除
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // 不支持更新
        return 0;
    }
}