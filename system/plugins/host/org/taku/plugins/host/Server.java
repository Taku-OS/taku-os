package org.taku.plugins.host;

import java.io.*;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Server {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: Server ROOT SOCKET");
        Path socket = Path.of(args[1]).toAbsolutePath();
        try (PluginManager manager = new PluginManager(Path.of(args[0])); ServerSocketChannel server = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            try {
                while (true) {
                    boolean shutdown = false;
                    try (SocketChannel connection = server.accept()) {
                        BufferedReader input = new BufferedReader(Channels.newReader(connection, StandardCharsets.UTF_8));
                        BufferedWriter output = new BufferedWriter(Channels.newWriter(connection, StandardCharsets.UTF_8));
                        Map<String,Object> response;
                        try {
                            String line = input.readLine();
                            if (line == null || line.length() > 1024 * 1024) throw new PluginException("USAGE", "invalid request");
                            Object parsed = Json.parse(line);
                            if (!(parsed instanceof Map)) throw new PluginException("USAGE", "request must be object");
                            Map<?,?> request = (Map<?,?>)parsed;
                            response = Protocol.execute(manager, request);
                            shutdown = "shutdown".equals(request.get("operation"));
                        } catch (Exception error) {
                            String code = error instanceof PluginException ? ((PluginException)error).code : "INTERNAL";
                            response = Map.of("ok", false, "error", Map.of("code", code, "message", String.valueOf(error.getMessage())));
                        }
                        output.write(Json.write(response)); output.newLine(); output.flush();
                    }
                    if (shutdown) break;
                }
            } finally { Files.deleteIfExists(socket); }
        }
    }
}
