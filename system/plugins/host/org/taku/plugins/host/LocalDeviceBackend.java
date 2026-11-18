package org.taku.plugins.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class LocalDeviceBackend {
    interface Runner { Map<String,Object> run(List<String> command) throws Exception; }
    private final Runner runner;
    public LocalDeviceBackend() { this(LocalDeviceBackend::run); }
    LocalDeviceBackend(Runner runner) { this.runner = runner; }
    private static String string(Map<?,?> request, String name) throws PluginException {
        Object value = request.get(name);
        if (!(value instanceof String) || ((String)value).isEmpty()) throw new PluginException("USAGE", name + " required");
        return (String)value;
    }
    public Object execute(Map<?,?> request) throws Exception {
        Object target = request.containsKey("target") ? request.get("target") : "local";
        if (!"local".equals(target)) throw new PluginException("REMOTE_UNAVAILABLE", "this backend only handles local; use a separate remote transport");
        String operation = string(request, "action");
        List<String> command = new ArrayList<>();
        switch (operation) {
            case "read-file":
                Path path = Path.of(string(request, "path"));
                if (!Files.isRegularFile(path) || Files.size(path) > 1048576) throw new PluginException("IO_ERROR", "not a regular file or exceeds 1 MiB");
                return Map.of("path", path.toString(), "base64", Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            case "packages": command.addAll(List.of("/system/bin/pm", "list", "packages")); break;
            case "apk-paths": command.addAll(List.of("/system/bin/pm", "path", string(request, "package"))); break;
            case "shell": command.addAll(List.of("/system/bin/sh", "-c", string(request, "command"))); break;
            case "processes": command.addAll(List.of("/system/bin/ps", "-A")); break;
            case "logcat": command.addAll(List.of("/system/bin/logcat", "-d", "-t", "200")); break;
            case "getprop": command.addAll(List.of("/system/bin/getprop", string(request, "key"))); break;
            case "settings-get": command.addAll(List.of("/system/bin/settings", "get", string(request, "namespace"), string(request, "key"))); break;
            case "settings-put": command.addAll(List.of("/system/bin/settings", "put", string(request, "namespace"), string(request, "key"), string(request, "value"))); break;
            case "activity-start": command.addAll(List.of("/system/bin/am", "start", "-n", string(request, "component"))); break;
            case "activity-stop": command.addAll(List.of("/system/bin/am", "force-stop", string(request, "package"))); break;
            case "display": command.addAll(List.of("/system/bin/dumpsys", "display")); break;
            case "window": command.addAll(List.of("/system/bin/dumpsys", "window")); break;
            case "device-info": command.add("/system/bin/getprop"); break;
            case "root": command.addAll(List.of("/system/bin/id", "-u")); break;
            default: throw new PluginException("USAGE", "unknown local device action: " + operation);
        }
        Map<String,Object> result = runner.run(command);
        if (operation.equals("apk-paths") && Integer.valueOf(0).equals(result.get("exitCode"))) {
            List<String> paths = new ArrayList<>();
            for (String line : ((String)result.get("output")).split("\\R")) if (line.startsWith("package:/")) paths.add(line.substring(8));
            return Map.of("paths", paths, "exitCode", 0);
        }
        return result;
    }
    private static Map<String,Object> run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> output = reader.submit(() -> process.getInputStream().readNBytes(1048577));
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("local operation timed out");
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            if (bytes.length > 1048576) throw new IOException("local output exceeds 1 MiB");
            return Map.of("exitCode", process.exitValue(), "output", new String(bytes, StandardCharsets.UTF_8));
        } finally { process.destroyForcibly(); reader.shutdownNow(); }
    }
}
