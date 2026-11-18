package org.taku.plugins.host;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;

final class Manifest {
    final Map<String,Object> data;
    final String id, version, dex, className;
    final boolean provider;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "id", "name", "description", "author", "category", "license", "version", "type", "minHostVersion", "targets", "capabilities", "permissions", "ui", "settings", "hooks", "runtime");
    private Manifest(Map<String,Object> data, String id, String version, String dex, String className) {
        this.data = data; this.id = id; this.version = version; this.dex = dex; this.className = className; this.provider = dex == null;
    }
    @SuppressWarnings("unchecked")
    static Manifest read(Path root) throws PluginException {
        try {
            Path path = root.resolve("manifest.json");
            if (Files.size(path) > 1024 * 1024) throw new IllegalArgumentException("manifest too large");
            Object parsed = Json.parse(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("manifest must be object");
            Map<String,Object> data = (Map<String,Object>)parsed;
            Set<String> expected = new HashSet<>(FIELDS);
            expected.removeAll(Set.of("type", "description", "author", "category", "license"));
            for (String optional : List.of("type", "description", "author", "category", "license")) if (data.containsKey(optional)) expected.add(optional);
            if (data.containsKey("roles")) expected.add("roles");
            if (data.containsKey("checks")) expected.add("checks");
            boolean provider = "tool-provider".equals(data.get("type")) || data.get("roles") instanceof List && (((List<?>)data.get("roles")).contains("tool-provider") || ((List<?>)data.get("roles")).contains("check-provider"));
            if (provider) { expected.addAll(List.of("mcp", "skills", "healthcheck")); if (data.containsKey("runtimePack")) expected.add("runtimePack"); } else expected.add("entrypoint");
            if (!data.keySet().equals(expected)) throw new IllegalArgumentException("required or unknown manifest fields");
            if (!(data.get("schemaVersion") instanceof Number) || ((Number)data.get("schemaVersion")).doubleValue() != 1) throw new IllegalArgumentException("schemaVersion must be 1");
            String id = field(data, "id", "[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+");
            field(data, "name", ".*\\S.*");
            String version = field(data, "version", "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
            field(data, "minHostVersion", "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
            if (data.containsKey("type")) field(data, "type", "hook|system-app|root-daemon|agent|tool-provider");
            else if (!(data.get("roles") instanceof List) || ((List<?>)data.get("roles")).isEmpty()) throw new IllegalArgumentException("type or roles required");
            if (provider && data.containsKey("type") && !"tool-provider".equals(data.get("type"))) throw new IllegalArgumentException("provider role conflicts with legacy type");
            field(data, "runtime", "host|target-process|root-daemon|agent");
            if (data.containsKey("roles")) {
                if (!(data.get("roles") instanceof List)) throw new IllegalArgumentException("roles must be an array");
                Set<Object> roles = new HashSet<>();
                for (Object role : (List<?>)data.get("roles")) if (!(role instanceof String) || !Set.of("tool-provider", "check-provider", "hook-provider", "root-service", "ui-provider").contains(role) || !roles.add(role)) throw new IllegalArgumentException("invalid roles");
            }
            if (data.containsKey("checks")) {
                if (!(data.get("roles") instanceof List) || !((List<?>)data.get("roles")).contains("check-provider") || !(data.get("checks") instanceof List)) throw new IllegalArgumentException("checks require check-provider role");
                Set<String> ids = new HashSet<>();
                for (Object item : (List<?>)data.get("checks")) {
                    if (!(item instanceof Map)) throw new IllegalArgumentException("invalid check");
                    Map<String,Object> check = (Map<String,Object>)item;
                    if (!check.keySet().equals(Set.of("id", "title", "description", "kinds", "targets"))) throw new IllegalArgumentException("invalid check fields");
                    String checkId = field(check, "id", ".*\\S.*");
                    if (!ids.add(checkId)) throw new IllegalArgumentException("duplicate check id");
                    field(check, "title", ".*\\S.*"); field(check, "description", ".*");
                    for (String key : List.of("kinds", "targets")) if (!(check.get(key) instanceof List) || ((List<?>)check.get(key)).stream().anyMatch(value -> !(value instanceof String) || ((String)value).trim().isEmpty()) || new HashSet<>((List<?>)check.get(key)).size() != ((List<?>)check.get(key)).size()) throw new IllegalArgumentException("invalid check " + key);
                }
            }
            if (data.get("roles") instanceof List && ((List<?>)data.get("roles")).contains("check-provider") && !data.containsKey("checks")) throw new IllegalArgumentException("check-provider requires checks");
            for (String key : List.of("targets", "capabilities", "permissions", "ui", "settings", "hooks")) {
                if (!(data.get(key) instanceof List)) throw new IllegalArgumentException(key + " must be array");
                Set<String> seen = new HashSet<>();
                for (Object item : (List<?>)data.get(key)) if (!(item instanceof String) || ((String)item).trim().isEmpty() || !seen.add((String)item)) throw new IllegalArgumentException(key + " must contain unique nonempty strings");
            }
            if (provider) {
                if (data.containsKey("runtimePack")) {
                    if (!(data.get("runtimePack") instanceof Map)) throw new IllegalArgumentException("runtimePack must be object");
                    Map<String,Object> pack = (Map<String,Object>) data.get("runtimePack");
                    if (!pack.keySet().equals(Set.of("id", "platform", "root", "version"))) throw new IllegalArgumentException("invalid runtimePack fields");
                    for (String key : pack.keySet()) field(pack, key, ".*\\S.*");
                }
                if (!(data.get("mcp") instanceof Map)) throw new IllegalArgumentException("mcp must be object");
                Map<String,Object> mcp = (Map<String,Object>)data.get("mcp");
                if (!mcp.keySet().equals(Set.of("command", "args", "env"))) throw new IllegalArgumentException("invalid mcp fields");
                field(mcp, "command", ".*\\S.*");
                if (!(mcp.get("args") instanceof List) || ((List<?>)mcp.get("args")).stream().anyMatch(item -> !(item instanceof String))) throw new IllegalArgumentException("mcp args must be strings");
                if (!(mcp.get("env") instanceof Map) || ((Map<?,?>)mcp.get("env")).values().stream().anyMatch(item -> !(item instanceof String))) throw new IllegalArgumentException("mcp env values must be strings");
                if (!(data.get("skills") instanceof List)) throw new IllegalArgumentException("skills must be array");
                Set<String> seen = new HashSet<>();
                for (Object item : (List<?>)data.get("skills")) {
                    if (!(item instanceof String) || !((String)item).matches("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*/SKILL\\.md") || !seen.add((String)item) || !Files.isRegularFile(root.resolve((String)item), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("invalid skill path " + item);
                }
                if (!(data.get("healthcheck") instanceof Map)) throw new IllegalArgumentException("healthcheck must be object");
                Map<?,?> health = (Map<?,?>)data.get("healthcheck"); Object timeout = health.get("timeoutMs");
                if (!health.keySet().equals(Set.of("timeoutMs")) || !(timeout instanceof Number) || ((Number)timeout).doubleValue() != ((Number)timeout).intValue() || ((Number)timeout).intValue() < 100 || ((Number)timeout).intValue() > 30000) throw new IllegalArgumentException("invalid timeoutMs");
                return new Manifest(data, id, version, null, null);
            }
            if (!(data.get("entrypoint") instanceof Map)) throw new IllegalArgumentException("entrypoint must be object");
            Map<String,Object> entry = (Map<String,Object>)data.get("entrypoint");
            if (!entry.keySet().equals(Set.of("dex", "class"))) throw new IllegalArgumentException("invalid entrypoint fields");
            String dex = field(entry, "dex", "[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.(dex|jar|apk)");
            String className = field(entry, "class", "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
            if (!Files.isRegularFile(root.resolve(dex), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("entrypoint file missing: " + dex);
            return new Manifest(data, id, version, dex, className);
        } catch (IOException | IllegalArgumentException error) { throw new PluginException("INVALID_MANIFEST", error.getMessage(), error); }
    }
    private static String field(Map<String,Object> data, String key, String pattern) {
        Object value = data.get(key);
        if (!(value instanceof String) || !((String)value).matches(pattern)) throw new IllegalArgumentException("invalid " + key);
        return (String)value;
    }
}
