package org.taku.plugins.host;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Aggregates all enabled God Mode tool providers behind one MCP stdio server.
 *
 * The bridge intentionally speaks to the existing root daemon instead of opening
 * its own PluginManager. This keeps one authoritative plugin registry and lets
 * Agent processes attach without receiving root access themselves.
 */
public final class McpBridge {
    private static final String DEFAULT_SOCKET = "taku-godmode";
    private static final int MAX_LINE = 16 * 1024 * 1024;

    private McpBridge() { }

    public static void main(String[] args) throws Exception {
        String socket = args.length > 0 && !args[0].isBlank()
                ? args[0]
                : System.getenv().getOrDefault("TAKU_GODMODE_SOCKET", DEFAULT_SOCKET);
        serve(socket, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8)));
    }

    static void serve(String socket, BufferedReader input, BufferedWriter output) throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) continue;
            if (line.length() > MAX_LINE) {
                write(output, error(null, -32600, "request too large"));
                continue;
            }
            Object parsed;
            try {
                parsed = Json.parse(line);
            } catch (Exception error) {
                write(output, error(null, -32700, "invalid JSON"));
                continue;
            }
            if (!(parsed instanceof Map)) {
                write(output, error(null, -32600, "request must be an object"));
                continue;
            }
            Map<?,?> request = (Map<?,?>)parsed;
            Object id = request.get("id");
            boolean notification = !request.containsKey("id");
            Object methodValue = request.get("method");
            if (!(methodValue instanceof String)) {
                if (!notification) write(output, error(id, -32600, "method required"));
                continue;
            }
            String method = (String)methodValue;
            Map<?,?> params = request.get("params") instanceof Map
                    ? (Map<?,?>)(request.get("params"))
                    : Map.of();
            try {
                Object result = dispatch(socket, method, params);
                if (!notification && result != null) write(output, response(id, result));
            } catch (RpcException error) {
                if (!notification) write(output, error(id, error.code, error.getMessage()));
            } catch (Exception error) {
                if (!notification) write(output, error(id, -32603, describe(error)));
            }
        }
    }

    private static Object dispatch(String socket, String method, Map<?,?> params) throws Exception {
        switch (method) {
            case "initialize": {
                Object requested = params.get("protocolVersion");
                String protocol = requested instanceof String && !((String)requested).isBlank()
                        ? (String)requested : "2024-11-05";
                Map<String,Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", protocol);
                result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
                result.put("serverInfo", Map.of("name", "taku-godmode", "version", "1.0.0"));
                return result;
            }
            case "ping":
            case "logging/setLevel":
                return Map.of();
            case "tools/list":
                return Map.of("tools", listTools(socket));
            case "tools/call":
                return callTool(socket, params);
            case "prompts/list":
                return Map.of("prompts", List.of());
            case "resources/list":
                return Map.of("resources", List.of());
            case "resources/templates/list":
                return Map.of("resourceTemplates", List.of());
            case "roots/list":
                return Map.of("roots", List.of());
            default:
                if (method.startsWith("notifications/")) return null;
                throw new RpcException(-32601, "method not found: " + method);
        }
    }

    private static List<Object> listTools(String socket) throws Exception {
        List<Object> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (CatalogEntry entry : catalog(socket)) {
            Map<String,Object> tool = new LinkedHashMap<>();
            tool.put("name", entry.exposedName);
            Object title = entry.detail.get("title");
            if (title instanceof String && !((String)title).isBlank()) tool.put("title", title);
            Object description = entry.detail.get("description");
            tool.put("description", description instanceof String ? description : "");
            Object schema = entry.detail.get("inputSchema");
            tool.put("inputSchema", schema instanceof Map ? schema : Map.of("type", "object"));
            Map<String,Object> meta = new LinkedHashMap<>();
            meta.put("pluginId", entry.pluginId);
            meta.put("tool", entry.toolName);
            for (String key : List.of("effects", "capabilities", "requirements", "toolset")) {
                if (entry.detail.containsKey(key)) meta.put(key, entry.detail.get(key));
            }
            tool.put("_meta", Map.of("taku", meta));
            result.add(tool);
        }
        return result;
    }

    private static Object callTool(String socket, Map<?,?> params) throws Exception {
        Object nameValue = params.get("name");
        if (!(nameValue instanceof String) || ((String)nameValue).isBlank()) {
            throw new RpcException(-32602, "tool name required");
        }
        Map<?,?> arguments = params.get("arguments") instanceof Map
                ? (Map<?,?>)(params.get("arguments"))
                : Map.of();
        CatalogEntry selected = null;
        for (CatalogEntry entry : catalog(socket)) {
            if (entry.exposedName.equals(nameValue)) { selected = entry; break; }
        }
        if (selected == null) throw new RpcException(-32602, "unknown tool: " + nameValue);
        Map<String,Object> request = new LinkedHashMap<>();
        request.put("operation", "call-tool");
        request.put("argument", selected.pluginId);
        request.put("name", selected.toolName);
        request.put("arguments", arguments);
        Map<?,?> reply = daemon(socket, request);
        if (!Boolean.TRUE.equals(reply.get("ok"))) {
            String message = "plugin call failed";
            Object value = reply.get("error");
            if (value instanceof Map) {
                Object raw = ((Map<?,?>)value).get("message");
                if (raw instanceof String && !((String)raw).isBlank()) message = (String)raw;
            }
            return Map.of("content", List.of(Map.of("type", "text", "text", message)), "isError", true);
        }
        return reply.get("result");
    }

    private static List<CatalogEntry> catalog(String socket) throws Exception {
        Map<?,?> reply = daemon(socket, Map.of("operation", "catalog"));
        if (!Boolean.TRUE.equals(reply.get("ok"))) {
            throw new RpcException(-32603, "God Mode catalog unavailable");
        }
        Object rawGroups = reply.get("result");
        if (!(rawGroups instanceof List)) throw new RpcException(-32603, "invalid God Mode catalog");
        List<CatalogEntry> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Object rawGroup : (List<?>)rawGroups) {
            if (!(rawGroup instanceof Map)) continue;
            Map<?,?> group = (Map<?,?>)rawGroup;
            Object pluginIdValue = group.get("pluginId");
            Object toolsValue = group.get("tools");
            if (!(pluginIdValue instanceof String) || !(toolsValue instanceof List)) continue;
            String pluginId = (String)pluginIdValue;
            for (Object rawTool : (List<?>)toolsValue) {
                if (!(rawTool instanceof Map)) continue;
                Map<?,?> detail = (Map<?,?>)rawTool;
                Object toolNameValue = detail.get("id");
                if (!(toolNameValue instanceof String)) toolNameValue = detail.get("name");
                if (!(toolNameValue instanceof String)) continue;
                String toolName = (String)toolNameValue;
                String base = slug(pluginId) + "__" + slug(toolName);
                String exposed = base;
                int suffix = 2;
                while (!used.add(exposed)) exposed = base + "__" + suffix++;
                result.add(new CatalogEntry(exposed, pluginId, toolName, detail));
            }
        }
        return result;
    }

    private static Map<?,?> daemon(String socket, Map<String,Object> request) throws Exception {
        try (Connection connection = Connection.open(socket)) {
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(connection.output(), StandardCharsets.UTF_8));
            BufferedReader input = new BufferedReader(new InputStreamReader(connection.input(), StandardCharsets.UTF_8));
            output.write(Json.write(request));
            output.newLine();
            output.flush();
            String line = input.readLine();
            if (line == null) throw new IOException("God Mode closed the connection");
            if (line.length() > MAX_LINE) throw new IOException("God Mode response too large");
            Object parsed = Json.parse(line);
            if (!(parsed instanceof Map)) throw new IOException("God Mode returned invalid JSON");
            return (Map<?,?>)parsed;
        }
    }

    private static String slug(String value) {
        String result = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return result.isBlank() ? "tool" : result;
    }

    private static Map<String,Object> response(Object id, Object result) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("jsonrpc", "2.0");
        value.put("id", id);
        value.put("result", result);
        return value;
    }

    private static Map<String,Object> error(Object id, int code, String message) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("jsonrpc", "2.0");
        value.put("id", id);
        value.put("error", Map.of("code", code, "message", message == null ? "error" : message));
        return value;
    }

    private static void write(BufferedWriter output, Object value) throws IOException {
        output.write(Json.write(value));
        output.newLine();
        output.flush();
    }

    private static final class CatalogEntry {
        final String exposedName;
        final String pluginId;
        final String toolName;
        final Map<?,?> detail;
        CatalogEntry(String exposedName, String pluginId, String toolName, Map<?,?> detail) {
            this.exposedName = exposedName;
            this.pluginId = pluginId;
            this.toolName = toolName;
            this.detail = detail;
        }
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null ? root.getClass().getName() : root.getClass().getSimpleName() + ": " + message;
    }

    private static final class RpcException extends Exception {
        final int code;
        RpcException(int code, String message) { super(message); this.code = code; }
    }

    private interface Connection extends Closeable {
        InputStream input();
        OutputStream output();
        static Connection open(String socket) throws Exception {
            if (socket.startsWith("/")) return openFileSystemSocket(socket);
            return openAndroidAbstract(socket);
        }
    }

    /**
     * Desktop JVMs expose filesystem Unix sockets through java.net; the Android
     * platform library has no UnixDomainSocketAddress, so the lookup stays
     * reflective and only runs on hosts that provide the class.
     */
    private static Connection openFileSystemSocket(String socket) throws Exception {
        Class<?> addressClass = Class.forName("java.net.UnixDomainSocketAddress");
        Object address = addressClass.getMethod("of", String.class).invoke(null, socket);
        Class<?> familyClass = Class.forName("java.net.StandardProtocolFamily");
        Object unix = null;
        for (Object constant : familyClass.getEnumConstants()) if ("UNIX".equals(constant.toString())) unix = constant;
        if (unix == null) throw new IllegalStateException("UNIX protocol family unavailable");
        Method open = SocketChannel.class.getMethod("open", Class.forName("java.net.ProtocolFamily"));
        final SocketChannel channel = (SocketChannel) open.invoke(null, unix);
        channel.connect((java.net.SocketAddress) address);
        return new Connection() {
            public InputStream input() { return Channels.newInputStream(channel); }
            public OutputStream output() { return Channels.newOutputStream(channel); }
            public void close() throws IOException { channel.close(); }
        };
    }

    private static Connection openAndroidAbstract(String socket) throws Exception {
        Class<?> addressClass = Class.forName("android.net.LocalSocketAddress");
        Class<?> namespaceClass = Class.forName("android.net.LocalSocketAddress$Namespace");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object namespace = Enum.valueOf((Class<? extends Enum>)namespaceClass.asSubclass(Enum.class), "ABSTRACT");
        Object address = addressClass.getConstructor(String.class, namespaceClass).newInstance(socket, namespace);
        Class<?> localSocketClass = Class.forName("android.net.LocalSocket");
        final Object localSocket = localSocketClass.getConstructor().newInstance();
        Method connect = localSocketClass.getMethod("connect", addressClass);
        Method setSoTimeout = localSocketClass.getMethod("setSoTimeout", int.class);
        Method getInputStream = localSocketClass.getMethod("getInputStream");
        Method getOutputStream = localSocketClass.getMethod("getOutputStream");
        Method close = localSocketClass.getMethod("close");
        connect.invoke(localSocket, address);
        setSoTimeout.invoke(localSocket, 45000);
        final InputStream input = (InputStream)getInputStream.invoke(localSocket);
        final OutputStream output = (OutputStream)getOutputStream.invoke(localSocket);
        return new Connection() {
            public InputStream input() { return input; }
            public OutputStream output() { return output; }
            public void close() throws IOException {
                try { close.invoke(localSocket); }
                catch (Exception error) { throw new IOException("cannot close local socket", error); }
            }
        };
    }
}
