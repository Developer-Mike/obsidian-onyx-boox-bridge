package app.onyx.obsidian;

import android.app.Activity;
import android.content.res.AssetManager;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Attaches the BOOX ink bridge to a Capacitor {@code BridgeActivity}'s WebView.
 *
 * <p>Called for every activity by {@link OnyxInitProvider}. It finds the WebView in two ways: the
 * Capacitor way ({@code getBridge().getWebView()} through reflection, so no Capacitor class is
 * linked) and, as a fallback, by walking the view hierarchy. The interface object is exposed to
 * pages as {@code window.onyx}; a small JavaScript shim from {@code assets/onyx/shim.js} is then
 * evaluated (repeatedly and idempotently, because page loads are asynchronous) to build the friendly
 * API on top of it.</p>
 */
public final class Injector {

    private static final String SHIM_ASSET = "onyx/shim.js";
    private static final String TAG = "OnyxObsidian";

    private static final String RAW_NAME = "__onyxBridge";

    private static final Map<WebView, Boolean> HOOKED = new WeakHashMap<WebView, Boolean>();
    private static volatile String sShim;
    private static volatile boolean sConnected;
    /** Strong reference so the injected Java object can never be collected. */
    private static volatile OnyxJs sJs;
    /** Last WebView we hooked, so controls outside the page can run JS in it. */
    private static volatile WebView sWebView;

    /** The hooked WebView's location on screen (x, y), for mapping page coords to screen coords. */
    public static int[] viewOffset() {
        int[] loc = new int[2];
        WebView wv = sWebView;
        if (wv != null) {
            try {
                wv.getLocationOnScreen(loc);
            } catch (Throwable t) {
                // leave 0,0
            }
        }
        return loc;
    }

    private Injector() {
    }

    public static void install(final Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            connectOnce();
            WebView wv = findWebView(activity);
            if (wv == null) {
                final View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (root != null) {
                    retry(root, activity, 0);
                }
                return;
            }
            hook(activity, wv);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "install failed: " + t);
        }
    }

    private static void connectOnce() {
        if (sConnected) {
            return;
        }
        sConnected = true;
        final OnyxEpd epd = OnyxEpd.get();
        if (epd.isAvailable()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    boolean ok = epd.connect();
                    android.util.Log.i(TAG, "connect: " + ok + ", maxPressure=" + epd.maxTouchPressure());
                } catch (Throwable e) {
                    android.util.Log.w(TAG, "connect failed: " + e);
                }
            }
        }, "onyx-connect");
        t.setDaemon(true);
        t.start();
    }

    private static void retry(final View root, final Activity activity, final int attempt) {
        if (attempt > 60) {
            return;
        }
        root.postDelayed(new Runnable() {
            public void run() {
                WebView wv = findWebView(activity);
                if (wv != null) {
                    hook(activity, wv);
                } else {
                    retry(root, activity, attempt + 1);
                }
            }
        }, 50);
    }

    private static synchronized void hook(final Activity activity, final WebView wv) {
        try {
            OnyxJs js = sJs;
            if (js == null) {
                js = new OnyxJs();
                sJs = js;
            }
            wv.addJavascriptInterface(js, RAW_NAME);
            wv.addJavascriptInterface(js, "onyx"); // legacy name; the shim replaces it with the wrapper
            sWebView = wv;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "addJavascriptInterface failed: " + t);
        }
        if (HOOKED.containsKey(wv)) {
            return;
        }
        HOOKED.put(wv, Boolean.TRUE);
        android.util.Log.i(TAG, "bridge installed on webview");

        final String shim = loadShim(activity);
        final WeakReference<WebView> ref = new WeakReference<WebView>(wv);
        // Page loads are asynchronous, so evaluate the shim a few times right away...
        for (int i = 0; i < 6; i++) {
            scheduleShim(ref, shim, i * 300L);
        }
        // ...and then keep a cheap watchdog running. The shim is idempotent, so this also restores
        // the friendly API after Obsidian reloads the page. It stops when the WebView is collected.
        scheduleWatchdog(ref, shim, 0);
    }

    private static void scheduleShim(final WeakReference<WebView> ref, final String shim, long delayMs) {
        WebView wv = ref.get();
        if (wv == null) {
            return;
        }
        wv.postDelayed(new Runnable() {
            public void run() {
                WebView w = ref.get();
                if (w == null) {
                    return;
                }
                try {
                    w.evaluateJavascript(shim, null);
                } catch (Throwable t) {
                    // ignore: the page or the WebView may not be ready yet
                }
            }
        }, delayMs);
    }

    private static void scheduleWatchdog(final WeakReference<WebView> ref, final String shim, final int n) {
        if (n > 400) {
            return; // ~15 minutes, plenty for a page reload; install() re-arms on resume anyway
        }
        WebView wv = ref.get();
        if (wv == null) {
            return;
        }
        wv.postDelayed(new Runnable() {
            public void run() {
                WebView w = ref.get();
                if (w == null) {
                    return;
                }
                try {
                    w.evaluateJavascript(shim, null);
                } catch (Throwable t) {
                    // ignore
                }
                scheduleWatchdog(ref, shim, n + 1);
            }
        }, 2200L);
    }

    private static String loadShim(Activity activity) {
        String s = sShim;
        if (s != null) {
            return s;
        }
        try {
            AssetManager am = activity.getAssets();
            InputStream in = am.open(SHIM_ASSET);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            s = new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "shim load failed: " + t);
            s = "(function(){})();";
        }
        sShim = s;
        return s;
    }

    static WebView findWebView(Activity activity) {
        WebView wv = bridgeWebView(activity);
        if (wv != null) {
            return wv;
        }
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        return scan(root);
    }

    /** {@code activity.getBridge().getWebView()} via reflection, without linking Capacitor. */
    private static WebView bridgeWebView(Activity activity) {
        try {
            Method getBridge = noArgMethod(activity.getClass(), "getBridge");
            if (getBridge == null) {
                return null;
            }
            Object bridge = getBridge.invoke(activity);
            if (bridge == null) {
                return null;
            }
            Method getWebView = noArgMethod(bridge.getClass(), "getWebView");
            if (getWebView == null) {
                return null;
            }
            Object o = getWebView.invoke(bridge);
            return (o instanceof WebView) ? (WebView) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method noArgMethod(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null) {
            try {
                return k.getMethod(name);
            } catch (Throwable t) {
                k = k.getSuperclass();
            }
        }
        return null;
    }

    private static WebView scan(View v) {
        if (v == null) {
            return null;
        }
        if (v instanceof WebView) {
            return (WebView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int count = g.getChildCount();
            for (int i = 0; i < count; i++) {
                WebView r = scan(g.getChildAt(i));
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
