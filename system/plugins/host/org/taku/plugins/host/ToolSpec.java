package org.taku.plugins.host;

import java.io.IOException;
import java.util.*;

final class ToolSpec {
    private final Map<String,Object> detail;

    ToolSpec(Map<?,?> raw, String pluginId) throws IOException {
        Object name = raw.get("name");
        if (!(name instanceof String) || ((String)name).isEmpty()) throw new IOException("invalid MCP tool name");
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id", name);
        value.put("name", name);
        value.put("title", string(raw, "title", (String)name));
        value.put("description", string(raw, "description", ""));
        value.put("toolset", string(raw, "toolset", pluginId));
        Object schema = raw.get("inputSchema");
        if (!(schema instanceof Map)) throw new IOException("invalid MCP inputSchema");
        value.put("inputSchema", schema);
        for (String field : List.of("effects", "capabilities", "requirements")) {
            Object item = raw.containsKey(field) ? raw.get(field) : List.of();
            if (!(item instanceof List)) throw new IOException("invalid MCP " + field);
            value.put(field, item);
        }
        Map<String,Object> extensions = new TreeMap<>();
        Set<String> known = Set.of("name", "title", "description", "toolset", "inputSchema", "effects", "capabilities", "requirements");
        for (Map.Entry<?,?> entry : raw.entrySet()) if (entry.getKey() instanceof String && !known.contains(entry.getKey())) extensions.put((String)entry.getKey(), entry.getValue());
        if (!extensions.isEmpty()) value.put("extensions", extensions);
        detail = Collections.unmodifiableMap(value);
    }
    private static String string(Map<?,?> raw, String key, String fallback) throws IOException {
        Object value = raw.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String) || (key.equals("toolset") && ((String)value).isEmpty())) throw new IOException("invalid MCP " + key);
        return (String)value;
    }
    String id() { return (String)detail.get("id"); }
    String toolset() { return (String)detail.get("toolset"); }
    Map<String,Object> detail() { return new LinkedHashMap<>(detail); }
    Map<String,Object> summary() {
        Map<String,Object> result = detail();
        result.remove("inputSchema");
        result.remove("extensions");
        return result;
    }
}
