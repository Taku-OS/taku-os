// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import java.util.List;
import org.json.JSONObject;

/** Optional presentation transport; it cannot authorize capture, recognition, or try-on. */
public interface LiveFrameProgressHost extends AutoCloseable {
    /** Ephemeral host-issued capability. Keep the same instance across one attempt's UI handoffs. */
    interface Session {}
    interface Listener {
        void action(String id);
        void event(String type);
    }
    interface Factory {
        /** Missing optional host data returns null; partially present or invalid data must fail. */
        Session session(JSONObject acceptedReceipt);
        /** Null keeps the normal notification path, including explicit flows without a host session. */
        LiveFrameProgressHost create(Context context, Session session, Listener listener);
    }
    record Action(String id, String label, boolean primary) {}
    record Card(String compact, String title, String body, boolean ongoing, List<Action> actions) {
        public Card { actions = List.copyOf(actions); }
    }

    /** Register from Application.onCreate. No optional host is loaded by the normal build. */
    static void installFactory(Factory factory) { Registry.factory = factory; }
    static Session session(JSONObject acceptedReceipt) {
        Factory factory = Registry.factory;
        return factory == null ? null : factory.session(acceptedReceipt);
    }
    static LiveFrameProgressHost create(Context context, Session session, Listener listener) {
        Factory factory = Registry.factory;
        return factory == null || session == null ? null : factory.create(context, session, listener);
    }
    final class Registry {
        private Registry() {}
        private static volatile Factory factory;
    }

    /** Main-thread calls and callbacks only. Close dismisses only this transport's latest revision. */
    void show(Card card);
    @Override void close();
}
