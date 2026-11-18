package org.taku.plugins.host;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class Cli {
    public static void main(String[] args) {
        try {
            if (args.length == 2 && args[0].equals("godmode") && args[1].equals("mcp")) {
                String socket = System.getenv().getOrDefault("TAKU_GODMODE_SOCKET", "taku-godmode");
                McpBridge.main(new String[] {socket});
                return;
            }
            if (args.length < 2 || args.length > 5 || !args[0].equals("godmode") || (args.length > 3 && !(args.length == 5 && (args[1].equals("call-tool") || args[1].equals("run-check"))) && !(args.length == 4 && args[1].equals("tool")))) throw new PluginException("USAGE", "taku-plugin godmode list|tools [TOOLSET]|tool ID NAME|checks [ID]|run-check ID NAME JSON|evidence [ID]|show ID|install PATH|install-url JSON|remove ID|enable ID|disable ID|reload ID|rollback ID|call-tool ID NAME JSON");
            String socket = System.getenv("TAKU_GODMODE_SOCKET");
            if (socket == null || socket.isEmpty()) throw new PluginException("USAGE", "TAKU_GODMODE_SOCKET is required");
            Map<String,Object> request = new LinkedHashMap<>();
            request.put("operation", args[1]);
            if (args.length == 3 && (args[1].equals("local") || args[1].equals("install-url"))) request.put("arguments", Json.parse(args[2]));
            else if (args.length == 3) request.put("argument", args[1].equals("install") ? Path.of(args[2]).toAbsolutePath().normalize().toString() : args[2]);
            if (args.length == 4) { request.put("argument", args[2]); request.put("name", args[3]); }
            if (args.length == 5) { request.put("argument", args[2]); request.put("name", args[3]); request.put("arguments", Json.parse(args[4])); }
            try (SocketChannel connection = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                BufferedWriter output = new BufferedWriter(Channels.newWriter(connection, StandardCharsets.UTF_8));
                BufferedReader input = new BufferedReader(Channels.newReader(connection, StandardCharsets.UTF_8));
                output.write(Json.write(request)); output.newLine(); output.flush();
                String response = input.readLine();
                if (response == null) throw new IOException("service closed connection");
                System.out.println(response);
                Object parsed = Json.parse(response);
                if (!(parsed instanceof Map) || !Boolean.TRUE.equals(((Map<?,?>)parsed).get("ok"))) System.exit(1);
            }
        } catch (Exception error) {
            String code = error instanceof PluginException ? ((PluginException)error).code : "SERVICE_UNAVAILABLE";
            System.out.println(Json.write(Map.of("ok", false, "error", Map.of("code", code, "message", String.valueOf(error.getMessage())))));
            System.exit(1);
        }
    }
}
