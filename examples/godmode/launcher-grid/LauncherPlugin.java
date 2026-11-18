package org.example;

import org.taku.plugins.PluginContext;
import org.taku.plugins.TakuPlugin;

public final class LauncherPlugin implements TakuPlugin {
    private PluginContext context;
    public void onLoad(PluginContext value) { context = value; }
    public void onStart() { context.ui().register("example.grid", "Launcher grid", () -> { }); }
    public void onStop() { context.ui().unregister("example.grid"); }
    public void onReload() { }
    public void onUninstall() { }
}
