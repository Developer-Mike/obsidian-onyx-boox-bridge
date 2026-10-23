package app.onyx.obsidian;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Empty ContentProvider used only as an early entry point.
 *
 * <p>{@code ContentProvider.onCreate} runs during process start, before the first activity exists.
 * Adding this provider to the manifest (the tool does that) lets the bridge register an
 * {@code ActivityLifecycleCallbacks} and attach itself to the Capacitor activity's WebView without
 * editing a single line of smali. The provider serves no data and is not exported.</p>
 */
public final class OnyxInitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        try {
            Context ctx = getContext();
            if (ctx != null) {
                Application app = (Application) ctx.getApplicationContext();
                if (app != null) {
                    app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                            Injector.install(activity);
                        }

                        public void onActivityStarted(Activity activity) {
                        }

                        public void onActivityResumed(Activity activity) {
                            Injector.install(activity);
                        }

                        public void onActivityPaused(Activity activity) {
                        }

                        public void onActivityStopped(Activity activity) {
                        }

                        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                        }

                        public void onActivityDestroyed(Activity activity) {
                        }
                    });
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("OnyxObsidian", "init provider failed: " + t);
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
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
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
