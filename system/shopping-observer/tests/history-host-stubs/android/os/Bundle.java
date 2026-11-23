// SPDX-License-Identifier: Apache-2.0
package android.os;

/** Typed inputs used by the real Preferences adapter, independent of Android framework stubs. */
public final class Bundle {
    private final java.util.Map<String,Object> values = new java.util.HashMap<>();
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public void putString(String key, String value) { values.put(key, value); }
    public boolean getBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public Object get(String key) { return values.get(key); }
    public java.util.Set<String> keySet() { return values.keySet(); }
    public boolean isEmpty() { return values.isEmpty(); }
}
