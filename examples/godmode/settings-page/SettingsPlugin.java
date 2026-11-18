package org.example;

import org.taku.plugins.PluginContext;
import org.taku.plugins.TakuPlugin;

public final class SettingsPlugin implements TakuPlugin {
    private PluginContext context;
    public void onLoad(PluginContext value) { context = value; }
    public void onStart() {
        context.settings().register("example.message", "Example message", "Hello");
        context.ui().register("example.settings", "Example settings", () -> { });
    }
    public void onStop() {
        context.ui().unregister("example.settings");
        context.settings().unregister("example.message");
    }
    public void onReload() { }
    public void onUninstall() { }
}
