package org.agentos.lab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure configuration/schema tests; no relay sockets, Android runtime, or credentials. */
final class DesktopDesignBridgeTest {
    static int checks;
    static void check(boolean value, String description) { checks++; if (!value) throw new AssertionError(description); }
    static JSONObject valid() throws Exception {
        return new JSONObject().put("endpoint", "http://127.0.0.1:8790")
                .put("fileKey", "SyntheticFile").put("token", "t".repeat(40));
    }
    public static void main(String[] ignored) throws Exception {
        DesktopDesignBridge.validateConfiguration(valid()); check(true, "loopback config accepted");
        for (String endpoint : new String[]{"https://example.com", "http://localhost:8790", "http://127.0.0.1:8792",
                "http://127.0.0.1:8790/", "http://127.0.0.1:8790/state", "http://token@127.0.0.1:8790", "http://127.0.0.1:8790?token=x"}) {
            try { DesktopDesignBridge.validateConfiguration(valid().put("endpoint", endpoint)); throw new AssertionError(endpoint); }
            catch (IOException expected) { check(true, "unsafe endpoint rejected"); }
        }
        for (JSONObject invalid : new JSONObject[]{valid().put("token", "short"), valid().put("fileKey", "file/key"),
                valid().put("other", "value"), valid().put("token", "t".repeat(40) + "\r\nInjected: header")}) {
            try { DesktopDesignBridge.validateConfiguration(invalid); throw new AssertionError("invalid config accepted"); }
            catch (IOException expected) { check(true, "invalid config rejected"); }
        }
        JSONObject schema = DesktopDesignBridge.definition().getJSONObject("inputSchema");
        check(schema.getJSONArray("required").length() == 1 && schema.getJSONArray("required").getString(0).equals("action"), "state needs no payload");
        check(!schema.getBoolean("additionalProperties"), "closed tool schema");
        JSONArray modules = new JSONObject(Files.readString(Path.of("system/assistant/res/raw/taku_harness.json"))).getJSONArray("modules");
        String workflow = null;
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.getJSONObject(i);
            if (module.getString("id").equals("figma-reference-workflow")) {
                check(workflow == null, "workflow has one authoritative module");
                check(module.getString("placement").equals("session-developer"), "workflow freezes with the session catalog");
                workflow = module.getString("content");
            }
        }
        check(workflow != null && workflow.contains("does not prove") && workflow.contains("silently replace dragging"), "workflow states capability and acceptance limits");
        System.out.println("DesktopDesignBridgeTest: " + checks + " checks passed (host only)");
    }
}
