package org.agentos.assistant;

import android.os.Handler;

import org.json.JSONObject;

/** Account RPC actions use the existing broker; sensitive challenge views remain in memory. */
final class AgentAccountController {
    interface Listener {
        void account(JSONObject state);

        void busy(boolean busy);

        void error(String message);
    }

    private final AgentRepository repository;
    private final Listener listener;
    private final Handler handler = new Handler();
    private boolean visible, reading;
    private String signature = "";

    AgentAccountController(AgentRepository repository, Listener listener) {
        this.repository = repository;
        this.listener = listener;
    }

    void start() {
        if (visible) return;
        visible = true;
        signature = "";
        action("account/read", new JSONObject());
        handler.post(poll);
    }

    void stop() {
        visible = false;
        handler.removeCallbacks(poll);
    }

    void login(boolean browser) {
        JSONObject params = new JSONObject();
        try {
            params.put("type", browser ? "chatgpt" : "chatgptDeviceCode");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        action("account/login/start", params);
    }

    void cancel() {
        action("account/login/cancel", new JSONObject());
    }

    void refresh() {
        action("account/read", new JSONObject());
    }

    private void action(String method, JSONObject params) {
        listener.busy(true);
        repository.rpc(
                method,
                params,
                new AgentRepository.Callback<JSONObject>() {
                    public void ready(JSONObject ignored) {
                        if (!visible) return;
                        listener.busy(false);
                        signature = "";
                        read();
                    }

                    public void failed(String message) {
                        if (!visible) return;
                        listener.busy(false);
                        listener.error("Account request did not complete. Retry later.");
                        signature = "";
                        read();
                    }
                });
    }

    private void read() {
        if (!visible || reading) return;
        reading = true;
        repository.account(
                new AgentRepository.Callback<JSONObject>() {
                    public void ready(JSONObject state) {
                        reading = false;
                        if (!visible || signature.equals(state.toString())) return;
                        signature = state.toString();
                        listener.account(state);
                    }

                    public void failed(String message) {
                        reading = false;
                        if (visible) listener.error(message);
                    }
                });
    }

    private final Runnable poll =
            new Runnable() {
                public void run() {
                    read();
                    if (visible) handler.postDelayed(this, 900);
                }
            };
}
