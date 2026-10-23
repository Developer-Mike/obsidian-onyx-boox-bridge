package app.onyx.obsidian;

import android.webkit.JavascriptInterface;

import java.util.ArrayList;

/**
 * The object exposed to the WebView as {@code window.onyx}. Every method here is callable from
 * JavaScript. Arrays are passed as compact comma/semicolon strings so the bridge needs nothing but
 * primitives and {@code String} (the most portable shape for {@code addJavascriptInterface}).
 *
 * <p>The nicer, documented surface (nested namespaces, high level session helpers) is built on top
 * of this class by the JavaScript shim shipped in {@code assets/onyx/shim.js}.</p>
 */
public final class OnyxJs {

    private static final String VERSION = "1.0.0";

    public OnyxJs() {
    }

    private static OnyxEpd epd() {
        return OnyxEpd.get();
    }

    // ---- meta ----

    @JavascriptInterface
    public String version() {
        return VERSION;
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return epd().isAvailable();
    }

    @JavascriptInterface
    public boolean connect() {
        return epd().connect();
    }

    @JavascriptInterface
    public double maxTouchPressure() {
        return epd().maxTouchPressure();
    }

    @JavascriptInterface
    public int ownerPid() {
        return epd().ownerPid();
    }

    @JavascriptInterface
    public boolean release() {
        return epd().release();
    }

    // ---- pen session ----

    @JavascriptInterface
    public int penState() {
        Integer v = epd().penState();
        return v == null ? -1 : v.intValue();
    }

    @JavascriptInterface
    public boolean setPenState(int state) {
        return epd().setPenState(state);
    }

    @JavascriptInterface
    public boolean penUpNotice() {
        return epd().penUpNotice();
    }

    @JavascriptInterface
    public boolean moveTo(double x, double y, double width) {
        return epd().moveTo((float) x, (float) y, (float) width);
    }

    @JavascriptInterface
    public boolean lineTo(double x, double y, int updateMode) {
        return epd().lineTo((float) x, (float) y, updateMode);
    }

    @JavascriptInterface
    public boolean quadTo(double x, double y, int updateMode) {
        return epd().quadTo((float) x, (float) y, updateMode);
    }

    @JavascriptInterface
    public boolean penUp() {
        return epd().penUp();
    }

    // ---- stroke ----

    @JavascriptInterface
    public boolean setStroke(int style, double width, int argb) {
        return epd().setStroke(style, (float) width, argb);
    }

    @JavascriptInterface
    public boolean setStrokeStyle(int style) {
        return epd().setStrokeStyle(style);
    }

    @JavascriptInterface
    public boolean setStrokeWidth(double width) {
        return epd().setStrokeWidth((float) width);
    }

    @JavascriptInterface
    public boolean setStrokeColor(int argb) {
        return epd().setStrokeColor(argb);
    }

    @JavascriptInterface
    public String getStrokeParameters(int style) {
        return join(epd().strokeParameters(style));
    }

    @JavascriptInterface
    public boolean setStrokeParameters(int style, String paramsCsv) {
        float[] p = parseFloats(paramsCsv);
        return p != null && epd().setStrokeParameters(style, p);
    }

    // ---- regions ----

    @JavascriptInterface
    public boolean setRegionMode(int mode) {
        return epd().setRegionMode(mode);
    }

    @JavascriptInterface
    public boolean setRegionLimit(String rectCsv, boolean screen) {
        int[] r = parseInts(rectCsv);
        return r != null && epd().setRegionLimit(r, screen);
    }

    @JavascriptInterface
    public boolean setRegionExcludes(String rectsCsv, boolean screen) {
        int[] r = parseRects(rectsCsv);
        return r != null && epd().setRegionExclude(r, screen);
    }

    @JavascriptInterface
    public boolean setRegionPenConfig(String rectCsv, String buttonsCsv) {
        int[] rect = parseInts(rectCsv);
        float[][] buttons = parseButtons(buttonsCsv);
        return rect != null && buttons != null && epd().setRegionPenConfig(rect, buttons);
    }

    @JavascriptInterface
    public boolean clearRegionPenConfig(String rectCsv) {
        if (rectCsv == null || rectCsv.trim().isEmpty()) {
            return epd().clearRegionPenConfig(null);
        }
        int[] rect = parseInts(rectCsv);
        return rect != null && epd().clearRegionPenConfig(rect);
    }

    // ---- raw drawing / hold ----

    @JavascriptInterface
    public boolean setBrushRawDrawing(boolean on) {
        return epd().setBrushRawDrawing(on);
    }

    @JavascriptInterface
    public boolean setEraserRawDrawing(boolean on, int style) {
        return epd().setEraserRawDrawing(on, style);
    }

    @JavascriptInterface
    public boolean setPenSideButton(boolean on) {
        return epd().setPenSideButton(on);
    }

    @JavascriptInterface
    public boolean enablePost(boolean on) {
        return epd().enablePost(on);
    }

    @JavascriptInterface
    public boolean setAutoSync(boolean on) {
        return epd().setAutoSync(on);
    }

    // ---- refresh ----

    @JavascriptInterface
    public boolean repaintEverything(int mode) {
        if (mode < 0) {
            return epd().repaintEverythingDefault();
        }
        return epd().repaintEverythingMode(mode);
    }

    @JavascriptInterface
    public boolean refresh(int left, int top, int width, int height, int mode) {
        return epd().refresh(left, top, width, height, mode);
    }

    @JavascriptInterface
    public boolean applyGcOnce() {
        return epd().applyGcOnce();
    }

    @JavascriptInterface
    public boolean waitForUpdate() {
        return epd().waitForUpdateFinished();
    }

    @JavascriptInterface
    public boolean handwritingRepaint(String rectCsv) {
        int[] r = parseInts(rectCsv);
        return r != null && epd().handwritingRepaint(r);
    }

    @JavascriptInterface
    public boolean applyTransientUpdate(int mode) {
        return epd().applyTransientUpdate(mode);
    }

    @JavascriptInterface
    public boolean clearTransientUpdate(boolean reset) {
        return epd().clearTransientUpdate(reset);
    }

    @JavascriptInterface
    public boolean enableRegal(boolean on) {
        return epd().enableRegal(on);
    }

    @JavascriptInterface
    public int fastModeIndex() {
        Integer v = epd().fastModeIndex();
        return v == null ? -1 : v.intValue();
    }

    // ---- geometry ----

    @JavascriptInterface
    public String panelSize() {
        return join(epd().panelSize());
    }

    @JavascriptInterface
    public String digitizerSize() {
        return join(epd().digitizerSize());
    }

    @JavascriptInterface
    public String mapToPanel(double x, double y) {
        return join(epd().mapToPanel((float) x, (float) y));
    }

    @JavascriptInterface
    public String mapDigitizerToScreen(double x, double y) {
        return join(epd().mapDigitizerToScreen((float) x, (float) y));
    }

    @JavascriptInterface
    public String panelToScreenMatrix() {
        return join(epd().panelToScreenMatrix());
    }

    /** The WebView's location on screen, "x,y". Add it to page coordinates for screen coordinates. */
    @JavascriptInterface
    public String viewOffset() {
        int[] loc = Injector.viewOffset();
        return loc[0] + "," + loc[1];
    }

    /** Current screen size in physical pixels, "w,h". Used to size the pen region. */
    @JavascriptInterface
    public String screenSize() {
        try {
            android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
            if (dm != null) {
                return dm.widthPixels + "," + dm.heightPixels;
            }
        } catch (Throwable t) {
            // fall through
        }
        return "";
    }

    @JavascriptInterface
    public double strokeWidthAt(int kind, double baseWidth, double x, double y, double pressure, double size, double timeMs) {
        Float f = epd().strokeWidthAt(kind, (float) baseWidth, (float) x, (float) y, (float) pressure, (float) size, (float) timeMs);
        return f == null ? -1d : f.floatValue();
    }

    // ---- parsing helpers ----

    private static int[] parseInts(String csv) {
        if (csv == null) {
            return new int[0];
        }
        String s = csv.trim();
        if (s.isEmpty()) {
            return new int[0];
        }
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = (int) Double.parseDouble(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static float[] parseFloats(String csv) {
        if (csv == null) {
            return new float[0];
        }
        String s = csv.trim();
        if (s.isEmpty()) {
            return new float[0];
        }
        String[] parts = s.split(",");
        float[] out = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** "l,t,r,b" or "l,t,r,b;l,t,r,b" flattened into one array. */
    private static int[] parseRects(String csv) {
        if (csv == null) {
            return new int[0];
        }
        String s = csv.trim();
        if (s.isEmpty()) {
            return new int[0];
        }
        String[] rects = s.split(";");
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < rects.length; i++) {
            int[] r = parseInts(rects[i]);
            if (r == null) {
                return null;
            }
            for (int k = 0; k < r.length; k++) {
                list.add(Integer.valueOf(r[k]));
            }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i).intValue();
        }
        return out;
    }

    /**
     * Rows separated by ';'. Each row: button,style,width,argb,eraserPreview,eraserPainter,param0,...
     * Layout matches {@link OnyxEpd#setRegionPenConfig(int[], float[][])}.
     */
    private static float[][] parseButtons(String csv) {
        if (csv == null) {
            return new float[0][];
        }
        String s = csv.trim();
        if (s.isEmpty()) {
            return new float[0][];
        }
        String[] rows = s.split(";");
        float[][] out = new float[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            float[] v = parseFloats(rows[i]);
            if (v == null || v.length < 6) {
                return null;
            }
            int paramCount = v.length - 6;
            float[] enc = new float[5 + paramCount + 2];
            enc[0] = v[0];                 // button
            enc[1] = v[1];                 // style
            enc[2] = v[2];                 // width
            enc[3] = v[3];                 // argb
            enc[4] = paramCount;           // param count
            for (int k = 0; k < paramCount; k++) {
                enc[5 + k] = v[6 + k];
            }
            enc[5 + paramCount] = v[4];    // eraserPreview
            enc[6 + paramCount] = v[5];    // eraserPainter
            out[i] = enc;
        }
        return out;
    }

    private static String join(float[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
