package org.taku.plugins.host;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class AndroidServer {
    private static final int REQUEST_TIMEOUT_MS = 5_000;

    static boolean isCallerAuthorized(int uid, java.util.function.IntPredicate permissionProvider) {
        if (uid == 0 || uid == 1000 || uid == 2000) return true;
        return uid >= 10000 && permissionProvider.test(uid);
    }

    private static boolean hasRootPermission(int uid) {
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

    private static String readRequest(LocalSocket socket) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_TIMEOUT_MS);
        InputStream timed = new FilterInputStream(socket.getInputStream()) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("God Mode request timed out");
                socket.setSoTimeout((int)Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                return in.read(buffer, offset, length);
            }
        };
        return BoundedLine.read(new BufferedInputStream(timed), 1024 * 1024);
    }

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "/data/local/agentos/godmode";
        String socketName = args.length > 1 ? args[1] : "taku-godmode";
        try (PluginManager manager = new PluginManager(Paths.get(root), new AndroidPluginLoader()); LocalServerSocket server = new LocalServerSocket(socketName)) {
            while (true) {
                try (LocalSocket socket = server.accept()) {
                    int peerUid = socket.getPeerCredentials().getUid();

                    socket.setSoTimeout(REQUEST_TIMEOUT_MS);
                    BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    if (!isCallerAuthorized(peerUid, AndroidServer::hasRootPermission)) {
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
                        String line = readRequest(socket);
                        if (line == null) throw new PluginException("USAGE", "invalid request");
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
