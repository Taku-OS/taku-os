package org.taku.plugins;

import java.io.File;

public interface PluginContext {
    String pluginId();
    File pluginDirectory();
    UiRegistry ui();
    SettingsRegistry settings();
    CapabilityRegistry capabilities();
    HookContext hooks();
}
