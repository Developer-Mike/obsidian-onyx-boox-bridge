package android.app;

import android.content.Context;
import android.os.Bundle;

/** Compile-time stub only. */
public class Application extends Context {
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callbacks) {}

    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity activity, Bundle savedInstanceState);
        void onActivityStarted(Activity activity);
        void onActivityResumed(Activity activity);
        void onActivityPaused(Activity activity);
        void onActivityStopped(Activity activity);
        void onActivitySaveInstanceState(Activity activity, Bundle outState);
        void onActivityDestroyed(Activity activity);
    }
}
