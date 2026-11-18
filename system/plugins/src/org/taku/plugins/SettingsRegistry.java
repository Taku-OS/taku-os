package org.taku.plugins;

public interface SettingsRegistry {
    void register(String key, String title, String defaultValue);
    String get(String key);
    void set(String key, String value);
    void unregister(String key);
}
