package org.taku.plugins.host;

import java.util.*;

final class PluginRecord {
    final String id;
    String generation;
    boolean enabled;
    String error;
    String previousGeneration;
    PluginRecord(String id, String generation, boolean enabled, String error, String previousGeneration) {
        this.id = id;
        this.generation = generation;
        this.enabled = enabled;
        this.error = error;
        this.previousGeneration = previousGeneration;
    }
    Map<String,Object> json() {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("generation", generation); value.put("enabled", enabled); value.put("error", error);
        value.put("previousGeneration", previousGeneration);
        return value;
    }
}
