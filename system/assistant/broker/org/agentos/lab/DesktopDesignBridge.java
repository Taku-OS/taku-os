package org.agentos.lab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Declarative commands to an explicitly provisioned, bounded loopback desktop relay. */
final class DesktopDesignBridge {
    static final Path SESSION = Path.of("/data/local/agentos/figma-desktop/session.json");
    // State contains the bounded command and receipt together, plus identity fields.
    static final int MAX_PAYLOAD = 65_000, MAX_RESPONSE = 393_216;

    static JSONObject definition() throws Exception {
        JSONObject definition = PhoneNativeTools.tool("desktop_design",
                "Use the configured loopback Figma desktop plugin for native layers. First call action=state. "
                + "An unconfigured device returns configured=false. Submit payload as JSON {requestId,job,body}; "
                + "supported body actions are begin/apply/clone/link/select/inspect. Wait for the exact requestId, job, "
                + "fileKey, executedBy=desktop-plugin and error=null in state before continuing. "
                + "The plugin supports click or timed transitions, not free drag physics. An acknowledgment proves execution, not visual fidelity.",
                new JSONObject().put("action", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("state").put("submit")))
                        .put("payload", PhoneNativeTools.string(MAX_PAYLOAD)));
        definition.getJSONObject("inputSchema").put("required", new JSONArray().put("action"));
        return definition;
    }

    static JSONObject configuration(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8192)
            throw new IOException("FIGMA_SESSION_INVALID");
        JSONObject config = new JSONObject(Files.readString(path));
        validateConfiguration(config);
        return config;
    }

    static void validateConfiguration(JSONObject config) throws Exception {
        if (!keys(config).equals(Set.of("endpoint", "fileKey", "token")))
            throw new IOException("FIGMA_SESSION_INVALID");
        URI endpoint = new URI(config.getString("endpoint"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() != 8790 || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !"".equals(endpoint.getRawPath())
                || !config.getString("fileKey").matches("[-A-Za-z0-9]{1,100}")
                || !config.getString("token").matches("[-A-Za-z0-9_]{32,128}"))
            throw new IOException("FIGMA_SESSION_INVALID");
    }

    static JSONObject control(JSONObject arguments) throws Exception {
        String action = arguments.getString("action");
        if (!action.equals("state") && !action.equals("submit")) throw new IOException("INVALID_DESIGN_ACTION");
        if (!Files.exists(SESSION)) {
            if (action.equals("state")) return new JSONObject().put("configured", false);
            throw new IOException("FIGMA_SESSION_NOT_CONFIGURED");
        }
        JSONObject config = configuration(SESSION);
        String payload = action.equals("submit") ? arguments.getString("payload") : "";
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD)
            throw new IOException("DESIGN_COMMAND_TOO_LARGE");
        if (action.equals("submit")) {
            JSONObject request = new JSONObject(payload);
            if (!keys(request).equals(Set.of("requestId", "job", "body")))
                throw new IOException("INVALID_DESIGN_REQUEST");
        }
        HttpURLConnection connection = (HttpURLConnection) new URI(config.getString("endpoint")
                + (action.equals("submit") ? "/request" : "/state")).toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setRequestProperty("Authorization", "Bearer " + config.getString("token"));
        try {
            if (action.equals("submit")) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (var output = connection.getOutputStream()) { output.write(payload.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("DESIGN_RELAY_HTTP_" + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (bytes.size() + read > MAX_RESPONSE) throw new IOException("DESIGN_RECEIPT_TOO_LARGE");
                    bytes.write(buffer, 0, read);
                }
                JSONObject result = new JSONObject(bytes.toString(StandardCharsets.UTF_8));
                if (action.equals("state")) {
                    JSONObject target = result.optJSONObject("target");
                    if (target == null || !config.getString("fileKey").equals(target.optString("fileKey")))
                        throw new IOException("DESIGN_TARGET_MISMATCH");
                    result.put("configured", true);
                }
                return result;
            }
        } finally { connection.disconnect(); }
    }

    private static Set<String> keys(JSONObject value) {
        Set<String> keys = new java.util.HashSet<>();
        value.keys().forEachRemaining(keys::add);
        return keys;
    }
}
