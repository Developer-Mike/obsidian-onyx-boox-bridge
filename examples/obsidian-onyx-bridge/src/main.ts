import { App, Notice, Plugin, PluginSettingTab, Setting } from "obsidian";
import { getOnyx } from "./onyx";

interface OnyxSettings {
  enabled: boolean;
  width: number;
}

const DEFAULTS: OnyxSettings = { enabled: false, width: 4 };

export default class OnyxBridgeDemo extends Plugin {
  settings: OnyxSettings;
  private ribbonEl: HTMLElement | null = null;

  async onload() {
    await this.loadSettings();
    this.addSettingTab(new OnyxSettingTab(this.app, this));

    this.ribbonEl = this.addRibbonIcon("pen-tool", "Toggle BOOX fast ink", () => this.toggle());

    this.addCommand({
      id: "toggle-fast-ink",
      name: "Toggle fast ink",
      callback: () => this.toggle(),
    });

    this.addCommand({
      id: "clean-screen",
      name: "Clean screen (deep GC)",
      callback: () => getOnyx()?.applyGcOnce(),
    });

    const ox = getOnyx();
    if (!ox) {
      console.log("[onyx-bridge] no bridge; this Obsidian build is not patched");
      return;
    }
    ox.setAutoInkWidth(this.settings.width);
    if (this.settings.enabled) {
      ox.setAutoInk(true);
    }
    this.updateRibbon();
    console.log("[onyx-bridge] ready; fast ink =", ox.isAutoInk());
  }

  onunload() {
    // Leave the display clean if we were driving it.
    this.settings.enabled = false;
    getOnyx()?.setAutoInk(false);
  }

  private toggle() {
    const ox = getOnyx();
    if (!ox) {
      new Notice("BOOX ink bridge not found — install a patched Obsidian APK.");
      return;
    }
    if (!ox.isAvailable() && !ox.connect()) {
      new Notice("Display did not answer the BOOX pen API on this device.");
      return;
    }
    this.settings.enabled = !ox.isAutoInk();
    ox.setAutoInkWidth(this.settings.width);
    ox.setAutoInk(this.settings.enabled);
    this.updateRibbon();
    new Notice("Fast ink " + (this.settings.enabled ? "on" : "off"));
    void this.saveSettings();
  }

  private updateRibbon() {
    const on = !!getOnyx()?.isAutoInk();
    this.ribbonEl?.toggleClass("is-active", on);
    this.ribbonEl?.setAttribute("aria-label", "BOOX fast ink: " + (on ? "on" : "off"));
  }

  async loadSettings() {
    this.settings = Object.assign({}, DEFAULTS, await this.loadData());
  }

  async saveSettings() {
    await this.saveData(this.settings);
  }
}

class OnyxSettingTab extends PluginSettingTab {
  plugin: OnyxBridgeDemo;

  constructor(app: App, plugin: OnyxBridgeDemo) {
    super(app, plugin);
    this.plugin = plugin;
  }

  display(): void {
    const { containerEl } = this;
    containerEl.empty();

    new Setting(containerEl)
      .setName("Fast ink")
      .setDesc("Draw pen strokes on the display itself for low latency.")
      .addToggle((t) =>
        t.setValue(this.plugin.settings.enabled).onChange(async (v) => {
          this.plugin.settings.enabled = v;
          const ox = getOnyx();
          if (ox) {
            if (v && !ox.isAvailable()) ox.connect();
            ox.setAutoInkWidth(this.plugin.settings.width);
            ox.setAutoInk(v);
          }
          await this.plugin.saveSettings();
        })
      );

    new Setting(containerEl)
      .setName("Pen width (px)")
      .setDesc("Width of the display-drawn preview stroke, in physical pixels.")
      .addSlider((s) =>
        s
          .setLimits(1, 24, 1)
          .setValue(this.plugin.settings.width)
          .setDynamicTooltip()
          .onChange(async (v) => {
            this.plugin.settings.width = v;
            getOnyx()?.setAutoInkWidth(v);
            await this.plugin.saveSettings();
          })
      );
  }
}
