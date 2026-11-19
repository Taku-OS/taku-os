package org.taku.plugins.host;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class AndroidServer {
    private static boolean isCallerAuthorized(int uid) {
        if (uid == 0 || uid == 1000 || uid == 2000) return true;
        if (uid < 10000) return false;
        Process proc = null;
        try {
            String req = Json.write(Map.of("uid", uid, "scope", "taku.root"));
            proc = new ProcessBuilder(
                    "/system/bin/content",
                    "call",
                    "--user", "0",
                    "--uri", "content://org.agentos.assistant.permissions",
                    "--method", "check",
                    "--arg", req)
                    .redirectErrorStream(true)
                    .start();
            byte[] bytes;
            try (InputStream in = proc.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > 65536) return false;
                }
                bytes = out.toByteArray();
            }
            if (!proc.waitFor(5, TimeUnit.SECONDS) || proc.exitValue() != 0) return false;
            String output = new String(bytes, StandardCharsets.UTF_8);
            int start = output.indexOf("result="), end = output.lastIndexOf("}]");
            if (start < 0 || end <= start) return false;
            Object parsed = Json.parse(output.substring(start + 7, end).trim());
            if (!(parsed instanceof Map)) return false;
            Map<?, ?> map = (Map<?, ?>) parsed;
            return Boolean.TRUE.equals(map.get("ok")) && Boolean.TRUE.equals(map.get("allowed"));
        } catch (Exception failClosed) {
            return false;
        } finally {
            if (proc != null) proc.destroyForcibly();
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
