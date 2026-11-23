// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.agentos.proactivity.api.ProactivityClient;
import org.json.JSONArray;
import org.json.JSONObject;

/** Phone-only transport to the installed presentation service. It never creates a capability. */
public final class PixelProgressHost implements LiveFrameProgressHost {
    public static final class Factory implements LiveFrameProgressHost.Factory {
        public LiveFrameProgressHost.Session session(JSONObject receipt) {
            if (receipt == null || !receipt.has("presentationToken") && !receipt.has("presentationId")
                    && !receipt.has("presentationExpiresAt")) return null;
            Object token = receipt.opt("presentationToken"), id = receipt.opt("presentationId");
            Object raw = receipt.opt("presentationExpiresAt");
            if (!(token instanceof String) || !(id instanceof String) || !(raw instanceof Number)
                    || ((Number) raw).doubleValue() != ((Number) raw).longValue()) throw invalid();
            long expires = ((Number) raw).longValue();
            if (expires <= SystemClock.elapsedRealtime()) throw invalid();
            return new Lease((String) token, (String) id, expires);
        }
        public LiveFrameProgressHost create(Context context, LiveFrameProgressHost.Session session,
                LiveFrameProgressHost.Listener listener) {
            if (!(session instanceof Lease lease)) throw invalid();
            // The observer retains its accepted reminder connection until this attempt ends.
            // Keep the original host-issued presentation, including expanded needs-action cards.
            return new PixelProgressHost(context, lease, listener);
        }
    }
    private static final class Lease implements LiveFrameProgressHost.Session {
        private final String token, id;
        private final long expires;
        private long revision;
        Lease(String token, String id, long expires) {
            if (!token.matches("[A-Za-z0-9_-]{43}")
                    || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid();
            this.token = token; this.id = id; this.expires = expires;
        }
        long next() {
            if (revision >= 9_007_199_254_740_991L || expires > 0 && SystemClock.elapsedRealtime() >= expires)
                throw invalid();
            return ++revision;
        }
        @Override public String toString() { return "PixelProgressSession"; }
    }
    private final ProactivityClient client;
    private final Lease session;
    private final LiveFrameProgressHost.Listener listener;
    private boolean connected, closed;
    private Card pending, sent;
    private long submittedRevision;
    private String pendingRequest;

    private PixelProgressHost(Context context, Lease session, LiveFrameProgressHost.Listener listener) {
        main(); this.session = session; this.listener = listener;
        client = new ProactivityClient(context, new ProactivityClient.Listener() {
            public void onConnected() { if (!closed) { connected = true; flush(); } }
            public void onMessage(JSONObject message) { receive(message); }
            public void onDisconnected() {
                connected = false;
                if (!closed) listener.event("presentation_error");
            }
        });
        connect();
    }
    public void show(Card card) {
        main(); if (closed) return;
        Card next = contents(card);
        if (next.equals(pending)) return;
        if (next.equals(sent)) { pending = null; return; }
        pending = next;
        if (connected) flush(); else connect();
    }
    private static Card contents(Card source) {
        if (source == null || source.title() == null || source.title().isBlank() || source.body() == null)
            throw invalid();
        var actions = new ArrayList<Action>(); var ids = new HashSet<String>(); int primary = 0;
        boolean selection = source.actions().stream().anyMatch(action -> "select".equals(action.id()));
        for (Action action : source.actions()) {
            // Preserve the original two-button selection card; host dismissal still cancels.
            if (selection && "close".equals(action.id())) continue;
            if (action.id() == null || !action.id().matches("[a-z][a-z0-9_-]{0,39}")
                    || action.label() == null || action.label().isBlank() || action.label().length() > 80
                    || !ids.add(action.id()) || action.primary() && ++primary > 1) throw invalid();
            actions.add(action);
        }
        if (actions.isEmpty() || actions.size() > 3) throw invalid();
        return new Card(source.compact(), bounded(source.title(), 160), bounded(source.body(), 500),
                source.ongoing(), actions);
    }
    private void connect() {
        try { client.connect(); }
        catch (Exception unavailable) { if (!closed) listener.event("presentation_error"); }
    }
    private void flush() {
        if (closed || !connected || pending == null) return;
        Card next = pending;
        try {
            long revision = session.next(); JSONArray actions = new JSONArray();
            for (Action action : next.actions()) actions.put(new JSONObject().put("id", action.id())
                    .put("label", action.label()).put("primary", action.primary()));
            JSONObject args = new JSONObject().put("presentationToken", session.token).put("revision", revision)
                    .put("title", next.title()).put("summary", next.body()).put("actions", actions);
            if (next.ongoing()) args.put("status", "working");
            pendingRequest = client.request("present_action", args);
            submittedRevision = revision; sent = next; pending = null;
        } catch (Exception unavailable) { listener.event("presentation_error"); }
    }
    private void receive(JSONObject message) {
        main(); if (closed) return;
        String type = message.optString("type");
        if ("present_action".equals(type) || "dismiss_action_presentation".equals(type)) {
            if (pendingRequest != null && pendingRequest.equals(message.optString("requestId"))) {
                pendingRequest = null;
                if (!Boolean.TRUE.equals(message.opt("ok"))) listener.event("presentation_error");
            }
            return;
        }
        if (!type.startsWith("presentation_")) return;
        JSONObject result = message.optJSONObject("result");
        if (result == null || !session.id.equals(result.optString("presentationId"))
                || result.optLong("revision", -1) != submittedRevision) return;
        if ("presentation_action".equals(type)) {
            String id = result.optString("actionId");
            if (sent == null || sent.actions().stream().noneMatch(action -> action.id().equals(id))) return;
            sent = null; listener.action(id);
        } else if (List.of("presentation_published", "presentation_dismissed", "presentation_expired", "presentation_error").contains(type)) {
            if (!"presentation_published".equals(type)) sent = null;
            listener.event(type);
        }
    }
    public void close() {
        main(); if (closed) return;
        pending = sent = null;
        if (connected && submittedRevision > 0) {
            try { client.request("dismiss_action_presentation", new JSONObject()
                    .put("presentationToken", session.token).put("revision", submittedRevision)); }
            catch (Exception ignored) { }
        }
        closed = true; pendingRequest = null; client.close(); connected = false;
    }
    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_presentation_session"); }
    private static void main() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }
}
