package org.taku.plugins.host;

import java.io.File;
import java.util.*;
import org.taku.plugins.*;

final class RuntimeContext implements PluginContext {
    final Map<String,String> pages = new TreeMap<>();
    final Map<String,Map<String,String>> settings = new TreeMap<>();
    final Map<String,Object> capabilities = new TreeMap<>();
    ToolProvider provider;
    private final String id;
    private final File directory;
    RuntimeContext(String id, File directory) { this.id = id; this.directory = directory; }
    public String pluginId() { return id; }
    public File pluginDirectory() { return directory; }
    public UiRegistry ui() { return new UiRegistry() {
        public void register(String key, String title, Runnable open) { pages.put(key, title); }
        public void unregister(String key) { pages.remove(key); }
    }; }
    public SettingsRegistry settings() { return new SettingsRegistry() {
        public void register(String key, String title, String defaultValue) {
            settings.putIfAbsent(key, new LinkedHashMap<>(Map.of("title", title, "value", defaultValue)));
        }
        public String get(String key) { return settings.containsKey(key) ? settings.get(key).get("value") : null; }
        public void set(String key, String value) { if (!settings.containsKey(key)) throw new IllegalArgumentException("setting not registered: " + key); settings.get(key).put("value", value); }
        public void unregister(String key) { settings.remove(key); }
    }; }
    public CapabilityRegistry capabilities() { return new CapabilityRegistry() {
        public Set<String> available() { return Collections.unmodifiableSet(capabilities.keySet()); }
        public void register(String name, Object service) { capabilities.put(name, service); }
        public void unregister(String name) { capabilities.remove(name); }
        public Object find(String name) { return capabilities.get(name); }
    }; }
    public HookContext hooks() { return (className, methodName, parameters, before, after) -> { throw new UnsupportedOperationException("no hook backend installed"); }; }
    Map<String,Object> snapshot() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("ui", new TreeMap<>(pages));
        result.put("settings", new TreeMap<>(settings));
        result.put("capabilities", new ArrayList<>(capabilities.keySet()));
        if (provider != null) {
            result.put("tools", provider.tools(null));
            result.put("skills", provider.skills());
            result.put("health", provider.alive() ? "running" : "stopped");
        }
        return result;
    }
}
