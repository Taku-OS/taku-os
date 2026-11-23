// SPDX-License-Identifier: Apache-2.0
package org.json;

import java.util.HashMap;
import java.util.Map;

/** Host data builder only, not a JSON parser. It never replaces Android/native-bridge validation. */
public final class JSONObject {
    private final Map<String, Object> fields = new HashMap<>();
    public JSONObject() { }
    public JSONObject(String ignored) { throw new UnsupportedOperationException("host fixture cannot parse provider responses"); }
    public JSONObject put(String key, Object value) { fields.put(key, value); return this; }
    public Object get(String key) { if (!fields.containsKey(key)) throw new IllegalArgumentException("missing fixture field"); return fields.get(key); }
    public String getString(String key) { Object value = get(key); if (!(value instanceof String)) throw new IllegalArgumentException("fixture field is not text"); return (String) value; }
    public boolean optBoolean(String key, boolean fallback) { Object value = fields.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
}
