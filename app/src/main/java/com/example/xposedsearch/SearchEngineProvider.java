package com.example.xposedsearch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.List;

public class SearchEngineProvider extends ContentProvider {

    public static final String AUTHORITY   = "com.example.xposedsearch.engines";
    public static final String PATH        = "engines";
    public static final Uri   CONTENT_URI  = Uri.parse("content://" + AUTHORITY + "/" + PATH);

    private static final int MATCH_ENGINES = 1;

    private UriMatcher uriMatcher;

    @Override
    public boolean onCreate() {
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(AUTHORITY, PATH, MATCH_ENGINES);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {

        if (uriMatcher.match(uri) != MATCH_ENGINES) {
            return null;
        }

        Context context = getContext();
        if (context == null) return null;

        List<SearchEngineConfig> list = ConfigManager.loadEngines(context);
        String json = ConfigManager.toJson(list);

        String column = "json";
        MatrixCursor cursor = new MatrixCursor(new String[]{column});
        cursor.addRow(new Object[]{json});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        if (uriMatcher.match(uri) == MATCH_ENGINES) {
            return "vnd.android.cursor.item/vnd." + AUTHORITY + "." + PATH;
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}