package org.agentos.assistant;

import android.content.Context;
import android.os.Handler;

import org.json.*;

/** Lifecycle and native-thread coordination, independent of the system window and theme. */
final class AgentDrawerController implements AutoCloseable {
    interface Listener {
        void conversation(AgentConversation value, JSONObject snapshot);

        void notice(String message);

        void busy(boolean value);

        void selectionStarted();

        void selectionFailed(String message, Runnable retry);
    }

    private final AgentRepository repository;
    private final Listener listener;
    private final Handler handler = new Handler();
    private String threadId = "", signature = "";
    private boolean visible, reading;
    private long selectionRevision;

    AgentDrawerController(Context context, Listener listener) {
        this.repository = new AgentRepository(context);
        this.listener = listener;
    }

    AgentRepository repository() {
        return repository;
    }

    String threadId() {
        return threadId;
    }

    void start(String requested) {
        start(requested,false);
    }

    void start(String requested,boolean fresh) {
        visible = true;
        String stored = repository.preferences().getString("conversation", "");
        String selected = fresh ? "" : AgentRepository.validId(requested) ? requested : stored;
        if (AgentRepository.validId(selected)) select(selected);
        else newConversation();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    void stop() {
        visible = false;
        selectionRevision++;
        reading = false;
        handler.removeCallbacks(poll);
    }

    void newConversation() {
        long revision = beginSelection();
        repository.rpc(
                "thread/start",
                new JSONObject(),
                callback(revision,
                        result -> {
                            threadId = result.getJSONObject("thread").getString("id");
                            if (!AgentRepository.validId(threadId)) throw new JSONException("Invalid thread id");
                            repository
                                    .preferences()
                                    .edit()
                                    .putString("conversation", threadId)
                                    .apply();
                            signature = "";
                            listener.busy(false);
                            refresh();
                        }, this::newConversation));
    }

    private long beginSelection() {
        long revision = ++selectionRevision;
        threadId = "";
        signature = "";
        reading = false;
        listener.selectionStarted();
        listener.busy(true);
        return revision;
    }

    void select(String id) {
        if (!AgentRepository.validId(id)) return;
        long revision = beginSelection();
        repository.preferences().edit().putString("conversation", id).apply();
        repository.rpc(
                "thread/read",
                parameter("threadId", id),
                callback(revision,
                        result -> {
                            if (result.optBoolean("threadUnavailable"))
                                throw new IllegalStateException("The original conversation is temporarily unavailable. Unsent requests and drafts were preserved.");
                            if (!id.equals(result.optString("threadId")))
                                throw new JSONException("Unexpected thread id");
                            threadId = id;
                            listener.busy(false);
                            refresh();
                        }, () -> select(id)));
    }

    void rename(String name) {
        String value = name.trim();
        if (value.isEmpty() || value.length() > 120 || !AgentRepository.validId(threadId)) return;
        JSONObject params = parameter("threadId", threadId);
        try {
            params.put("name", value);
        } catch (JSONException ignored) {
            return;
        }
        repository.rpc(
                "thread/name/set",
                params,
                callback(selectionRevision,
                        result -> {
                            signature = "";
                            refresh();
                        }, null));
    }

    private interface ResultAction {
        void run(JSONObject result) throws Exception;
    }

    private AgentRepository.Callback<JSONObject> callback(long revision, ResultAction action, Runnable retry) {
        return new AgentRepository.Callback<JSONObject>() {
            public void ready(JSONObject result) {
                if (!visible || revision != selectionRevision) return;
                try {
                    action.run(result);
                } catch (Exception error) {
                    failed(error instanceof IllegalStateException ? error.getMessage()
                            : "The conversation service returned an invalid structure. Native conversation history was not changed.");
                }
            }

            public void failed(String message) {
                if (!visible || revision != selectionRevision) return;
                listener.busy(false);
                if (retry == null) listener.notice(message);
                else {
                    threadId = "";
                    listener.selectionFailed(message, () -> {
                        if (visible && revision == selectionRevision) retry.run();
                    });
                }
            }
        };
    }

    private void refresh() {
        if (!visible || reading || !AgentRepository.validId(threadId)) return;
        reading = true;
        String requested = threadId;
        long revision = selectionRevision;
        repository.snapshot(
                requested,
                new AgentRepository.Callback<JSONObject>() {
                    public void ready(JSONObject result) {
                        if (!visible || revision != selectionRevision || !requested.equals(threadId)) return;
                        reading = false;
                        String nextSignature = signature(result);
                        if (nextSignature.equals(signature)) return;
                        signature = nextSignature;
                        JSONObject thread = result.optJSONObject("thread");
                        if (thread == null || !requested.equals(thread.optString("id"))) return;
                        try {
                            listener.conversation(AgentConversation.read(thread), result);
                        } catch (JSONException error) {
                            listener.notice("Invalid conversation display data format. Native conversation history was not changed.");
                        }
                    }

                    public void failed(String message) {
                        if (!visible || revision != selectionRevision || !requested.equals(threadId)) return;
                        reading = false;
                        listener.notice(message);
                    }
                });
    }

    private final Runnable poll =
            new Runnable() {
                public void run() {
                    refresh();
                    if (visible) handler.postDelayed(this, 500);
                }
            };

    private static JSONObject parameter(String key, String value) {
        JSONObject params = new JSONObject();
        try {
            return params.put(key, value);
        } catch (JSONException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    private static String signature(JSONObject snapshot) {
        StringBuilder value = new StringBuilder(snapshot.optString("projectionStamp", ""));
        for (String key : new String[] {"composerSubmitting", "composerSteering", "native", "task", "request", "runtime"}) {
            Object part = snapshot.opt(key);
            value.append('|').append(key).append('=');
            if (part instanceof JSONObject || part instanceof JSONArray) value.append(part.toString());
            else value.append(String.valueOf(part));
        }
        return value.toString();
    }

    @Override
    public void close() {
        stop();
        repository.close();
    }
}
