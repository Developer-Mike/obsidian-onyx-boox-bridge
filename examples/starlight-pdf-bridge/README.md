# Starlight PDF Bridge — example plugin

Bridges Starlight PDF's canvas to the BOOX display via the patched Obsidian `window.onyx` API.

- On load: enables the automatic fast-ink session (`setAutoInk(true)`) and registers a filter.
- On unload: clears the filter and disables the session.
- The filter draws only when the pen touches a descendant of `.pdf-canvas-container`, and sets the
  display stroke to match Starlight's current tool before returning:

| Studio tool   | `window.STARLIGHT_DEBUG_VALUES.tool.tool` | Display style | Extra |
|---------------|-------------------------------------------|---------------|-------|
| Pen           | `"pen"`                                   | pencil (0)    | `tool.color`, `tool.size` |
| Highlighter   | `"highlighter"`                           | marker (2)    | `tool.color` |
| Select        | `"select"`                                | square (7)    | — |
| Eraser        | `"eraser"`                                | eraser track (8) | — |
| Line          | `"line"`                                  | dash (5)      | `tool.color`, `tool.size` |

`tool.color` is a hex string with a leading `#`; `tool.size` is a float (physical px).

Build:

```sh
cd examples/starlight-pdf-bridge
npm install
npm run build          # produces main.js
```
