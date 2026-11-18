package org.taku.plugins.host;

import java.util.*;

public final class LocalDeviceTest {
    public static void main(String[] args) throws Exception {
        List<List<String>> calls = new ArrayList<>();
        LocalDeviceBackend backend = new LocalDeviceBackend(command -> {
            calls.add(command);
            return Map.of("exitCode", 0, "output", command.contains("path") ? "package:/data/app/a/base.apk\npackage:/data/app/a/split.apk\n" : "ok");
        });
        Map<?,?> paths = (Map<?,?>)backend.execute(Map.of("target", "local", "action", "apk-paths", "package", "org.example"));
        if (!paths.get("paths").equals(List.of("/data/app/a/base.apk", "/data/app/a/split.apk"))) throw new AssertionError(paths);
        for (String action : List.of("packages", "processes", "logcat", "display", "window", "device-info", "root")) backend.execute(Map.of("action", action));
        backend.execute(Map.of("action", "settings-put", "namespace", "system", "key", "example", "value", "1"));
        backend.execute(Map.of("action", "shell", "command", "echo local"));
        for (List<String> call : calls) if (call.get(0).contains("adb")) throw new AssertionError(call);
        try { backend.execute(Map.of("target", "remote", "action", "packages")); throw new AssertionError("remote accepted"); }
        catch (PluginException expected) { if (!expected.code.equals("REMOTE_UNAVAILABLE")) throw expected; }
        System.out.println("PASS: local backend routes without adb, preserves base/split paths and rejects remote");
    }
}
