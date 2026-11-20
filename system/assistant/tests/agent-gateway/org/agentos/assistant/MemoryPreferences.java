package org.agentos.assistant;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    boolean failNextCommit;
    public MemoryPreferences() {}
    MemoryPreferences(MemoryPreferences other) { values.putAll(other.values); }
    public Map<String, ?> getAll() { return new HashMap<>(values); }
    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>) values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
    public float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
    public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    public boolean contains(String key) { return values.containsKey(key); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    public Editor edit() { return new Editor() {
        private final Map<String, Object> edits = new HashMap<>();
        private boolean clear;
        public Editor putString(String key, String value) { edits.put(key, value); return this; }
        public Editor putStringSet(String key, Set<String> value) { edits.put(key, value); return this; }
        public Editor putInt(String key, int value) { edits.put(key, value); return this; }
        public Editor putLong(String key, long value) { edits.put(key, value); return this; }
        public Editor putFloat(String key, float value) { edits.put(key, value); return this; }
        public Editor putBoolean(String key, boolean value) { edits.put(key, value); return this; }
        public Editor remove(String key) { edits.put(key, null); return this; }
        public Editor clear() { clear = true; return this; }
        public void apply() { commit(); }
        public boolean commit() {
            if (clear) values.clear();
            edits.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
            // Android still updates the in-process map if the disk write fails.
            boolean success = !failNextCommit;
            failNextCommit = false;
            return success;
        }
    }; }
}
