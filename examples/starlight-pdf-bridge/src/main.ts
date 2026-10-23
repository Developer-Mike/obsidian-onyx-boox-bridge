import { Plugin } from "obsidian";

// Starlight's tool -> BOOX display stroke style.
//   0 pencil, 2 marker, 5 dash, 7 square, 8 eraser track
const STYLE: Record<string, number> = {
  pen: 0,
  highlighter: 2,
  select: 7, // square
  eraser: 8,
  line: 5, // dash
};

function toArgb(hex: string): number {
  const h = String(hex).replace("#", "").trim();
  let rgb = 0;
  if (h.length === 3) {
    rgb = parseInt(h.split("").map((c) => c + c).join(""), 16);
  } else {
    rgb = parseInt(h.slice(0, 6), 16);
  }
  if (!isFinite(rgb)) {
    rgb = 0;
  }
  return (0xff000000 | (rgb & 0xffffff)) | 0;
}

export default class StarlightPdfBridge extends Plugin {
  // Called on each pen pointerdown. Only draw when the pen is over Starlight's PDF canvas.
  private filter = (e: PointerEvent): boolean => {
    const target = e.target as HTMLElement | null;
    if (!target || typeof target.closest !== "function" || !target.closest(".pdf-canvas-container")) {
      return false;
    }

    const ox = (window as any).onyx;
    const tool = (((window as any).STARLIGHT_DEBUG_VALUES || {}).tool) || {};
    const name: string = tool.tool;

    if (ox) {
      const style = STYLE[name];
      if (style !== undefined) {
        ox.setStrokeStyle(style);
      }
      // pen, line and highlighter carry a colour
      if ((name === "pen" || name === "line" || name === "highlighter") && typeof tool.color === "string") {
        ox.setStrokeColor(toArgb(tool.color));
      }
      // pen and line carry a size
      if ((name === "pen" || name === "line") && typeof tool.size === "number" && tool.size > 0) {
        ox.setAutoInkWidth(tool.size);
      }
    }

    return true;
  };

  async onload() {
    const ox = (window as any).onyx;
    if (!ox) {
      console.log("[starlight-pdf-bridge] no bridge; this Obsidian build is not patched");
      return;
    }
    if (!ox.isAvailable()) ox.connect();
    ox.setAutoInk(true);
    ox.setAutoInkFilter(this.filter);
    console.log("[starlight-pdf-bridge] loaded");
  }

  onunload() {
    const ox = (window as any).onyx;
    if (ox) {
      ox.setAutoInkFilter(null);
      ox.setAutoInk(false);
    }
  }
}
