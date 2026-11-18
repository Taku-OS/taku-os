package org.agentos.lab;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Agent-facing client of the same native RPC bridge used by the plugin screen. */
public final class PluginCli {
    private static JSONObject rpc(String method, JSONObject params) throws Exception {
        return request(new JSONObject().put("op", "rpc").put("method", method).put("params", params));
    }

    private static JSONObject request(JSONObject request) throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("taku-agent-native"));
            socket.setSoTimeout(45000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = socket.getInputStream().read(buffer)) != -1) {
                response.write(buffer, 0, count);
                if (response.size() > 2000000) throw new IllegalStateException("response too large");
            }
            JSONObject reply = new JSONObject(response.toString("UTF-8"));
            if (!reply.optBoolean("ok")) throw new IllegalStateException(reply.optString("message", "RPC failed"));
            return reply.getJSONObject("result");
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("godmode")) {
                godmode(args); return;
            }
            if (args.length == 2 && args[0].equals("validate")) {
                System.out.println(PluginCreator.validate(Paths.get(args[1])).toString()); return;
            }
            if (args.length == 2 && args[0].equals("catalog-add")) {
                System.out.println(request(new JSONObject().put("op", "pluginCatalogAdd").put("path", args[1])).toString()); return;
            }
            if (args.length == 3 && args[0].equals("pack")) {
                System.out.println(PluginCreator.pack(Paths.get(args[1]), Paths.get(args[2])).toString()); return;
            }
            if ((args.length == 3 || args.length == 4) && args[0].equals("publish")) {
                System.out.println(PluginCreator.publish(Paths.get(args[1]), Paths.get(args[2]), args.length == 4 ? args[3] : null).toString()); return;
            }
            JSONObject params = new JSONObject();
            String method;
            boolean reload = false;
            if (args.length == 1 && args[0].equals("list")) {
                method = "plugin/list";
                params.put("cwds", new JSONArray().put("/data/local/agentos/work"));
            } else if (args.length == 1 && args[0].equals("status")) {
                method = "mcpServerStatus/list";
                params.put("limit", 100);
            } else if (args.length == 3 && args[0].equals("install")) {
                method = "plugin/install";
                params.put("marketplacePath", args[1]).put("pluginName", args[2]);
                reload = true;
            } else if (args.length == 2 && args[0].equals("rollback")) {
                method = "plugin/rollback";
                params.put("pluginId", args[1]);
                reload = true;
            } else if (args.length == 2 && (args[0].equals("uninstall") || args[0].equals("remove"))) {
                method = "plugin/uninstall";
                params.put("pluginId", args[1]);
                reload = true;
            } else if (args.length == 1 && args[0].equals("reload")) {
                method = "config/mcpServer/reload";
            } else if (args.length == 2 && (args[0].equals("enable") || args[0].equals("disable"))) {
                if (!args[1].matches("[A-Za-z0-9_.@-]+")) throw new IllegalArgumentException("invalid plugin ID");
                method = "config/value/write";
                params.put("keyPath", "plugins.\"" + args[1] + "\".enabled")
                        .put("mergeStrategy", "upsert").put("value", args[0].equals("enable"));
                reload = true;
            } else {
                throw new IllegalArgumentException("usage: taku-plugin validate DIR|pack DIR OUTPUT|catalog-add PACK_DIR|publish DIR OUTPUT [OWNER/REPO]|list|status|install MARKETPLACE_PATH NAME|enable ID|disable ID|uninstall ID|remove ID|reload|rollback ID");
            }
            JSONObject result = rpc(method, params);
            if (reload) {
                try { rpc("config/mcpServer/reload", new JSONObject()); }
                catch (Exception error) {
                    System.err.println("Plugin operation succeeded, but MCP reload failed: " + error.getMessage());
                    System.exit(2);
                }
            }
            System.out.println(result.toString());
        } catch (Exception error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    private static void godmode(String[] args) {
        try {
            if (args.length < 2 || args.length > 5 || (args.length > 3 && !(args.length == 5 && (args[1].equals("call-tool") || args[1].equals("run-check"))) && !(args.length == 4 && args[1].equals("tool")))) throw new IllegalArgumentException("usage: taku-plugin godmode list|tools [TOOLSET]|tool ID NAME|checks [ID]|run-check ID NAME JSON|evidence [ID]|show ID|install PATH|install-url JSON|remove ID|enable ID|disable ID|reload ID|rollback ID|call-tool ID NAME JSON");
            String path = System.getenv("TAKU_GODMODE_SOCKET");
            if (path == null || path.isEmpty()) path = "taku-godmode";
            JSONObject request = new JSONObject().put("operation", args[1]);
            if (args.length == 3 && (args[1].equals("local") || args[1].equals("install-url"))) request.put("arguments", new JSONObject(args[2]));
            else if (args.length == 3) request.put("argument", args[1].equals("install") ? new File(args[2]).getAbsolutePath() : args[2]);
            if (args.length == 4) request.put("argument", args[2]).put("name", args[3]);
            if (args.length == 5) request.put("argument", args[2]).put("name", args[3]).put("arguments", new JSONObject(args[4]));
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(path, path.startsWith("/") ? LocalSocketAddress.Namespace.FILESYSTEM : LocalSocketAddress.Namespace.ABSTRACT));
                socket.setSoTimeout(45000);
                socket.getOutputStream().write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                int character;
                while ((character = socket.getInputStream().read()) != -1 && character != '\n') {
                    response.write(character);
                    if (response.size() > 2000000) throw new IllegalStateException("response too large");
                }
                JSONObject reply = new JSONObject(response.toString("UTF-8"));
                System.out.println(reply.toString());
                if (!reply.optBoolean("ok")) System.exit(1);
            }
        } catch (Exception error) {
            try {
                String code = error instanceof IllegalArgumentException ? "USAGE" : "SERVICE_UNAVAILABLE";
                System.out.println(new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code).put("message", String.valueOf(error.getMessage()))));
            } catch (Exception ignored) { System.err.println("God Mode client error"); }
            System.exit(1);
        }
    }
}
