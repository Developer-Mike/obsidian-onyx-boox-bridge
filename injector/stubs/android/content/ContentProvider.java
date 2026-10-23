package android.content;

import android.database.Cursor;
import android.net.Uri;

/** Compile-time stub only. Signatures must match the real framework exactly. */
public abstract class ContentProvider {
    public boolean onCreate() { return true; }
    public Context getContext() { return null; }
    public abstract Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder);
    public abstract String getType(Uri uri);
    public abstract Uri insert(Uri uri, ContentValues values);
    public abstract int delete(Uri uri, String selection, String[] selectionArgs);
    public abstract int update(Uri uri, ContentValues values, String selection, String[] selectionArgs);
}
