/*
 * Types for the BOOX ink bridge exposed by the patched Obsidian APK as `window.onyx`.
 */

export interface OnyxStartConfig {
  style?: number;
  width?: number;
  color?: number;
  params?: number[];
  region?: [number, number, number, number];
  exclude?: [number, number, number, number][];
  screen?: boolean;
  mode?: "multi" | "single";
  brush?: boolean;
  eraser?: boolean;
  eraserStyle?: number;
  hold?: boolean;
  autoSync?: boolean;
}

export interface OnyxApi {
  version: string;

  PenState: Record<string, number>;
  Style: Record<string, number>;
  UpdateMode: Record<string, number>;
  RegionMode: Record<string, number>;

  on(name: string, fn: (detail?: unknown) => void): () => void;
  off(name: string, fn: (detail?: unknown) => void): void;

  isAvailable(): boolean;
  connect(): boolean;
  release(): boolean;
  maxTouchPressure(): number;

  penState(): number;
  setPenState(state: number): boolean;
  startPen(): boolean;
  pausePen(): boolean;
  stopPen(): boolean;

  setStroke(style: number, width: number, color: number): boolean;
  setStrokeStyle(style: number): boolean;
  setStrokeWidth(width: number): boolean;
  setStrokeColor(color: number): boolean;
  getStrokeParameters(style: number): number[];
  setStrokeParameters(style: number, params: number[]): boolean;

  // app-fed stroke (what the automatic session uses)
  moveTo(x: number, y: number, width: number): boolean;
  quadTo(x: number, y: number, mode?: number): boolean;
  penUp(): boolean;

  setRegionMode(mode: number): boolean;
  setRegionLimit(rect: number[], screen?: boolean): boolean;
  setRegionExclude(rects: number[][], screen?: boolean): boolean;

  enablePost(on: boolean): boolean;
  holdFrames(): boolean;
  releaseFrames(): boolean;
  setAutoSync(on: boolean): boolean;

  repaintEverything(mode?: number): boolean;
  refresh(l: number, t: number, w: number, h: number, mode: number): boolean;
  handwritingRepaint(rect: number[]): boolean;
  applyGcOnce(): boolean;
  applyTransientUpdate(mode?: number): boolean;
  clearTransientUpdate(reset?: boolean): boolean;
  enableRegal(on: boolean): boolean;
  fastModeIndex(): number;

  screenSize(): number[];
  viewOffset(): number[];
  panelSize(): number[];
  digitizerSize(): number[];
  mapToPanel(x: number, y: number): number[];
  mapDigitizerToScreen(x: number, y: number): number[];

  // automatic fast writing
  setAutoInk(on: boolean, width?: number): boolean;
  isAutoInk(): boolean;
  setAutoInkWidth(width: number): number;
  autoInkState(): string;

  startSession(config: OnyxStartConfig): boolean;
  endSession(config?: { hold?: boolean; repaint?: boolean; repaintMode?: number }): boolean;
}

declare global {
  interface Window {
    onyx?: OnyxApi;
    __onyxBridge?: unknown;
    __onyxNative?: unknown;
    __onyxShim?: unknown;
  }
}

export function getOnyx(): OnyxApi | undefined {
  return typeof window !== "undefined" ? window.onyx : undefined;
}
