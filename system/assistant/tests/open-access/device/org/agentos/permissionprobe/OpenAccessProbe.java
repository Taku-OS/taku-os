package org.agentos.permissionprobe;

import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;

/** Runs as its own ordinary app UID on an isolated emulator; never submits a real mutation. */
public final class OpenAccessProbe extends Instrumentation {
    private String mode;
    @Override public void onCreate(Bundle arguments) {
        mode = arguments.getString("mode", "control");
        start();
    }
    private JSONObject call(String authority, String method, String arg) throws Exception {
        Bundle reply = getTargetContext().getContentResolver().call(Uri.parse("content://" + authority), method, arg, null);
        return new JSONObject(reply.getString("result"));
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            String open = "org.agentos.assistant.open-access";
            if (mode.equals("setup")) {
                JSONObject configured = call("org.agentos.assistant.agent-gateway", "setAppPermissionMode",
                        new JSONObject().put("mode", "yolo").put("allowRootInYolo", false).toString());
                if (!configured.optBoolean("ok")) throw new AssertionError("Fixture policy failed: " + configured);
                output.putString("result", "{\"passed\":true,\"mode\":\"yolo\",\"allowRootInYolo\":false}");
                finish(-1, output);
                return;
            }
            JSONObject read = call(open, "reactions/options", "{}");
            if (!read.optBoolean("ok")) throw new AssertionError("Control read did not succeed: " + read);
            int checks = 1;
            if (mode.equals("gateway")) {
                JSONObject result = call("org.agentos.assistant.agent-gateway", "composer/read", new JSONObject().put("threadId", "").toString());
                if (!result.optBoolean("ok")) throw new AssertionError("Signed GUI gateway failed: " + result);
                JSONObject before = result.getJSONObject("result");
                JSONObject written = call("org.agentos.assistant.agent-gateway", "composer/write",
                        new JSONObject().put("threadId", "").put("revision", before.getString("revision"))
                                .put("text", "permission probe draft").toString());
                if (!written.optBoolean("ok")) throw new AssertionError("Signed GUI write failed: " + written);
                JSONObject shared = call(open, "composer/read", new JSONObject().put("threadId", "").toString());
                JSONObject draft = shared.getJSONObject("result");
                if (!"permission probe draft".equals(draft.getString("text")))
                    throw new AssertionError("GUI and open access did not share the same draft");
                JSONObject restored = call("org.agentos.assistant.agent-gateway", "composer/write",
                        new JSONObject().put("threadId", "").put("revision", draft.getString("revision"))
                                .put("text", before.getString("text")).toString());
                if (!restored.optBoolean("ok")) throw new AssertionError("Draft restore failed");
                checks += 4;
                for (String method : new String[]{"readAccount", "readJev"}) {
                    JSONObject onboarding = call("org.agentos.assistant.agent-gateway", method, "{}");
                    if (!onboarding.optBoolean("ok")) throw new AssertionError("Signed onboarding read failed: " + method);
                    JSONObject state = onboarding.getJSONObject("result");
                    if (!state.has(method.equals("readAccount") ? "aiAvailable" : "ready"))
                        throw new AssertionError("Missing authoritative onboarding state: " + method);
                    checks++;
                }
            } else {
                try {
                    call("org.agentos.assistant.agent-gateway", "composer/read", new JSONObject().put("threadId", "").toString());
                    throw new AssertionError("Third-party app reached signature gateway");
                } catch (SecurityException expected) { checks++; }
                for (String operation : new String[]{"plugin/install", "config/mcpServer/reload",
                        "skills/community/install", "mcpServer/tool/call", "thread/queue/submit", "taskFiles/write"}) {
                    // Missing operation parameters make this harmless even if the guard regresses.
                    JSONObject result = call(open, "manageAssistant", new JSONObject().put("operation", operation).toString());
                    if (!"PERMISSION_DENIED".equals(result.optString("code")))
                        throw new AssertionError("Nested operation not denied: " + operation + " " + result);
                    checks++;
                }
                for (String method : new String[]{"composer/submit", "composer/write", "composer/options/write",
                        "composer/plugin/add", "composer/plugin/remove",
                        "requests/respond", "requests/submit", "requests/cancel", "accounts/saveKey",
                        "accounts/removeKey", "accounts/login", "accounts/openMagpie", "accounts/cancel",
                        "accounts/refresh", "manageSystemAi", "setSystemAi", "bindSystemAi", "bindSystemAiModel",
                        "reorderSystemAi", "restoreSystemAi", "deleteSystemAiGroup", "skills/community/install",
                        "jev/status", "jev/connect", "jev/enable", "account/login/start", "account/login/cancel",
                        "capture/read", "capture/request", "capture/accept", "capture/dismiss",
                        "capture/openConversation", "interrupt", "cancelQueued", "readTaskDetail"}) {
                    // Permission must be checked before parsing even malformed inputs.
                    JSONObject result = call(open, method, "{");
                    if (!"PERMISSION_DENIED".equals(result.optString("code")))
                        throw new AssertionError("Sensitive method not denied: " + method + " " + result);
                    checks++;
                }
            }
            output.putString("result", new JSONObject().put("passed", true).put("mode", mode)
                    .put("uid", android.os.Process.myUid()).put("checks", checks).toString());
            finish(-1, output);
        } catch (Throwable failure) {
            output.putString("error", failure.toString());
            finish(0, output);
        }
    }
}
