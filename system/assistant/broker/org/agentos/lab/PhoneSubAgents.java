package org.agentos.lab;

import org.json.*;

import java.io.IOException;
import java.util.*;

/**
 * Codex sub-agents (multi_agent) as the broker sees them. Codex refuses app-server input to a
 * sub-agent, so a give-back of its screen is relayed to the session that spawned it; archiving a
 * session ends the sessions it spawned too. No Android or NativeBridge types here, so host tests
 * drive it with a fake Codex.
 */
final class PhoneSubAgents {
    /** A Codex app-server request: method and params in, result out. */
    interface Rpc {
        JSONObject call(String method, JSONObject params) throws Exception;
    }

    private static JSONObject spawn(JSONObject read) {
        JSONObject thread = read == null ? null : read.optJSONObject("thread");
        JSONObject source = thread == null ? null : thread.optJSONObject("source");
        JSONObject sub = source == null ? null : source.optJSONObject("subAgent");
        return sub == null ? null : sub.optJSONObject("thread_spawn");
    }

    /**
     * From a thread/read result: the session that spawned it, or "" for one the broker can address
     * directly.
     */
    static String parent(JSONObject read) {
        JSONObject s = spawn(read);
        return s == null ? "" : s.optString("parent_thread_id");
    }

    /**
     * From a thread/read result: the path its parent addresses it by ("/root/inspect_settings"), or
     * "".
     */
    static String path(JSONObject read) {
        JSONObject s = spawn(read);
        return s == null ? "" : s.optString("agent_path");
    }

    /**
     * What the parent is told when the user gives its sub-agent's screen back. The sub-agent never
     * needs a screenshot before waking; it observes its own screen afterwards. followup_task is named
     * because send_message only leaves a note: the sub-agent does not start a turn, and keeps its
     * screen and Apps meanwhile.
     */
    static String relayText(String path, String session, String taskTitle) {
        return "The user gave back the screen of your sub-agent "
                + path
                + " (session "
                + session
                + ", task \""
                + taskTitle
                + "\"). Wake that same sub-agent with followup_task in either case.\n"
                + "- Task still wanted: have it look at its screen with observe_background before carrying on. A successful handback leaves the workspace open.\n"
                + "- Task called off by the user: tell it to release all held Apps with release_apps, then end its turn.";
    }

    /**
     * What the parent is told when the user closes its sub-agent's screen. Nothing else tells it:
     * its wait_agent does not return for a turn the broker interrupts, so it would keep waiting,
     * and keep waking a sub-agent whose turns are all refused.
     */
    static String closedText(String path, String taskTitle) {
        return "The user closed the screen of your sub-agent "
                + path
                + " (task \""
                + taskTitle
                + "\"), which stops that sub-agent for good: any turn it starts is refused. Stop"
                + " waiting for it and carry on with the rest; redo its task only if the user"
                + " asks, and tell the user it was stopped.";
    }

    /**
     * Every session spawned under this one, at any depth. archived: true lists only archived ones.
     */
    static List<String> descendants(Rpc rpc, String session, boolean archived) throws Exception {
        return listed(rpc, session, archived);
    }

    /** Complete ancestry before a phone grant; an unreadable or cyclic family cannot acquire. */
    static List<String> ancestors(Rpc rpc, String session) throws Exception {
        List<String> out = new ArrayList<>();
        String current = session;
        for (int depth = 0; depth < 64; depth++) {
            if (out.contains(current)) throw new IOException("SESSION_FAMILY_INCOMPLETE");
            out.add(current);
            JSONObject read = rpc.call("thread/read", new JSONObject().put("threadId", current));
            if (read.optJSONObject("thread") == null) throw new IOException("SESSION_FAMILY_INCOMPLETE");
            current = parent(read);
            if (current.isEmpty()) return List.copyOf(out);
        }
        throw new IOException("SESSION_FAMILY_INCOMPLETE");
    }

    /** Reconcile an unanswered removal: absence is evidence only after complete list queries. */
    static boolean removed(Rpc rpc, String session, boolean archived) throws Exception {
        SessionRemovals.State state = removalState(rpc, session);
        return archived ? state == SessionRemovals.State.ARCHIVED : state == SessionRemovals.State.ABSENT;
    }

    static SessionRemovals.State removalState(Rpc rpc, String session) throws Exception {
        boolean active = listed(rpc, null, false).contains(session);
        boolean archived = listed(rpc, null, true).contains(session);
        if (active && archived) throw new IOException("SESSION_STATE_UNCONFIRMED");
        if (!active && !archived) {
            // Omit limit: the pinned protocol returns all loaded ids by default.
            // Fresh persistent sessions are loaded before their first history record exists.
            JSONObject loaded = rpc.call("thread/loaded/list", new JSONObject());
            if (!loaded.has("nextCursor") || !loaded.isNull("nextCursor") && !loaded.optString("nextCursor").isEmpty())
                throw new IOException("SESSION_FAMILY_INCOMPLETE");
            JSONArray ids = loaded.getJSONArray("data");
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                if (id.isEmpty()) throw new IOException("SESSION_FAMILY_INCOMPLETE");
                if (id.equals(session)) active = true;
            }
        }
        return active ? SessionRemovals.State.ACTIVE
                : archived ? SessionRemovals.State.ARCHIVED : SessionRemovals.State.ABSENT;
    }

    private static List<String> listed(Rpc rpc, String ancestor, boolean archived) throws Exception {
        List<String> out = new ArrayList<>();
        String cursor = null;
        Set<String> cursors = new HashSet<>();
        for (int page = 0; page < 20; page++) {
            // The pinned native protocol defaults to interactive sources only. An empty filter
            // has the same default, so enumerate every kind to include spawned sub-agents too.
            JSONObject params = new JSONObject().put("limit", 50)
                    .put("modelProviders", new JSONArray())
                    .put("sourceKinds", new JSONArray(List.of("cli", "vscode", "exec", "appServer",
                            "subAgent", "subAgentReview", "subAgentCompact", "subAgentThreadSpawn",
                            "subAgentOther", "unknown")));
            if (ancestor != null) params.put("ancestorThreadId", ancestor);
            if (archived) params.put("archived", true);
            if (cursor != null) params.put("cursor", cursor);
            JSONObject r = rpc.call("thread/list", params);
            JSONArray data = r.getJSONArray("data");
            if (!r.has("nextCursor")) throw new IOException("SESSION_FAMILY_INCOMPLETE");
            for (int i = 0; i < data.length(); i++) {
                String id = data.getJSONObject(i).optString("id");
                if (id.isEmpty()) throw new IOException("SESSION_FAMILY_INCOMPLETE");
                if (!out.contains(id)) out.add(id);
            }
            // nextCursor is JSON null on the last page; optString would turn that into "null".
            cursor = r.isNull("nextCursor") ? null : r.optString("nextCursor");
            if (cursor == null || cursor.isEmpty()) return out;
            if (data.length() == 0 || !cursors.add(cursor)) throw new IOException("SESSION_FAMILY_INCOMPLETE");
        }
        throw new IOException("SESSION_FAMILY_INCOMPLETE");
    }

    /**
     * The session was archived or deleted: its descendants (archived with it or not) are gone too.
     */
    static Set<String> gone(Rpc rpc, String session) throws Exception {
        Set<String> out = new LinkedHashSet<>(descendants(rpc, session, true));
        out.addAll(descendants(rpc, session, false));
        return out;
    }
}
