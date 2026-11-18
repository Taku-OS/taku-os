package org.taku.plugins.host;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;

public final class AndroidServer {
    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "/data/local/agentos/godmode";
        String socketName = args.length > 1 ? args[1] : "taku-godmode";
        try (PluginManager manager = new PluginManager(Paths.get(root), new AndroidPluginLoader()); LocalServerSocket server = new LocalServerSocket(socketName)) {
            while (true) {
                try (LocalSocket socket = server.accept()) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
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
