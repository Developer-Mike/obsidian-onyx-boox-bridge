# BOOX Ink Bridge — example plugin

Minimal example for the patched Obsidian APK's `window.onyx` API.

Fast ink is off by default in the patch; this plugin is the switch:

* a ribbon button / command toggles the automatic fast-ink session,
* a setting retunes the preview stroke width,
* a "Clean screen" command runs a deep GC repaint (ghost removal).

Build:

```sh
cd examples/obsidian-onyx-bridge
npm install
npm run build          # produces main.js
```

Copy `main.js` and `manifest.json` into `<vault>/.obsidian/plugins/onyx-bridge/` and enable it in
Obsidian. See the main [README](../../README.md) for the full API.
