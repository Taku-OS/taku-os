// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import org.agentos.proactivity.api.*;
import org.json.*;

/** A connection owned by either the settings page or the observer, never by TikTok. */
final class ReminderConnection implements AutoCloseable {
    static final String ID = "org.agentos.shopping.tiktok", EVENT = "org.agentos.shopping.garment_ready";
    static final String CONSENT_SUMMARY = "LIVE image + camera video · Cloud AI\nUp to 60s · Billed by usage";
    interface Listener {
        void state(boolean allowed, String status);
        default void event(String type, JSONObject receipt) {}
        default void authorizationRevoked() {}
    }
    private final ProactivityClient client;
    private final Listener listener;
    private boolean closed;
    boolean allowed, connected;
    ReminderConnection(Context c, Listener listener) {
        this.listener = listener;
        client = new ProactivityClient(c, new ProactivityClient.Listener() {
            public void onConnected() {
                if (closed) return;
                connected = true;
                try { client.register(new ObserverSpec(ID, "TikTok Live Try-On", EVENT, 500, 60000, 1, 60000,
                    "See how the host’s outfit looks on you?", CONSENT_SUMMARY, new PromptSpec("Try on", "Not now"))); }
                catch (Exception unavailable) { listener.state(false, "Reminder service is unavailable"); }
            }
            public void onMessage(JSONObject message) {
                if (closed) return;
                String type = message.optString("type");
                if (!message.optBoolean("ok")) {
                    allowed = false; listener.authorizationRevoked();
                    listener.state(false, "Allow this observer in Proactivity settings"); return;
                }
                JSONObject result = message.optJSONObject("result");
                if (result == null) return;
                if (type.equals("register") || type.equals("inspect") || type.equals("policy_changed")) {
                    allowed = result.optBoolean("approved") && result.optBoolean("enabled")
                        && result.optLong("activeRevision") > 0 && result.optLong("pendingRevision") == 0;
                    if (!allowed) listener.authorizationRevoked();
                    listener.state(allowed, allowed ? "System reminders are allowed" : "Allow the Live Try-On observer");
                } else if (ID.equals(result.optString("observerId"))) listener.event(type, result);
            }
            public void onDisconnected() {
                connected = false; allowed = false;
                if (!closed) listener.state(false, "Reminder service disconnected");
            }
        });
    }
    void connect() { try { client.connect(); } catch (Exception e) { listener.state(false, "Install the Taku Proactivity component first"); } }
    /** Host ownership and event freshness are checked before this explicit-choice marker is used. */
    static boolean isTryOnAcceptance(JSONObject receipt) {
        return receipt != null && "choice".equals(receipt.opt("promptType")) && "accept".equals(receipt.opt("actionId"));
    }
    void emit(String eventId, int revision) throws Exception { if (closed || !allowed) throw new IllegalStateException(); client.emit(ID, eventId, EVENT, revision); }
    @Override public void close() { closed = true; connected = false; allowed = false; client.close(); }
}
