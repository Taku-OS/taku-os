package org.agentos.assistant;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Narrow, system-UID forwarding to the God Mode local-socket manager. */
final class PluginManagerBridge {
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private PluginManagerBridge() {}

    static JSONObject execute(JSONObject input) {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("taku-godmode", LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(45000);
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            output.write(input.toString());
            output.newLine();
            output.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder line = new StringBuilder();
            int value;
            while ((value = reader.read()) != -1) {
                if (value == '\n') break;
                if (line.length() >= MAX_RESPONSE) throw new IllegalStateException("plugin response too large");
                line.append((char) value);
            }
            if (line.length() == 0) throw new IllegalStateException("God Mode returned no response");
            JSONObject response = new JSONObject(line.toString());
            if (response.optBoolean("ok")) return response;
            JSONObject error = response.optJSONObject("error");
            return new JSONObject().put("ok", false).put("error",
                    new JSONObject().put("code", error == null ? "OPERATION_FAILED" : error.optString("code", "OPERATION_FAILED"))
                            .put("message", error == null ? "God Mode operation failed" : error.optString("message", "God Mode operation failed")));
        } catch (Exception error) {
            try {
                return new JSONObject().put("ok", false).put("error",
                        new JSONObject().put("code", "UNAVAILABLE").put("message", error.getMessage() == null ? "God Mode host unavailable" : error.getMessage()));
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
