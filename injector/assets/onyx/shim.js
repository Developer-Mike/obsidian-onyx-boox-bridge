/*
 * window.onyx — the BOOX ink bridge for Obsidian.
 *
 * The Java side exposes the raw display client as `window.__onyxBridge`; this shim wraps it into
 * `window.onyx` with named constants, array arguments and a few high level helpers. It is idempotent,
 * so it is safe to evaluate repeatedly and it re-installs after a page reload. The automatic pen
 * session is OFF by default — a plugin starts it with `setAutoInk(true)`.
 *
 * Fast writing: while the stylus is down the app's frames are held and the stroke is fed to the
 * display as `moveTo`/`quadTo`/`penUp` in screen coordinates, so the display paints it; a short pause
 * after the pen lifts releases the frames and repaints, revealing the app's own ink.
 */
(function () {
  "use strict";

  var raw = (typeof window !== "undefined")
    ? (window.__onyxBridge || window.onyx)
    : undefined;
  if (!raw || typeof raw.isAvailable !== "function") {
    return;
  }
  if (window.onyx && window.onyx.__isOnyxShim === true && window.onyx.__native === raw) {
    return;
  }

  window.__onyxNative = raw;

  var PenState = { STOP: 0, START: 1, DRAW: 2, PAUSE: 3, PAUSED: 4 };
  var Style = {
    PENCIL: 0, FOUNTAIN: 1, MARKER: 2, BRUSH: 3, CHARCOAL: 4,
    DASH: 5, CHARCOAL_V2: 6, SQUARE: 7, ERASER_TRACK: 8
  };
  var UpdateMode = {
    DU: 1, GU: 2, GC4: 3, A2: 4, DEFAULT: 5, REGAL: 6, REGAL_PLUS: 9,
    GC: 98, GCC: 107, DEEP_GC: 108, HANDWRITING: 524290,
    DU_QUALITY: 2305, A2_QUALITY: 2308, DU4: 2312, MONO_A2: 33554436
  };
  var RegionMode = { MULTI: 0, SINGLE: 1 };

  var BREAK_MS = 450;      // release + reveal this long after the pen lifts
  var FAILSAFE_MS = 4000;  // never leave the display held if a pen-up is missed

  function csv(a) {
    if (a === null || a === undefined) return "";
    if (typeof a === "string") return a;
    if (typeof a === "number") return String(a);
    var out = [];
    for (var i = 0; i < a.length; i++) out.push(a[i]);
    return out.join(",");
  }

  function csvRects(rects) {
    if (!rects) return "";
    if (typeof rects === "string") return rects;
    var out = [];
    for (var i = 0; i < rects.length; i++) out.push(csv(rects[i]));
    return out.join(";");
  }

  function nums(s) {
    if (!s || s === "null") return [];
    return String(s).split(",").map(Number);
  }

  function encodeButtons(buttons) {
    if (!buttons) return "";
    var rows = [];
    for (var i = 0; i < buttons.length; i++) {
      var b = buttons[i];
      var row = [
        b.button | 0,
        b.style | 0,
        b.width === undefined ? 2 : +b.width,
        b.color | 0,
        b.eraserPreview ? 1 : 0,
        b.eraserPainter === undefined ? 5 : b.eraserPainter | 0
      ];
      var params = b.params || [];
      for (var k = 0; k < params.length; k++) row.push(+params[k]);
      rows.push(row.join(","));
    }
    return rows.join(";");
  }

  var listeners = {};

  function on(name, fn) {
    (listeners[name] = listeners[name] || []).push(fn);
    return function off() {
      var a = listeners[name] || [];
      var idx = a.indexOf(fn);
      if (idx >= 0) a.splice(idx, 1);
    };
  }

  function emit(name, detail) {
    var a = listeners[name];
    if (a) {
      for (var i = 0; i < a.length; i++) {
        try { a[i](detail); } catch (e) { /* ignore listener errors */ }
      }
    }
    try {
      window.dispatchEvent(new CustomEvent("onyx:" + name, { detail: detail }));
    } catch (e) { /* ignore */ }
  }

  // ---- geometry helpers -------------------------------------------------------

  function screenSize() {
    var s = "";
    try { s = raw.screenSize(); } catch (e) { s = ""; }
    if (!s) {
      return [window.innerWidth || 1404, window.innerHeight || 1872];
    }
    var a = s.split(",");
    return [parseInt(a[0], 10) || 1404, parseInt(a[1], 10) || 1872];
  }

  function viewOffset() {
    try {
      var s = raw.viewOffset();
      if (s) {
        var a = s.split(",");
        return [parseInt(a[0], 10) || 0, parseInt(a[1], 10) || 0];
      }
    } catch (e) { /* ignore */ }
    return [0, 0];
  }

  function physPerCss() {
    var ss = screenSize();
    var cssW = (document.documentElement && document.documentElement.clientWidth) || window.innerWidth || ss[0];
    return ss[0] / cssW;
  }

  function clientX(e) { return (e.clientX !== undefined) ? e.clientX : (e.x || 0); }
  function clientY(e) { return (e.clientY !== undefined) ? e.clientY : (e.y || 0); }
  function isPen(e) { return !e.pointerType || e.pointerType === "pen"; }

  // ---- automatic pen session --------------------------------------------------

  var autoInk = {
    on: false, held: false, down: false, timer: 0, maxTimer: 0, has: false,
    minX: 0, minY: 0, maxX: 0, maxY: 0, width: 3, filter: null
  };

  function markStroke(e) {
    var k = physPerCss();
    var off = viewOffset();
    var x = clientX(e) * k + off[0];
    var y = clientY(e) * k + off[1];
    if (!autoInk.has) {
      autoInk.minX = autoInk.maxX = x;
      autoInk.minY = autoInk.maxY = y;
      autoInk.has = true;
    } else {
      if (x < autoInk.minX) autoInk.minX = x;
      if (x > autoInk.maxX) autoInk.maxX = x;
      if (y < autoInk.minY) autoInk.minY = y;
      if (y > autoInk.maxY) autoInk.maxY = y;
    }
  }

  function setPen(state) {
    try { return raw.setPenState(state); } catch (e) { return false; }
  }

  function onPenDown(e) {
    if (!autoInk.on || !isPen(e)) return;
    // Filter hook: return false to cancel this stroke (e.g. the pen touched a UI element).
    if (autoInk.filter) {
      var ok = true;
      try { ok = autoInk.filter(e); } catch (err) { ok = true; }
      if (ok === false) { autoInk.down = false; return; }
    }
    if (autoInk.timer) { clearTimeout(autoInk.timer); autoInk.timer = 0; }
    if (!autoInk.held) { try { raw.enablePost(false); } catch (err) {} autoInk.held = true; }
    setPen(PenState.START);
    setPen(PenState.DRAW);
    autoInk.has = false;
    autoInk.down = true;
    if (autoInk.maxTimer) clearTimeout(autoInk.maxTimer);
    autoInk.maxTimer = setTimeout(forceRelease, FAILSAFE_MS);
    var k = physPerCss();
    var off = viewOffset();
    try { raw.setStrokeWidth(autoInk.width); } catch (err) {}
    try { raw.moveTo(clientX(e) * k + off[0], clientY(e) * k + off[1], autoInk.width); } catch (err) {}
    markStroke(e);
  }

  function onPenMove(e) {
    if (!autoInk.on || !isPen(e)) return;
    if (autoInk.down) {
      var k = physPerCss();
      var off = viewOffset();
      try { raw.quadTo(clientX(e) * k + off[0], clientY(e) * k + off[1], UpdateMode.DU); } catch (err) {}
    }
    markStroke(e);
  }

  function onPenUp(e) {
    if (!autoInk.on || !isPen(e)) return;
    if (!autoInk.down) return;
    autoInk.down = false;
    markStroke(e);
    try { raw.penUp(); } catch (err) {}
    if (autoInk.maxTimer) { clearTimeout(autoInk.maxTimer); autoInk.maxTimer = 0; }
    if (autoInk.timer) clearTimeout(autoInk.timer);
    autoInk.timer = setTimeout(reveal, BREAK_MS);
  }

  function reveal() {
    autoInk.timer = 0;
    if (autoInk.held) { try { raw.enablePost(true); } catch (err) {} autoInk.held = false; }
    if (autoInk.has) {
      var pad = 80;
      var rect = [
        Math.max(0, Math.round(autoInk.minX - pad)),
        Math.max(0, Math.round(autoInk.minY - pad)),
        Math.round(autoInk.maxX + pad),
        Math.round(autoInk.maxY + pad)
      ];
      try { raw.handwritingRepaint(rect.join(",")); } catch (err) {}
    }
    autoInk.has = false;
  }

  function forceRelease() {
    autoInk.maxTimer = 0;
    autoInk.down = false;
    if (autoInk.timer) { clearTimeout(autoInk.timer); autoInk.timer = 0; }
    if (autoInk.held) { try { raw.enablePost(true); } catch (err) {} autoInk.held = false; }
    setPen(PenState.STOP);
    try { raw.repaintEverything(); } catch (err) {}
    autoInk.has = false;
  }

  function startAutoInk() {
    if (autoInk.on) return true;
    try {
      raw.connect();
      var ss = screenSize();
      raw.setRegionExcludes("0,0,0,0", true);
      raw.setRegionLimit("0,0," + ss[0] + "," + ss[1], true);
      raw.setPenState(PenState.START);
      raw.setPenState(PenState.DRAW);
      raw.setStrokeColor(-16777216);
      raw.setStrokeStyle(Style.PENCIL);
      raw.setStrokeWidth(autoInk.width);
    } catch (e) { /* ignore */ }
    autoInk.on = true;
    window.addEventListener("pointerdown", onPenDown, true);
    window.addEventListener("pointermove", onPenMove, true);
    window.addEventListener("pointerup", onPenUp, true);
    window.addEventListener("pointercancel", onPenUp, true);
    window.addEventListener("blur", forceRelease, true);
    emit("autostart", {});
    return true;
  }

  function stopAutoInk() {
    if (!autoInk.on) return true;
    autoInk.on = false;
    window.removeEventListener("pointerdown", onPenDown, true);
    window.removeEventListener("pointermove", onPenMove, true);
    window.removeEventListener("pointerup", onPenUp, true);
    window.removeEventListener("pointercancel", onPenUp, true);
    window.removeEventListener("blur", forceRelease, true);
    if (autoInk.timer) { clearTimeout(autoInk.timer); autoInk.timer = 0; }
    if (autoInk.maxTimer) { clearTimeout(autoInk.maxTimer); autoInk.maxTimer = 0; }
    if (autoInk.held) { try { raw.enablePost(true); } catch (err) {} autoInk.held = false; }
    try { raw.setPenState(PenState.PAUSE); raw.setPenState(PenState.STOP); raw.release(); } catch (e) { /* ignore */ }
    emit("autostop", {});
    return true;
  }

  // ---- API --------------------------------------------------------------------

  var api = {
    version: "1.0.0",
    __isOnyxShim: true,
    __raw: raw,
    __native: raw,

    PenState: PenState,
    Style: Style,
    UpdateMode: UpdateMode,
    RegionMode: RegionMode,
    constants: { PenState: PenState, Style: Style, UpdateMode: UpdateMode, RegionMode: RegionMode },

    on: on,
    off: function (name, fn) {
      var a = listeners[name] || [];
      var idx = a.indexOf(fn);
      if (idx >= 0) a.splice(idx, 1);
    },

    // ---- meta ----
    isAvailable: function () { return raw.isAvailable(); },
    connect: function () {
      var ok = raw.connect();
      if (ok) emit("connect", { maxTouchPressure: raw.maxTouchPressure() });
      return ok;
    },
    release: function () {
      var ok = raw.release();
      emit("release", {});
      return ok;
    },
    maxTouchPressure: function () { return raw.maxTouchPressure(); },
    ownerPid: function () { return raw.ownerPid(); },

    // ---- pen session ----
    penState: function () { return raw.penState(); },
    setPenState: function (s) { return raw.setPenState(s | 0); },
    startPen: function () { return raw.setPenState(PenState.START); },
    pausePen: function () { return raw.setPenState(PenState.PAUSE); },
    stopPen: function () { return raw.setPenState(PenState.STOP); },

    // ---- stroke ----
    setStroke: function (style, width, color) { return raw.setStroke(style | 0, +width, color | 0); },
    setStrokeStyle: function (style) { return raw.setStrokeStyle(style | 0); },
    setStrokeWidth: function (width) { return raw.setStrokeWidth(+width); },
    setStrokeColor: function (color) { return raw.setStrokeColor(color | 0); },
    getStrokeParameters: function (style) { return nums(raw.getStrokeParameters(style | 0)); },
    setStrokeParameters: function (style, params) { return raw.setStrokeParameters(style | 0, csv(params)); },

    // ---- app-fed stroke ----
    moveTo: function (x, y, width) { return raw.moveTo(+x, +y, +width); },
    quadTo: function (x, y, mode) { return raw.quadTo(+x, +y, mode === undefined ? UpdateMode.DU : mode | 0); },
    penUp: function () { return raw.penUp(); },

    // ---- regions ----
    setRegionMode: function (mode) { return raw.setRegionMode(mode | 0); },
    setRegionLimit: function (rect, screen) { return raw.setRegionLimit(csv(rect), screen !== false); },
    setRegionExclude: function (rects, screen) { return raw.setRegionExcludes(csvRects(rects), screen !== false); },
    setRegionPenConfig: function (rect, buttons) {
      return raw.setRegionPenConfig(csv(rect), encodeButtons(buttons));
    },
    clearRegionPenConfig: function (rect) {
      return raw.clearRegionPenConfig(rect ? csv(rect) : "");
    },

    // ---- raw drawing / hold ----
    setBrushRawDrawing: function (on) { return raw.setBrushRawDrawing(!!on); },
    setEraserRawDrawing: function (on, style) {
      return raw.setEraserRawDrawing(!!on, style === undefined ? 5 : style | 0);
    },
    setPenSideButton: function (on) { return raw.setPenSideButton(!!on); },
    setAutoSync: function (on) { return raw.setAutoSync(!!on); },
    enablePost: function (on) { return raw.enablePost(!!on); },
    holdFrames: function () { return raw.enablePost(false); },
    releaseFrames: function () { return raw.enablePost(true); },

    // ---- refresh ----
    repaintEverything: function (mode) {
      return raw.repaintEverything(mode === undefined || mode === null ? -1 : mode | 0);
    },
    refresh: function (l, t, w, h, mode) { return raw.refresh(l | 0, t | 0, w | 0, h | 0, mode | 0); },
    applyGcOnce: function () { return raw.applyGcOnce(); },
    waitForUpdate: function () { return raw.waitForUpdate(); },
    handwritingRepaint: function (rect) { return raw.handwritingRepaint(csv(rect)); },
    applyTransientUpdate: function (mode) {
      return raw.applyTransientUpdate(mode === undefined || mode === null ? UpdateMode.A2 : mode | 0);
    },
    clearTransientUpdate: function (reset) { return raw.clearTransientUpdate(reset !== false); },
    enableRegal: function (on) { return raw.enableRegal(!!on); },
    fastModeIndex: function () { return raw.fastModeIndex(); },
    upNotice: function () { return raw.penUpNotice(); },

    // ---- geometry ----
    screenSize: screenSize,
    viewOffset: viewOffset,
    panelSize: function () { return nums(raw.panelSize()); },
    digitizerSize: function () { return nums(raw.digitizerSize()); },
    mapToPanel: function (x, y) { return nums(raw.mapToPanel(+x, +y)); },
    mapDigitizerToScreen: function (x, y) { return nums(raw.mapDigitizerToScreen(+x, +y)); },
    panelToScreenMatrix: function () { return nums(raw.panelToScreenMatrix()); },
    strokeWidthAt: function (kind, baseWidth, x, y, pressure, size, timeMs) {
      return raw.strokeWidthAt(kind | 0, +baseWidth, +x, +y, +pressure, +size, +timeMs);
    },

    // ---- automatic fast writing ----
    setAutoInk: function (on) { return on ? startAutoInk() : stopAutoInk(); },
    setAutoInkFilter: function (fn) {
      autoInk.filter = (typeof fn === "function") ? fn : null;
      return true;
    },
    isAutoInk: function () { return autoInk.on; },
    setAutoInkWidth: function (w) {
      if (+w > 0) {
        autoInk.width = +w;
      }
      try { raw.setStrokeWidth(autoInk.width); } catch (e) { /* ignore */ }
      return autoInk.width;
    },
    autoInkState: function () {
      return JSON.stringify({ on: autoInk.on, held: autoInk.held, width: autoInk.width });
    },

    // ---- high level session (for plugins that want full control) ----
    startSession: function (o) {
      o = o || {};
      var screen = o.screen !== false;
      stopAutoInk();
      if (!raw.isAvailable()) raw.connect();
      raw.setRegionMode(o.mode === "single" ? RegionMode.SINGLE : RegionMode.MULTI);
      if (o.region) raw.setRegionLimit(csv(o.region), screen);
      raw.setRegionExcludes(csvRects(o.exclude), screen);
      raw.setBrushRawDrawing(o.brush !== false);
      raw.setEraserRawDrawing(!!o.eraser, o.eraserStyle === undefined ? 5 : o.eraserStyle | 0);
      var style = o.style === undefined ? Style.PENCIL : o.style | 0;
      raw.setStroke(style, o.width === undefined ? 2 : +o.width, o.color === undefined ? -16777216 : o.color | 0);
      if (o.params) raw.setStrokeParameters(style, csv(o.params));
      raw.setAutoSync(o.autoSync !== false);
      if (o.hold) raw.enablePost(false);
      raw.setPenState(PenState.START);
      raw.setPenState(PenState.DRAW);
      emit("sessionstart", { config: o });
      return true;
    },

    endSession: function (o) {
      o = o || {};
      raw.setPenState(PenState.PAUSE);
      raw.setPenState(PenState.STOP);
      if (o.hold !== false) raw.enablePost(true);
      if (o.repaint) raw.repaintEverything(o.repaintMode === undefined ? -1 : o.repaintMode | 0);
      emit("sessionend", { config: o });
      return true;
    }
  };

  window.onyx = api;
  window.__onyxShim = api;

  if (window.__onyxAutoInk === true) {
    try { startAutoInk(); } catch (e) { /* ignore */ }
  }

  try {
    window.dispatchEvent(new CustomEvent("onyx:ready", { detail: api }));
  } catch (e) { /* ignore */ }
})();
