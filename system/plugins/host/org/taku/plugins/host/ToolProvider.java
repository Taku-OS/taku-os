package org.taku.plugins.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.taku.plugins.*;

final class ToolProvider implements TakuPlugin {
    private final Manifest manifest;
    private final RuntimeContext context;
    private final Path directory;
    private Process process;
    private BufferedReader input;
    private BufferedWriter output;
    private ExecutorService reader;
    private List<ToolSpec> tools = new ArrayList<>();
    private long nextId;
    ToolProvider(Manifest manifest, RuntimeContext context, Path directory) { this.manifest = manifest; this.context = context; this.directory = directory; }
    public void onLoad(PluginContext ignored) { }
    @SuppressWarnings("unchecked")
    public void onStart() throws Exception {
        Map<String,Object> config = (Map<String,Object>)manifest.data.get("mcp");
        List<String> command = new ArrayList<>(); command.add((String)config.get("command"));
        for (Object arg : (List<?>)config.get("args")) command.add((String)arg);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        for (Map.Entry<String,Object> entry : ((Map<String,Object>)config.get("env")).entrySet()) builder.environment().put(entry.getKey(), (String)entry.getValue());
        process = builder.start();
        input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "taku-mcp-reader"); thread.setDaemon(true); return thread; });
        Thread stderr = new Thread(() -> { try (InputStream stream = process.getErrorStream()) { byte[] buffer = new byte[1024]; while (stream.read(buffer) != -1) { } } catch (IOException ignored) { } }, "taku-mcp-stderr");
        stderr.setDaemon(true); stderr.start();
        try {
            rpc("initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "taku-godmode", "version", "1.0.0")));
            send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            Object result = rpc("tools/list", Map.of());
            if (!(result instanceof Map) || !(((Map<?,?>)result).get("tools") instanceof List)) throw new IOException("tools/list response missing tools");
            List<ToolSpec> normalized = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Object item : (List<?>)((Map<?,?>)result).get("tools")) {
                if (!(item instanceof Map)) throw new IOException("invalid MCP tool");
                ToolSpec spec = new ToolSpec((Map<?,?>)item, manifest.id);
                if (!names.add(spec.id())) throw new IOException("duplicate MCP tool: " + spec.id());
                normalized.add(spec);
            }
            tools = normalized;
            context.provider = this;
        } catch (Exception error) { onStop(); throw error; }
    }
    private void send(Object request) throws IOException { output.write(Json.write(request)); output.newLine(); output.flush(); }
    private synchronized Object rpc(String method, Map<String,Object> params) throws Exception {
        long id = ++nextId;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        int timeout = ((Number)((Map<?,?>)manifest.data.get("healthcheck")).get("timeoutMs")).intValue();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("MCP timeout: " + method);
            String line = reader.submit(() -> input.readLine()).get(remaining, TimeUnit.NANOSECONDS);
            if (line == null) throw new IOException("MCP provider closed stdout");
            if (line.length() > 16_000_000) throw new IOException("MCP response too large");
            Object parsed = Json.parse(line);
            if (!(parsed instanceof Map)) throw new IOException("invalid MCP response");
            Map<?,?> reply = (Map<?,?>)parsed;
            if (!Objects.equals(reply.get("id"), id)) continue;
            if (reply.containsKey("error")) throw new IOException("MCP error: " + Json.write(reply.get("error")));
            if (!reply.containsKey("result")) throw new IOException("MCP result missing");
            return reply.get("result");
        }
    }
    Object call(String name, Map<String,Object> args) throws Exception {
        if (!alive()) throw new PluginException("PROVIDER_STOPPED", manifest.id);
        boolean found = false;
        for (ToolSpec tool : tools) if (name.equals(tool.id())) found = true;
        if (!found) throw new PluginException("NOT_FOUND", "tool " + name);
        return rpc("tools/call", Map.of("name", name, "arguments", args));
    }
    boolean alive() { return process != null && process.isAlive(); }
    List<Object> tools(String toolset) {
        List<Object> result = new ArrayList<>();
        if (alive()) for (ToolSpec tool : tools) if (toolset == null || toolset.equals(tool.toolset())) result.add(tool.summary());
        return result;
    }
    Map<String,Object> tool(String name) throws PluginException {
        if (!alive()) throw new PluginException("PROVIDER_STOPPED", manifest.id);
        for (ToolSpec tool : tools) if (name.equals(tool.id())) return tool.detail();
        throw new PluginException("NOT_FOUND", "tool " + name);
    }
    @SuppressWarnings("unchecked")
    List<Object> checks() { return (List<Object>)manifest.data.getOrDefault("checks", List.of()); }
    Object runCheck(String name, Map<String,Object> context) throws Exception {
        boolean found = false;
        for (Object item : checks()) if (name.equals(((Map<?,?>)item).get("id"))) found = true;
        if (!found) throw new PluginException("NOT_FOUND", "check " + name);
        Object result;
        try { result = call(name, context); }
        catch (TimeoutException error) { onStop(); throw new PluginException("CHECK_TIMEOUT", "check timed out: " + name, error); }
        catch (PluginException error) { throw error; }
        catch (Exception error) { throw new PluginException("CHECK_FAILED", "check failed: " + error.getMessage(), error); }
        if (result instanceof Map && Boolean.TRUE.equals(((Map<?,?>)result).get("isError"))) throw new PluginException("CHECK_FAILED", "provider reported check error");
        if (result instanceof Map && ((Map<?,?>)result).containsKey("structuredContent")) result = ((Map<?,?>)result).get("structuredContent");
        if (!(result instanceof Map)) throw new PluginException("CHECK_FAILED", "check result must be an object");
        Map<?,?> value = (Map<?,?>)result;
        Object similarity = value.get("similarity");
        if (!(value.get("passed") instanceof Boolean) || !(similarity instanceof Number) || !Double.isFinite(((Number)similarity).doubleValue()) || ((Number)similarity).doubleValue() < 0 || ((Number)similarity).doubleValue() > 100 || !(value.get("summary") instanceof String) || !(value.get("unknowns") instanceof List) || ((List<?>)value.get("unknowns")).stream().anyMatch(item -> !(item instanceof String))) throw new PluginException("CHECK_FAILED", "invalid check result");
        return result;
    }
    @SuppressWarnings("unchecked") List<Object> skills() { return alive() ? new ArrayList<>((List<Object>)manifest.data.get("skills")) : List.of(); }
    public void onStop() {
        context.provider = null; tools.clear();
        if (process != null) { process.destroy(); try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); process.destroyForcibly(); } }
        if (reader != null) reader.shutdownNow();
        try { if (input != null) input.close(); } catch (IOException ignored) { }
        try { if (output != null) output.close(); } catch (IOException ignored) { }
    }
    public void onReload() { }
    public void onUninstall() { }
}
