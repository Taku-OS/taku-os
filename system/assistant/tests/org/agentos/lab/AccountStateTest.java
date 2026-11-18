package org.agentos.lab;

import org.json.JSONObject;

/** Protocol guards only: synthetic native events, never an OAuth or model acceptance claim. */
public final class AccountStateTest {
    private static JSONObject view() throws Exception {
        return new JSONObject(AgentAccount.snapshot());
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Account invariant failed");
    }

    public static void main(String[] args) throws Exception {
        AgentAccount.read(new JSONObject().put("account", JSONObject.NULL));
        AgentAccount.starting();
        AgentAccount.event(
                new JSONObject()
                        .put("method", "account/login/completed")
                        .put(
                                "params",
                                new JSONObject()
                                        .put("loginId", "protocol-only-1")
                                        .put("success", true)));
        AgentAccount.started(
                new JSONObject()
                        .put("type", "chatgptDeviceCode")
                        .put("loginId", "protocol-only-1")
                        .put("verificationUrl", "https://auth.openai.com/codex/device")
                        .put("userCode", "TEST-ONLY"));
        check(!view().getBoolean("connected"));
        check(view().getString("phase").equals("verifying"));
        check(view().getString("code").isEmpty() && view().getString("url").isEmpty());
        AgentAccount.read(
                new JSONObject()
                        .put(
                                "account",
                                new JSONObject().put("type", "chatgpt").put("planType", "free")));
        check(view().getBoolean("connected"));
        AgentAccount.read(new JSONObject().put("account", JSONObject.NULL));
        AgentAccount.starting();
        AgentAccount.started(
                new JSONObject()
                        .put("type", "chatgptDeviceCode")
                        .put("loginId", "protocol-only-2")
                        .put("verificationUrl", "https://auth.openai.com/codex/device")
                        .put("userCode", "TEST-ONLY"));
        AgentAccount.event(
                new JSONObject()
                        .put("method", "account/login/completed")
                        .put(
                                "params",
                                new JSONObject().put("loginId", "unrelated").put("success", true)));
        check(!view().getBoolean("connected") && view().getString("phase").equals("pending"));
        AgentAccount.cancelled();
        check(view().getString("code").isEmpty() && view().getString("url").isEmpty());
        AgentAccount.failed();
        check(!view().getBoolean("connected") && view().getString("phase").equals("failed"));
        System.out.println(
                "PASS: protocol-only account event race, native-read authority, cancellation and"
                    + " secret clearing. No real login simulated.");
    }
}
