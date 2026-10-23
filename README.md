# Onyx Boox - Obsidian - Fast Ink Bridge

Patch an Obsidian (Capacitor) APK to add a BOOX e-ink pen bridge, exposed to the page as
`window.onyx`. Fast, display-drawn pen strokes work out of the box, and every Obsidian plugin can
re-tune the pen at runtime — no per-plugin APK patch needed.

```sh
python3 onyx_patch.py patch Obsidian.apk -o Obsidian-onyx.apk
```

What the patch does to the APK:

1. **apktool** decodes resources/manifest.
2. An empty `<provider>` is added. It runs at process start and attaches the bridge to the Capacitor
   activity's WebView — no smali editing and no Capacitor-version dependency.
3. `assets/onyx/shim.js` is added: the `window.onyx` JavaScript API.
4. A small dex is compiled from `injector/` (JDK + R8/d8) and added as `classesN.dex`.
5. The APK is rebuilt, aligned and signed.

That is the whole footprint: one provider, one asset, one dex. Nothing from the BOOX SDK is bundled.

---

## How the fast ink works

Obsidian renders strokes in its WebView, which is slow on e-ink. This firmware's display does not
preview strokes on its own, so the bridge drives it the way the official Onyx SDK does on these
devices (app-fed strokes):

* a pen session is opened and put into **pen state 2 (DRAWING)** — this is what puts the controller
  into its fast handwriting scheme. (State 1 / START alone renders strokes at normal speed.)
* when the stylus touches, the WebView's frames are **held** (`enablePost(false)`) so Obsidian's slow
  frames don't cut into the stroke,
* each pen movement is sent to the display as `moveTo` / `quadTo` in **screen coordinates**, so the
  display paints the stroke itself (fast), and `penUp` ends it,
* ~450 ms after the pen lifts, the frames are released and the stroke area is repainted, revealing
  Obsidian's own ink.

This is done by a small automatic session in `shim.js`, enabled by default. A plugin can turn it off
and drive the primitives itself.

The transaction codes and payloads were read from this tablet's framework
(`android.onyx.ViewUpdateHelper`) and cross-checked against the Onyx SDK's `AppTouchRender`.

## Verified on hardware

Tested end to end on a **BOOX Note Air 1** (SDM636, Android 10 / API 29, firmware
`2023-12-12_21-24_3.5_4687b1901`), WebView 108, Obsidian 1.14.4:

| Check | Result |
|---|---|
| apktool decode / rebuild / sign | ok (`classes3.dex` added) |
| Provider attaches to the WebView | `OnyxObsidian: bridge installed on webview` |
| `connect()` probe | `true`, max pressure 4095 |
| `window.onyx` | present, all methods, fast-ink session auto-started |
| Fast pen strokes | display-drawn strokes land under the pen; ~450 ms later the real rerender replaces them |
| Obsidian itself | launches, opens the vault, no crash |

Notes for this firmware:

* The display does **not** self-preview; strokes must be app-fed (`moveTo`/`quadTo`/`penUp`).
* Those calls use **flag 1 / screen coordinates** (the framework's "a `View` was passed" case).
* The session must be in **pen state 2 (DRAWING)** while active, matching the SDK; with only state 1
  the strokes still draw but at normal (slow) speed.
* `setAutoSync` and `clearRegionPenConfig` are not accepted here (harmless, logged once).

---

## Requirements

* Python 3.8+
* A JDK 17 (or any JDK that still supports `javac --release 8`): `java`, `javac`, `jar`, `keytool`

Everything else is downloaded automatically into `~/.cache/onyx-patch/` on first run: apktool, R8
(d8), and uber-apk-signer (unless `zipalign` + `apksigner` are on `PATH`).

On NixOS: `nix-shell` (uses `shell.nix`), then `python3 onyx_patch.py doctor`.

---

## Usage

```sh
# patch and sign (output defaults to Obsidian-onyx.apk)
python3 onyx_patch.py patch Obsidian.apk

# other options
python3 onyx_patch.py patch Obsidian.apk -o out.apk --keep-work
python3 onyx_patch.py patch Obsidian.apk --offline --apktool apktool.jar --r8-jar r8.jar
python3 onyx_patch.py patch Obsidian.apk --injector-dex injector.dex --no-sign
python3 onyx_patch.py doctor
```

Install (the APK is signed with a new key, so uninstall the official app first):

```sh
adb uninstall md.obsidian
adb install Obsidian-onyx.apk
```

> Your vaults live in shared storage and are not touched; app-internal settings may be lost when the
> official install is removed.

---

## `window.onyx` API

`window.__onyxBridge` is the raw Java object; the shim wraps it as `window.onyx` (and keeps the raw
one at `window.__onyxNative`). Everything is synchronous.

### Constants

```js
onyx.Style        // PENCIL 0, FOUNTAIN 1, MARKER 2, BRUSH 3, CHARCOAL 4,
                  // DASH 5, CHARCOAL_V2 6, SQUARE 7, ERASER_TRACK 8
onyx.PenState     // STOP 0, START 1, DRAW 2, PAUSE 3, PAUSED 4
onyx.RegionMode   // MULTI 0, SINGLE 1
onyx.UpdateMode   // DU 1, GU 2, GC4 3, A2 4, DEFAULT 5, REGAL 6, REGAL_PLUS 9,
                  // GC 98, GCC 107, DEEP_GC 108, HANDWRITING 524290,
                  // DU_QUALITY 2305, A2_QUALITY 2308, DU4 2312, MONO_A2 33554436
```

### Automatic fast writing

```js
onyx.setAutoInk(true);        // default: on. false hands control to your plugin
onyx.setAutoInk(false);
onyx.setAutoInk(true, 8);     // arm with a specific width
onyx.setAutoInkWidth(6);      // retune while running
onyx.isAutoInk();             // boolean
onyx.autoInkState();          // "{"on":true,"held":false,"width":3}"
```

### App-fed stroke (what the auto session uses)

```js
onyx.moveTo(x, y, width);     // screen pixels; starts a stroke
onyx.quadTo(x, y, onyx.UpdateMode.DU);
onyx.penUp();
```

### Meta / session / stroke

```js
onyx.isAvailable(); onyx.connect(); onyx.release(); onyx.maxTouchPressure();
onyx.penState(); onyx.setPenState(onyx.PenState.START); onyx.startPen(); onyx.pausePen(); onyx.stopPen();
onyx.setStroke(style, widthPx, argb); onyx.setStrokeStyle(style); onyx.setStrokeWidth(px); onyx.setStrokeColor(argb);
onyx.getStrokeParameters(style); onyx.setStrokeParameters(style, [..]);
```

### Regions, hold and refresh

```js
onyx.setRegionMode(onyx.RegionMode.MULTI);
onyx.setRegionLimit([l, t, r, b], /* screen */ true);
onyx.setRegionExclude([[l,t,r,b], ...], true);
onyx.enablePost(false); onyx.holdFrames(); onyx.releaseFrames();
onyx.handwritingRepaint([l, t, r, b]); onyx.repaintEverything(mode); onyx.applyGcOnce();
onyx.refresh(left, top, width, height, mode);
onyx.applyTransientUpdate(onyx.UpdateMode.A2_QUALITY); onyx.clearTransientUpdate(true);
onyx.enableRegal(true); onyx.fastModeIndex(); onyx.waitForUpdate();
```

### Geometry

```js
onyx.screenSize();               // [w, h] in physical pixels
onyx.viewOffset();               // [x, y] of the WebView on screen
onyx.panelSize(); onyx.digitizerSize();
onyx.mapToPanel(x, y); onyx.mapDigitizerToScreen(x, y); onyx.panelToScreenMatrix();
```

### High-level session (opt out of the automatic one)

```js
onyx.setAutoInk(false);
onyx.startSession({ style: onyx.Style.FOUNTAIN, width: 3, color: 0xff101010 | 0,
                    region: [0,0,w,h], exclude: [[0,0,w,80]], mode: "multi", hold: true });
onyx.endSession({ hold: true, repaint: true });
```

---

## Writing a plugin

Fast ink already works; a plugin usually only needs to tune it:

```ts
const ox = (window as any).onyx;
if (ox) {
  ox.setAutoInkWidth(6);   // bolder preview
  // or take full control:
  // ox.setAutoInk(false);
  // ox.setRegionLimit([0, 0, ox.screenSize()[0], ox.screenSize()[1]], true);
}
```

A buildable example (toggle + width/style settings) is in
[`examples/obsidian-onyx-bridge`](examples/obsidian-onyx-bridge).

---

## Firmware / device notes

* Codes are from firmware 3.5's `android.onyx.ViewUpdateHelper`; the same codes are used by firmware
  4.3. `connect()` validates before anything is driven.
* If `connect()` returns false on a BOOX device, send the `adb logcat -s OnyxObsidian` output — the
  codes are named constants in `OnyxEpd.java`.
* Some calls (`startStroke`, `drawHandwritingBitmap`, region pen config) exist but aren't used by the
  automatic session.

---

## Layout

```
onyx_patch.py                        the tool (doctor, patch)
injector/
  stubs/android/...                  compile-time-only Android stubs (never packaged)
  src/app/onyx/obsidian/
    OnyxEpd.java                     SurfaceFlinger transaction client (reflection)
    OnyxJs.java                      @JavascriptInterface bridge (window.__onyxBridge)
    Injector.java                    finds the WebView, installs the bridge + shim
    OnyxInitProvider.java            early entry point (registered in the manifest)
  assets/onyx/shim.js                the window.onyx API + automatic fast writing
examples/obsidian-onyx-bridge/       example plugin
```

---

## Scope

This patches your own copy of an app for personal use on hardware you own. It bundles no BOOX code
and no Obsidian code. Don't redistribute the patched Obsidian APK.
