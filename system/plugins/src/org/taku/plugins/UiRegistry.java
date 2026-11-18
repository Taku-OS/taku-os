package org.taku.plugins;

public interface UiRegistry {
    void register(String id, String title, Runnable open);
    void unregister(String id);
}
