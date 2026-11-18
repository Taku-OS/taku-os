package org.taku.plugins;

import java.util.Set;

public interface CapabilityRegistry {
    Set<String> available();
    void register(String name, Object service);
    void unregister(String name);
    Object find(String name);
}
