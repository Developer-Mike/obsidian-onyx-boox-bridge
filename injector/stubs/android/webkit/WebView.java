package android.webkit;

import android.view.ViewGroup;

/** Compile-time stub only. */
public class WebView extends ViewGroup {
    public void addJavascriptInterface(Object object, String name) {}
    public void removeJavascriptInterface(String name) {}
    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {}
    public String getUrl() { return null; }
    public void loadUrl(String url) {}
    public void reload() {}
}
