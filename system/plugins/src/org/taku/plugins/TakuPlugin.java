package org.taku.plugins;

public interface TakuPlugin {
    void onLoad(PluginContext context) throws Exception;
    void onStart() throws Exception;
    void onStop() throws Exception;
    void onReload() throws Exception;
    void onUninstall() throws Exception;
}
