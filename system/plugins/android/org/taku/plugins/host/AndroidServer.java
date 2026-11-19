package org.taku.plugins.host;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;

public final class AndroidServer {
    private static volatile Context systemContext;

    private static Context getSystemContext() {
        Context cached = systemContext;
        if (cached != null) return cached;
        synchronized (AndroidServer.class) {
            if (systemContext != null) return systemContext;
            try {
                if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
                Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
                systemContext = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            } catch (Exception ignored) {
            }
            return systemContext;
        }
    }

    private static boolean isCallerAuthorized(int uid) {
        if (uid == 0 || uid == 1000 || uid == 2000) return true;
        if (uid < 10000) return false;
        try {
            Context ctx = getSystemContext();
            if (ctx == null) return false;
            String req = Json.write(Map.of("uid", uid, "scope", "taku.root"));
            Bundle out = ctx.getContentResolver().call(
                    Uri.parse("content://org.agentos.assistant.permissions"), "check", req, null);
            if (out == null) return false;
            String raw = out.getString("result");
            if (raw == null || raw.isEmpty()) return false;
            Object parsed = Json.parse(raw);
            if (!(parsed instanceof Map)) return false;
            Map<?, ?> map = (Map<?, ?>) parsed;
            return Boolean.TRUE.equals(map.get("ok")) && Boolean.TRUE.equals(map.get("allowed"));
        } catch (Exception failClosed) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "/data/local/agentos/godmode";
        String socketName = args.length > 1 ? args[1] : "taku-godmode";
        try (PluginManager manager = new PluginManager(Paths.get(root), new AndroidPluginLoader()); LocalServerSocket server = new LocalServerSocket(socketName)) {
            while (true) {
                try (LocalSocket socket = server.accept()) {
                    int peerUid = socket.getPeerCredentials().getUid();
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    if (!isCallerAuthorized(peerUid)) {
                        Map<String, Object> denied = Map.of(
                                "ok", false,
                                "error", Map.of("code", "PERMISSION_DENIED", "message", "Root plugin access denied"));
                        output.write(Json.write(denied));
                        output.newLine();
                        output.flush();
                        continue;
                    }
                    Map<String,Object> response;
                    try {
                        String line = input.readLine();
                        if (line == null || line.length() > 1024 * 1024) throw new PluginException("USAGE", "invalid request");
                        Object request = Json.parse(line);
                        if (!(request instanceof Map)) throw new PluginException("USAGE", "request must be object");
                        if ("shutdown".equals(((Map<?,?>)request).get("operation"))) throw new PluginException("USAGE", "use init to stop service");
                        response = Protocol.execute(manager, (Map<?,?>)request);
                    } catch (Exception error) {
                        String code = error instanceof PluginException ? ((PluginException)error).code : "INTERNAL";
                        response = Map.of("ok", false, "error", Map.of("code", code, "message", String.valueOf(error.getMessage())));
                    }
                    output.write(Json.write(response)); output.newLine(); output.flush();
                } catch (IOException error) { System.err.println("God Mode connection failed: " + error); }
            }
        }
    }
}
