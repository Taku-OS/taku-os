package org.example;

import org.taku.plugins.PluginContext;
import org.taku.plugins.TakuPlugin;

public final class StatusBarPlugin implements TakuPlugin {
    private PluginContext context;
    public void onLoad(PluginContext value) { context = value; }
    public void onStart() { context.capabilities().register("example.status-bar", this); }
    public void onStop() { context.capabilities().unregister("example.status-bar"); }
    public void onReload() { }
    public void onUninstall() { }
}
