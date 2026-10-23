package app.onyx.obsidian;

import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for the BOOX e-ink display, spoken to through SurfaceFlinger's private transactions.
 *
 * <p>This is a Java port of the verified clean-room client from the BooxUltimatum project
 * (see {@code docs/09-ink.md}). It deliberately uses no Onyx SDK classes: the transactions are made
 * through the standard {@code android.os.IBinder}/{@code android.os.Parcel} public API, and the
 * SurfaceFlinger binder is looked up with reflection on {@code android.os.ServiceManager}. That
 * means nothing has to be bundled and the same dex works on any BOOX device whose firmware speaks
 * the same codes.</p>
 *
 * <p>Every call writes the interface token {@code android.ui.ISurfaceComposer} first and is
 * synchronous. {@link #connect()} only accepts a route that answers the pen state and a plausible
 * pressure range, so a firmware whose codes differ is detected instead of mis-driven.</p>
 *
 * <p>Codes and parcel layouts are those the FW 4.3 {@code android.onyx.ViewUpdateHelper} sends.
 * See the docs for which calls are verified on hardware.</p>
 */
public final class OnyxEpd {

    private static final String TOKEN = "android.ui.ISurfaceComposer";

    // ---- Transaction codes (see docs/09-ink.md) ----
    public static final int SET_PEN_STATE = 16711693;
    public static final int GET_PEN_STATE = 1048643;
    public static final int IS_VALID_PEN_STATE = 1048641;
    public static final int SET_REGION_LIMIT = 16711694;
    public static final int SET_REGION_EXCLUDE = 16711714;
    public static final int SET_REGION_MODE = 1048620;
    public static final int SET_STROKE_COLOR = 16711686;
    public static final int SET_STROKE_WIDTH = 16711687;
    public static final int SET_STROKE_STYLE = 16711688;
    public static final int GET_STROKE_PARAMETERS = 1049088;
    public static final int SET_STROKE_PARAMETERS = 1049089;
    public static final int SET_ERASER_RAW_DRAWING = 1048833;
    public static final int SET_BRUSH_RAW_DRAWING = 1048834;
    public static final int ENABLE_POST = 16711692;
    public static final int SET_AUTO_SYNC = 1048722;
    public static final int HANDWRITING_REPAINT = 1048647;
    public static final int REPAINT_EVERYTHING = 16711700;
    public static final int REPAINT_EVERYTHING_MODE = 16711715;
    public static final int REFRESH_SCREEN = 16711681;
    public static final int APPLY_GC_ONCE = 16711718;
    public static final int WAIT_FOR_UPDATE = 16711703;
    public static final int APPLY_TRANSIENT_UPDATE = 16711782;
    public static final int CLEAR_TRANSIENT_UPDATE = 16711783;
    public static final int GET_FAST_MODE_INDEX = 1048656;
    public static final int DRAW_HANDWRITING_BITMAP = 1049092;
    public static final int MAP_TO_VIEW = 16711723;
    public static final int MAP_TO_EPD = 16711724;
    public static final int GET_EPD_WIDTH = 16711727;
    public static final int GET_EPD_HEIGHT = 16711712;
    public static final int GET_TOUCH_WIDTH = 16711706;
    public static final int GET_TOUCH_HEIGHT = 16711707;
    public static final int GET_EPD_TO_VIEW_MATRIX = 1049344;
    public static final int GET_MAX_TOUCH_PRESSURE = 1048618;
    public static final int ENABLE_REGAL = 16711722;
    public static final int SET_REGION_PEN_CONFIG = 1049090;
    public static final int CLEAR_REGION_PEN_CONFIG = 1049091;
    public static final int SET_PEN_SIDE_BUTTON = 1048832;
    public static final int START_STROKE = 16711697;
    public static final int ADD_STROKE_POINT = 16711698;
    public static final int FINISH_STROKE = 16711699;
    public static final int APP_DIE = 16711717;
    public static final int PEN_UP_NOTICE = 16711784;

    /** The SDK's default painter value for an eraser whose raw preview is off. */
    public static final int RAW_DEFAULT_ERASER_PAINTER = 5;

    public static final int PEN_STATE_STOP = 0;
    public static final int PEN_STATE_START = 1;
    public static final int PEN_STATE_DRAW = 2;
    public static final int PEN_STATE_PAUSE = 3;
    public static final int PEN_STATE_PAUSED_READBACK = 4;

    public static final int REGION_MODE_MULTI = 0;
    public static final int REGION_MODE_SINGLE = 1;

    private static final int MAX_BITMAP_BYTES = 2 * 1024 * 1024;

    private static volatile OnyxEpd sInstance;

    private volatile IBinder mBinder;
    private volatile boolean mAvailable;
    private volatile float mMaxTouchPressure;
    private volatile int mOwnerPid = Process.myPid();
    private final Set<Integer> mFailed = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    private OnyxEpd() {
    }

    public static OnyxEpd get() {
        OnyxEpd local = sInstance;
        if (local == null) {
            synchronized (OnyxEpd.class) {
                local = sInstance;
                if (local == null) {
                    local = new OnyxEpd();
                    sInstance = local;
                }
            }
        }
        return local;
    }

    /** Finds SurfaceFlinger and validates the route. Safe to call repeatedly and from any thread. */
    public synchronized boolean connect() {
        if (isAvailable()) {
            return true;
        }
        IBinder b = findSurfaceFlinger();
        if (b != null && probe(b)) {
            mBinder = b;
            mAvailable = true;
            mOwnerPid = Process.myPid();
            return true;
        }
        mBinder = null;
        mAvailable = false;
        return false;
    }

    public boolean isAvailable() {
        IBinder b = mBinder;
        return mAvailable && b != null && b.isBinderAlive();
    }

    public float maxTouchPressure() {
        return mMaxTouchPressure;
    }

    public int ownerPid() {
        return mOwnerPid;
    }

    private static IBinder findSurfaceFlinger() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod("getService", String.class);
            Object o = m.invoke(null, "SurfaceFlinger");
            return (IBinder) o;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean probe(IBinder b) {
        Integer state = readInt(b, GET_PEN_STATE);
        if (state == null) {
            return false;
        }
        Float p = readFloat(b, GET_MAX_TOUCH_PRESSURE);
        if (p == null) {
            return false;
        }
        float pv = p.floatValue();
        if (!(pv >= 1f && pv <= 100000f)) {
            return false;
        }
        mMaxTouchPressure = pv;
        return true;
    }

    // ---- Pen session ----

    public Integer penState() {
        IBinder b = mBinder;
        return b == null ? null : readInt(b, GET_PEN_STATE);
    }

    public boolean setPenState(int state) {
        final int pid = mOwnerPid;
        return call(SET_PEN_STATE, new Writer() {
            public void write(Parcel p) {
                p.writeInt(state);
                p.writeInt(pid);
            }
        });
    }

    public boolean penUpNotice() {
        return call(PEN_UP_NOTICE, null);
    }

    // ---- App-fed stroke (the older fast-preview path; payloads from FW 3.5's ViewUpdateHelper) ----

    public static final int MOVE_TO = 16711690;   // 0xff000a
    public static final int LINE_TO = 16711691;   // 0xff000b
    public static final int QUAD_TO = 16711696;   // 0xff0010

    /** Starts an app-fed stroke: int flag(0), float x, float y, float width. */
    public boolean moveTo(float x, float y, float width) {
        final float fx = x;
        final float fy = y;
        final float fw = width;
        return call(MOVE_TO, new Writer() {
            public void write(Parcel p) {
                p.writeInt(1); // screen coordinates (view != null in the framework's caller)
                p.writeFloat(fx);
                p.writeFloat(fy);
                p.writeFloat(fw);
            }
        });
    }

    /** Continues an app-fed stroke: int flag(0), float x, float y, int updateMode. */
    public boolean lineTo(float x, float y, int updateMode) {
        final float fx = x;
        final float fy = y;
        final int m = updateMode;
        return call(LINE_TO, new Writer() {
            public void write(Parcel p) {
                p.writeInt(1);
                p.writeFloat(fx);
                p.writeFloat(fy);
                p.writeInt(m);
            }
        });
    }

    /** Quadratic app-fed stroke: int flag(0), float x, float y, int updateMode. */
    public boolean quadTo(float x, float y, int updateMode) {
        final float fx = x;
        final float fy = y;
        final int m = updateMode;
        return call(QUAD_TO, new Writer() {
            public void write(Parcel p) {
                p.writeInt(1);
                p.writeFloat(fx);
                p.writeFloat(fy);
                p.writeInt(m);
            }
        });
    }

    /** Ends an app-fed stroke (no payload). */
    public boolean penUp() {
        return call(PEN_UP_NOTICE, null);
    }

    public boolean appDie(int pid) {
        final int v = pid;
        return call(APP_DIE, new Writer() {
            public void write(Parcel p) {
                p.writeInt(v);
            }
        });
    }

    // ---- Regions ----

    public boolean setRegionLimit(int[] rects, boolean screen) {
        final int[] r = rects;
        final int s = screen ? 1 : 0;
        return call(SET_REGION_LIMIT, new Writer() {
            public void write(Parcel p) {
                p.writeInt(s);
                p.writeIntArray(r);
            }
        });
    }

    public boolean setRegionExclude(int[] rects, boolean screen) {
        final int[] r = rects;
        final int s = screen ? 1 : 0;
        return call(SET_REGION_EXCLUDE, new Writer() {
            public void write(Parcel p) {
                p.writeInt(s);
                p.writeIntArray(r);
            }
        });
    }

    public boolean setRegionMode(int mode) {
        final int m = mode;
        return call(SET_REGION_MODE, new Writer() {
            public void write(Parcel p) {
                p.writeInt(m);
            }
        });
    }

    // ---- Stroke ----

    public boolean setStrokeStyle(int style) {
        final int v = style;
        return call(SET_STROKE_STYLE, new Writer() {
            public void write(Parcel p) {
                p.writeInt(v);
            }
        });
    }

    public boolean setStrokeWidth(float widthPx) {
        final float v = widthPx;
        return call(SET_STROKE_WIDTH, new Writer() {
            public void write(Parcel p) {
                p.writeFloat(v);
            }
        });
    }

    public boolean setStrokeColor(int argb) {
        final int v = argb;
        return call(SET_STROKE_COLOR, new Writer() {
            public void write(Parcel p) {
                p.writeInt(v);
            }
        });
    }

    /** Style first: choosing a style loads its default width, so send the width after the style. */
    public boolean setStroke(int style, float widthPx, int argb) {
        boolean a = setStrokeStyle(style);
        boolean b = setStrokeWidth(widthPx);
        boolean c = setStrokeColor(argb);
        return a && b && c;
    }

    public float[] strokeParameters(int style) {
        IBinder b = mBinder;
        if (b == null) {
            return null;
        }
        final int s = style;
        Object o = transact(b, GET_STROKE_PARAMETERS, new Writer() {
            public void write(Parcel p) {
                p.writeInt(s);
            }
        }, new Reader() {
            public Object read(Parcel r) {
                int n = r.readInt();
                if (n < 0 || n > 64) {
                    return null;
                }
                float[] out = new float[n];
                for (int i = 0; i < n; i++) {
                    out[i] = r.readFloat();
                }
                return out;
            }
        });
        return (float[]) o;
    }

    public boolean setStrokeParameters(int style, float[] params) {
        final int s = style;
        final float[] v = params;
        return call(SET_STROKE_PARAMETERS, new Writer() {
            public void write(Parcel p) {
                p.writeInt(s);
                p.writeInt(v.length);
                for (int i = 0; i < v.length; i++) {
                    p.writeFloat(v[i]);
                }
            }
        });
    }

    // ---- Raw drawing ----

    public boolean setEraserRawDrawing(boolean on, int style) {
        final int o = on ? 1 : 0;
        final int s = style;
        return call(SET_ERASER_RAW_DRAWING, new Writer() {
            public void write(Parcel p) {
                p.writeInt(o);
                p.writeInt(s);
            }
        });
    }

    public boolean setBrushRawDrawing(boolean on) {
        final int o = on ? 1 : 0;
        return call(SET_BRUSH_RAW_DRAWING, new Writer() {
            public void write(Parcel p) {
                p.writeInt(o);
            }
        });
    }

    public boolean setPenSideButton(boolean on) {
        final int o = on ? 1 : 0;
        return call(SET_PEN_SIDE_BUTTON, new Writer() {
            public void write(Parcel p) {
                p.writeInt(o);
            }
        });
    }

    /**
     * Whether the app's frames reach the panel. The display holds them from the first pen touch of a
     * session until this lets them through (which also replaces the preview with the app's pixels).
     */
    public boolean enablePost(boolean on) {
        final int o = on ? 1 : 0;
        final int pid = mOwnerPid;
        return call(ENABLE_POST, new Writer() {
            public void write(Parcel p) {
                p.writeInt(-1);
                p.writeInt(o);
                p.writeInt(pid);
            }
        });
    }

    public boolean setAutoSync(boolean on) {
        final int o = on ? 1 : 0;
        return call(SET_AUTO_SYNC, new Writer() {
            public void write(Parcel p) {
                p.writeInt(o);
            }
        });
    }

    public boolean handwritingRepaint(int[] rect) {
        final int[] r = rect;
        return call(HANDWRITING_REPAINT, new Writer() {
            public void write(Parcel p) {
                p.writeInt(0);
                p.writeIntArray(r);
            }
        });
    }

    // ---- Refreshes ----

    public boolean repaintEverythingDefault() {
        return call(REPAINT_EVERYTHING, null);
    }

    public boolean repaintEverythingMode(int mode) {
        final int m = mode;
        return call(REPAINT_EVERYTHING_MODE, new Writer() {
            public void write(Parcel p) {
                p.writeInt(m);
            }
        });
    }

    public boolean refresh(int left, int top, int width, int height, int mode) {
        final int l = left;
        final int t = top;
        final int w = width;
        final int h = height;
        final int m = mode;
        return call(REFRESH_SCREEN, new Writer() {
            public void write(Parcel p) {
                p.writeInt(l);
                p.writeInt(t);
                p.writeInt(w);
                p.writeInt(h);
                p.writeInt(m);
            }
        });
    }

    public boolean applyGcOnce() {
        return call(APPLY_GC_ONCE, null);
    }

    public boolean waitForUpdateFinished() {
        return call(WAIT_FOR_UPDATE, null);
    }

    public boolean applyTransientUpdate(int mode) {
        final int m = mode;
        return call(APPLY_TRANSIENT_UPDATE, new Writer() {
            public void write(Parcel p) {
                p.writeInt(m);
            }
        });
    }

    public boolean clearTransientUpdate(boolean reset) {
        final int r = reset ? 1 : 0;
        return call(CLEAR_TRANSIENT_UPDATE, new Writer() {
            public void write(Parcel p) {
                p.writeInt(r);
            }
        });
    }

    public boolean enableRegal(boolean on) {
        final int o = on ? 1 : 0;
        return call(ENABLE_REGAL, new Writer() {
            public void write(Parcel p) {
                p.writeInt(o);
            }
        });
    }

    public Integer fastModeIndex() {
        IBinder b = mBinder;
        return b == null ? null : readInt(b, GET_FAST_MODE_INDEX);
    }

    // ---- Handwriting layer (API 33+) ----

    public boolean drawHandwritingBitmap(int x, int y, int width, int height, byte[] pixels) {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        if (pixels == null || pixels.length != width * height * 4 || pixels.length > MAX_BITMAP_BYTES) {
            return false;
        }
        final int fx = x;
        final int fy = y;
        final int fw = width;
        final int fh = height;
        final byte[] fp = pixels;
        return call(DRAW_HANDWRITING_BITMAP, new Writer() {
            public void write(Parcel p) {
                p.writeInt(fx);
                p.writeInt(fy);
                p.writeInt(fw);
                p.writeInt(fh);
                p.writeInt(fp.length);
                p.writeBlob(fp);
            }
        });
    }

    // ---- Geometry ----

    public float[] panelSize() {
        IBinder b = mBinder;
        if (b == null) {
            return null;
        }
        Float w = readFloat(b, GET_EPD_WIDTH);
        Float h = readFloat(b, GET_EPD_HEIGHT);
        if (w == null || h == null) {
            return null;
        }
        return new float[]{w.floatValue(), h.floatValue()};
    }

    public float[] digitizerSize() {
        IBinder b = mBinder;
        if (b == null) {
            return null;
        }
        Float w = readFloat(b, GET_TOUCH_WIDTH);
        Float h = readFloat(b, GET_TOUCH_HEIGHT);
        if (w == null || h == null) {
            return null;
        }
        return new float[]{w.floatValue(), h.floatValue()};
    }

    public float[] mapToPanel(float x, float y) {
        IBinder b = mBinder;
        if (b == null) {
            return null;
        }
        final float fx = x;
        final float fy = y;
        return (float[]) transact(b, MAP_TO_EPD, new Writer() {
            public void write(Parcel p) {
                p.writeFloat(fx);
                p.writeFloat(fy);
            }
        }, new Reader() {
            public Object read(Parcel r) {
                return new float[]{r.readFloat(), r.readFloat()};
            }
        });
    }

    public float[] mapDigitizerToScreen(float x, float y) {
        IBinder b = mBinder;
        if (b == null) {
            return null;
        }
        final float fx = x;
        final float fy = y;
        return (float[]) transact(b, MAP_TO_VIEW, new Writer() {
            public void write(Parcel p) {
                p.writeFloat(fx);
                p.writeFloat(fy);
            }
        }, new Reader() {
            public Object read(Parcel r) {
                return new float[]{r.readFloat(), r.readFloat()};
            }
        });
    }

    public float[] panelToScreenMatrix() {
        IBinder b = mBinder;
        if (b == null) {
            return null;
        }
        return (float[]) transact(b, GET_EPD_TO_VIEW_MATRIX, null, new Reader() {
            public Object read(Parcel r) {
                int n = r.readInt();
                if (n < 1 || n > 32) {
                    return null;
                }
                float[] out = new float[n];
                for (int i = 0; i < n; i++) {
                    out[i] = r.readFloat();
                }
                return out;
            }
        });
    }

    public Float strokeWidthAt(int kind, float baseWidth, float x, float y, float pressure, float size, float timeMs) {
        IBinder b = mBinder;
        if (b == null) {
            return null;
        }
        final int code = kind == 0 ? START_STROKE : (kind == 1 ? ADD_STROKE_POINT : FINISH_STROKE);
        final float a = baseWidth;
        final float fx = x;
        final float fy = y;
        final float fp = pressure;
        final float fs = size;
        final float ft = timeMs;
        return (Float) transact(b, code, new Writer() {
            public void write(Parcel p) {
                p.writeFloat(a);
                p.writeFloat(fx);
                p.writeFloat(fy);
                p.writeFloat(fp);
                p.writeFloat(fs);
                p.writeFloat(ft);
            }
        }, new Reader() {
            public Object read(Parcel r) {
                return Float.valueOf(r.readFloat());
            }
        });
    }

    // ---- Region pen config (advanced / *[verify]*) ----

    /**
     * Per-part pen configuration for one screen rectangle. {@code buttons} is a flat array of
     * 6-tuples: {button, style, widthPx(as float bits), argb, eraserPreview, eraserPainter, paramCount,
     * then paramCount floats}. Use {@link #encodeButton} to build it from JSON-ish input in the bridge.
     */
    public boolean setRegionPenConfig(int[] rect, float[][] buttons) {
        final int[] fr = rect;
        final float[][] fb = buttons;
        return call(SET_REGION_PEN_CONFIG, new Writer() {
            public void write(Parcel p) {
                p.writeInt(1);
                p.writeInt(1);
                sized(p, new Writer() {
                    public void write(Parcel q) {
                        for (int i = 0; i < 4 && i < fr.length; i++) {
                            q.writeInt(fr[i]);
                        }
                        q.writeInt(fb.length);
                        for (int i = 0; i < fb.length; i++) {
                            final float[] b = fb[i];
                            q.writeInt(1);
                            sized(q, new Writer() {
                                public void write(Parcel s) {
                                    s.writeInt((int) b[0]);          // button
                                    s.writeInt(1);
                                    sized(s, new Writer() {
                                        public void write(Parcel t) {
                                            t.writeInt((int) b[1]);      // style
                                            t.writeFloat(b[2]);          // width
                                            t.writeInt((int) b[3]);      // argb
                                            int count = b.length > 4 ? (int) b[4] : 0;
                                            t.writeInt(count);
                                            for (int k = 0; k < count; k++) {
                                                t.writeFloat(b[5 + k]);
                                            }
                                            t.writeInt(b.length > 5 + count ? (int) b[5 + count] : 0);
                                            t.writeInt(b.length > 6 + count ? (int) b[6 + count] : 0);
                                        }
                                    });
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    public boolean clearRegionPenConfig(int[] rect) {
        final int[] fr = rect;
        return call(CLEAR_REGION_PEN_CONFIG, new Writer() {
            public void write(Parcel p) {
                if (fr == null) {
                    p.writeInt(0);
                    p.writeInt(0);
                } else {
                    p.writeInt(1);
                    p.writeInt(1);
                    for (int i = 0; i < 4 && i < fr.length; i++) {
                        p.writeInt(fr[i]);
                    }
                }
            }
        });
    }

    // ---- Recovery ----

    /** Ends any session and undoes everything a session may have changed. Always safe to call. */
    public boolean release() {
        clearRegionPenConfig(null);
        setRegionExclude(new int[0], true);
        setRegionMode(REGION_MODE_SINGLE);
        setBrushRawDrawing(true);
        setEraserRawDrawing(false, RAW_DEFAULT_ERASER_PAINTER);
        enablePost(true);
        setPenState(PEN_STATE_STOP);
        setAutoSync(true);
        clearTransientUpdate(true);
        repaintEverythingDefault();
        return true;
    }

    // ---- Transport ----

    private interface Writer {
        void write(Parcel p);
    }

    private interface Reader {
        Object read(Parcel p);
    }

    /** Writes a size-prefixed record, as AIDL parcelables are: an int length, then the fields. */
    private static void sized(Parcel p, Writer body) {
        int start = p.dataPosition();
        p.writeInt(0);
        body.write(p);
        int end = p.dataPosition();
        p.setDataPosition(start);
        p.writeInt(end - start);
        p.setDataPosition(end);
    }

    private boolean call(int code, Writer write) {
        IBinder b = mBinder;
        if (b == null) {
            return false;
        }
        return transact(b, code, write, null) != null;
    }

    private Integer readInt(IBinder b, int code) {
        Object o = transact(b, code, null, new Reader() {
            public Object read(Parcel p) {
                return Integer.valueOf(p.readInt());
            }
        });
        return (Integer) o;
    }

    private Float readFloat(IBinder b, int code) {
        Object o = transact(b, code, null, new Reader() {
            public Object read(Parcel p) {
                return Float.valueOf(p.readFloat());
            }
        });
        return (Float) o;
    }

    private Object transact(IBinder b, int code, Writer write, Reader read) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOKEN);
            if (write != null) {
                write.write(data);
            }
            if (!b.transact(code, data, reply, 0)) {
                noteFailure(code);
                return null;
            }
            if (read == null) {
                return Boolean.TRUE;
            }
            return read.read(reply);
        } catch (Throwable t) {
            noteFailure(code);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Logs the first failure of each code only, so a firmware without a call does not flood. */
    private void noteFailure(int code) {
        if (mFailed.add(Integer.valueOf(code))) {
            android.util.Log.w("OnyxObsidian", "display call failed: " + code);
        }
    }
}
