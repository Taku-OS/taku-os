package org.agentos.lab;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntPredicate;
import org.json.JSONObject;

public final class NativeBridgeSocketTest {
    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
    }

    public static void main(String[] args) throws Exception {
        IntPredicate unexpected = uid -> { throw new AssertionError("Unexpected policy lookup: " + uid); };
        IntPredicate policy = uid -> uid == 10042;
        JSONObject state = new JSONObject().put("op", "state").put("uid", 0);
        JSONObject plugin = new JSONObject().put("op", "rpc").put("method", "plugin/list");
        check(NativeBridge.isCallerAuthorized(0, state, unexpected), "root");
        check(NativeBridge.isCallerAuthorized(1000, state, unexpected), "system");
        check(NativeBridge.isCallerAuthorized(2000, plugin, unexpected), "shell plugin RPC");
        check(!NativeBridge.isCallerAuthorized(2000, state, unexpected), "shell ordinary RPC");
        check(!NativeBridge.isCallerAuthorized(10042, state, unexpected), "grant still requires a plugin request");
        check(NativeBridge.isCallerAuthorized(10042, plugin, policy), "permission center grant");
        check(!NativeBridge.isCallerAuthorized(10043, plugin, policy), "permission center denial");
        check(!NativeBridge.isCallerAuthorized(1001, plugin, policy), "unrecognized system service");
        JSONObject invalidSetting = new JSONObject().put("op", "rpc").put("method", "config/value/write")
                .put("params", new JSONObject().put("keyPath", "unrelated").put("value", true)
                        .put("mergeStrategy", "upsert"));
        check(!NativeBridge.isCallerAuthorized(2000, invalidSetting, unexpected), "unrelated config write");

        byte[] json = ("{}" + " ".repeat(1_999_998)).getBytes(StandardCharsets.UTF_8);
        check(NativeBridge.readAuthorizedRequest(new ByteArrayInputStream(json), 0, unexpected).isEmpty(), "exact byte limit");
        ByteArrayInputStream oversized = new ByteArrayInputStream(
                (plugin + " ".repeat(2_000_102 - plugin.toString().length())).getBytes(StandardCharsets.UTF_8));
        try {
            NativeBridge.readAuthorizedRequest(oversized, 10042, unexpected);
            throw new AssertionError("Oversized request reached JSON parsing");
        } catch (IOException expected) {
            check("Message exceeds the limit".equals(expected.getMessage()), "bounded rejection");
            check(oversized.available() == 101, "read stops at the first byte past the limit");
        }
        check(NativeBridge.readAuthorizedRequest(
                new ByteArrayInputStream(plugin.toString().getBytes(StandardCharsets.UTF_8)), 10043, policy)
                == null, "denied plugin request does not reach dispatch");
        System.out.println("NativeBridgeSocketTest passed (13 checks)");
    }
}
