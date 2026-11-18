package org.agentos.lab;

import org.json.JSONObject;

/** Native account state held only in RAM. Login artifacts never enter task/projection files. */
final class AgentAccount {
    private static volatile String view = "{\"phase\":\"loading\",\"connected\":false}";
    private static boolean connected;
    private static String phase = "loading", plan = "", kind = "", loginId = "";
    private static String url = "", code = "", message = "Checking account connection…";
    private static String completedId = "";
    private static boolean completedSuccessfully;
    static boolean refreshRequested;

    static String snapshot() {
        return view;
    }

    static String loginId() {
        return loginId;
    }

    static void gateway(boolean ready, String detail) throws Exception {
        clearChallenge();
        connected = ready; kind = "magpie"; plan = "";
        phase = ready ? "connected" : "disconnected";
        message = ready ? "Local Magpie is connected. The model service will be verified when requested." : detail;
        publish();
    }

    private static void publish() throws Exception {
        view =
                new JSONObject()
                        .put("connected", connected)
                        .put("phase", phase)
                        .put("plan", plan)
                        .put("kind", kind)
                        .put("url", url)
                        .put("code", code)
                        .put("message", message)
                        .toString();
    }

    private static void clearChallenge() {
        loginId = "";
        url = "";
        code = "";
    }

    static void read(JSONObject response) throws Exception {
        JSONObject account = response.optJSONObject("account");
        connected = account != null;
        kind = connected ? account.optString("type") : "";
        plan = connected ? account.optString("planType", "") : "";
        if (connected) {
            clearChallenge();
            phase = "connected";
            message = "Account connected. You can start a conversation.";
        } else if (!phase.equals("pending")
                && !phase.equals("starting")
                && !phase.equals("failed")) {
            clearChallenge();
            phase = "disconnected";
            message = "Connect an account to get started with Taku.";
        }
        publish();
    }

    static void starting() throws Exception {
        clearChallenge();
        phase = "starting";
        message = "Preparing official authorization…";
        publish();
    }

    static void started(JSONObject response) throws Exception {
        loginId = response.getString("loginId");
        boolean deviceCode = "chatgptDeviceCode".equals(response.getString("type"));
        url = response.getString(deviceCode ? "verificationUrl" : "authUrl");
        code = deviceCode ? response.getString("userCode") : "";
        phase = "pending";
        message = "Complete authorization on the official page. Connection status updates automatically.";
        if (loginId.equals(completedId)) finish(completedSuccessfully);
        else publish();
    }

    static void event(JSONObject event) throws Exception {
        String method = event.optString("method");
        JSONObject params = event.optJSONObject("params");
        if (params == null) return;
        if (method.equals("account/updated")) refreshRequested = true;
        if (method.equals("account/login/completed")) {
            completedId = params.optString("loginId", "");
            completedSuccessfully = params.optBoolean("success");
            if (!loginId.isEmpty() && loginId.equals(completedId)) finish(completedSuccessfully);
            if (completedSuccessfully) refreshRequested = true;
        }
    }

    private static void finish(boolean success) throws Exception {
        clearChallenge();
        phase = success ? "verifying" : "failed";
        message = success ? "Authorization complete. Confirming your account…" : "Authorization was not completed or has expired. Please reconnect.";
        // A success event alone never changes connected to true; account/read is authoritative.
        refreshRequested |= success;
        publish();
    }

    static void cancelled() throws Exception {
        clearChallenge();
        phase = "disconnected";
        message = "This sign-in was cancelled.";
        publish();
    }

    static void failed() throws Exception {
        clearChallenge();
        phase = "failed";
        message = "Cannot connect to the authorization service. Check your network and retry, or authorize in a browser.";
        publish();
    }
}
