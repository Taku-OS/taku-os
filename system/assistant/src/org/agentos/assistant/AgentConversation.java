package org.agentos.assistant;

import org.json.*;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

/** A presentation of native records. Identity, ordering and status come from Codex. */
final class AgentConversation {
    private static final java.util.regex.Pattern PLUGIN_NOTE = java.util.regex.Pattern.compile(
            "The user attached the plugin \".*\" \\(id: [A-Za-z0-9_.@:-]{1,128}\\) to this message\\."
                    + "( Prefer this plugin's tools for this request: .*\\.)?");
    enum Kind { USER, ASSISTANT, TOOL, PLAN }

    static final class Entry {
        final Kind kind;
        final String id, turnId, type, text;
        final JSONObject item;
        final List<String> attachments = new ArrayList<>();
        final Set<Integer> workspaceObservations = new HashSet<>();

        Entry(Kind kind, JSONObject item, String turnId, String text) {
            this.kind = kind;
            this.item = item;
            this.id = item.optString("id");
            this.type = item.optString("type");
            this.turnId = turnId;
            this.text = text;
        }

        String key() { return turnId + "/" + id; }
    }

    final String id, title;
    final List<Entry> entries;
    final List<Turn> turns;
    final boolean running;
    final String historyNotice;

    static final class Turn {
        final String id, status;
        final List<Entry> entries = new ArrayList<>();

        Turn(JSONObject nativeTurn) {
            id = nativeTurn.optString("id");
            status = nativeTurn.optString("status");
        }
    }

    private AgentConversation(String id, String title, List<Entry> entries, List<Turn> turns,
            boolean running, String historyNotice) {
        this.id = id;
        this.title = title;
        this.entries = entries;
        this.turns = turns;
        this.running = running;
        this.historyNotice = historyNotice;
    }

    /** Local task identity gates stale projection fallback; native live state remains authoritative. */
    String activeTask(JSONObject snapshot) {
        JSONObject task = snapshot.optJSONObject("task"), request = snapshot.optJSONObject("request");
        if (task == null || request == null || !task.optBoolean("published")
                || !id.equals(request.optString("conversationId"))
                || task.optString("id").isEmpty() || !task.optString("id").equals(request.optString("id"))) return "";
        String state = task.optString("state");
        return state.equals("running") || state.equals("queued") ? task.optString("id") : "";
    }

    String activeTurn(JSONObject snapshot) {
        JSONObject nativeState = snapshot.optJSONObject("native");
        if (nativeState != null && !nativeState.optString("activeTurnId").isEmpty())
            return nativeState.optString("activeTurnId");
        JSONObject runtime = snapshot.optJSONObject("runtime");
        return running && !activeTask(snapshot).isEmpty() && runtime != null
                && runtime.optBoolean("turnStarted") && id.equals(runtime.optString("threadId"))
                        ? runtime.optString("turnId") : "";
    }

    static String title(JSONObject thread) {
        String name = thread.optString("name", "").trim();
        if (name.isEmpty() || name.equals("null")) name = thread.optString("preview", "").trim();
        return name.isEmpty() ? "New conversation" : name;
    }

    static AgentConversation read(JSONObject thread) throws JSONException {
        List<Entry> entries = new ArrayList<>();
        List<Turn> nativeTurns = new ArrayList<>();
        boolean running = false;
        JSONArray turns = thread.optJSONArray("turns");
        if (turns != null) for (int i = 0; i < turns.length(); i++) {
            JSONObject turn = turns.getJSONObject(i);
            Turn projected = new Turn(turn);
            nativeTurns.add(projected);
            int firstEntry = entries.size();
            running |= "inProgress".equals(turn.optString("status"));
            JSONArray items = turn.optJSONArray("items");
            if (items == null) continue;
            Map<Integer, JSONObject> primaryReplies = primaryReplies(turn);
            int anchorOrdinal = -1;
            JSONObject primary = null;
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                String turnId = turn.optString("id");
                if ("userMessage".equals(item.optString("type"))) primary = primaryReplies.get(++anchorOrdinal);
                switch (item.optString("type")) {
                    case "userMessage":
                        StringBuilder text = new StringBuilder();
                        Entry user = new Entry(Kind.USER, item, turnId, "");
                        JSONArray content = item.optJSONArray("content");
                        if (content != null) for (int k = 0; k < content.length(); k++) {
                            JSONObject input = content.optJSONObject(k);
                            if (input == null) continue;
                            String type = input.optString("type");
                            if ("text".equals(type)) {
                                String value = input.optString("text");
                                // Existing broker encodes non-image attachments as this exact input text.
                                String prefix = value.startsWith("用户选择的附件：") ? "用户选择的附件：" : "User-selected attachment: ";
                                String[] file = value.split(prefix.equals("用户选择的附件：") ? "\\n本机路径：" : "\\nLocal path: ", -1);
                                if (value.startsWith(prefix) && file.length == 2
                                        && file[1].startsWith("/data/user/0/org.agentos.assistant/files/composer-attachments/")) {
                                    user.attachments.add(file[0].substring(prefix.length()));
                                } else if (PLUGIN_NOTE.matcher(value).matches()) {
                                    // ComposerInput.appendPlugins: the composer's plugin chip, not words the user typed.
                                } else {
                                    if (text.length() > 0) text.append('\n');
                                    text.append(value);
                                }
                            } else if ("localImage".equals(type)) {
                                String path = input.optString("path");
                                String name = path.isEmpty() ? "" : new java.io.File(path).getName();
                                if (workspaceObservation(path)) user.workspaceObservations.add(user.attachments.size());
                                user.attachments.add(name.isEmpty() || name.matches("[a-fA-F0-9-]{36}") ? "Image" : name);
                            } else if ("image".equals(type)) {
                                user.attachments.add("Image");
                            }
                        }
                        Entry message = new Entry(Kind.USER, item, turnId, text.toString());
                        message.attachments.addAll(user.attachments);
                        message.workspaceObservations.addAll(user.workspaceObservations);
                        if (!message.text.isEmpty() || !message.attachments.isEmpty()) entries.add(message);
                        break;
                    case "agentMessage":
                        String replyId = item.optString("replyId");
                        boolean primaryItem = !replyId.isEmpty() && (primary == null || replyId.equals(primary.optString("id")));
                        String answer = primaryItem && primary != null ? primary.optString("readableText") : item.optString("text");
                        if (!answer.isEmpty()) {
                            String phase = primaryItem ? "final_answer" : primary != null ? "commentary" : item.optString("phase");
                            JSONObject visible = item;
                            if (!phase.equals(item.optString("phase")) || !answer.equals(item.optString("text")))
                                visible = new JSONObject(item.toString()).put("phase", phase).put("text", answer);
                            entries.add(new Entry(Kind.ASSISTANT, visible, turnId, answer));
                        }
                        break;
                    case "plan":
                        if (!item.optString("text").isEmpty())
                            entries.add(new Entry(Kind.PLAN, item, turnId, item.optString("text")));
                        break;
                    case "commandExecution":
                    case "fileChange":
                    case "mcpToolCall":
                    case "dynamicToolCall":
                    case "webSearch":
                    case "contextCompaction":
                    case "collabAgentToolCall":
                    case "collabToolCall":
                    case "enteredReviewMode":
                    case "exitedReviewMode":
                        entries.add(new Entry(Kind.TOOL, item, turnId, ""));
                        break;
                    default:
                        // Private reasoning and unrecognized/auth records are not conversation text.
                }
            }
            projected.entries.addAll(entries.subList(firstEntry, entries.size()));
        }
        int omittedTurns = Math.max(0, thread.optInt("historyOmittedTurns"));
        int omittedItems = Math.max(0, thread.optInt("historyOmittedItems"));
        int omittedText = Math.max(0, thread.optInt("historyOmittedText"));
        int omittedPayloads = Math.max(0, thread.optInt("historyOmittedPayloads"));
        String historyNotice = "";
        boolean unknownOmissions = thread.optBoolean("historyOmissionCountUnknown");
        if (unknownOmissions)
            historyNotice = "History is not fully loaded; some content is shown only as a summary. The original conversation is preserved.";
        else if (omittedTurns + omittedItems + omittedText + omittedPayloads > 0) {
            List<String> folded = new ArrayList<>();
            if (omittedTurns > 0) folded.add("Earlier " + omittedTurns + " turns");
            if (omittedItems > 0) folded.add(omittedItems + " records");
            if (omittedText > 0) folded.add(omittedText + " long text sections");
            if (omittedPayloads > 0) folded.add(omittedPayloads + " images or large objects");
            historyNotice = "Collapsed to keep rendering responsive: " + String.join("、", folded)
                    + "; complete native history is preserved, and you can continue sending messages.";
        }
        if (thread.optBoolean("historyNotLoaded"))
            historyNotice = "Only locally saved records are shown. History is not fully loaded; the original conversation is preserved."
                    + (unknownOmissions || historyNotice.isEmpty() ? "" : " " + historyNotice);
        return new AgentConversation(thread.getString("id"), title(thread), entries, nativeTurns,
                running, historyNotice);
    }

    static String lastReply(JSONObject thread) {
        JSONArray turns = thread.optJSONArray("turns");
        JSONObject turn = turns == null || turns.length() == 0 ? null : turns.optJSONObject(turns.length() - 1);
        JSONArray items = turn == null ? null : turn.optJSONArray("items");
        int anchorOrdinal = -1, lastUser = -1;
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "userMessage".equals(item.optString("type"))) { anchorOrdinal++; lastUser = i; }
        }
        JSONObject primary = turn == null ? null : primaryReplies(turn).get(anchorOrdinal);
        if (primary != null) return primary.optString("readableText");
        if (items != null) for (int i = items.length() - 1; i > lastUser; i--) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "agentMessage".equals(item.optString("type")) && !item.optString("text").isEmpty()) return item.optString("text");
        }
        return "";
    }

    private static Map<Integer, JSONObject> primaryReplies(JSONObject turn) {
        JSONObject replies = turn.optJSONObject("mainReplies");
        if (replies == null || replies.length() == 0) return java.util.Collections.emptyMap();
        Map<Integer, JSONObject> result = new HashMap<>();
        java.util.Iterator<String> ids = replies.keys();
        while (ids.hasNext()) {
            JSONObject reply = replies.optJSONObject(ids.next());
            JSONObject provenance = reply == null ? null : reply.optJSONObject("provenance");
            if (provenance != null && provenance.has("anchorOrdinal")) result.put(provenance.optInt("anchorOrdinal", -1), reply);
        }
        return result;
    }

    /** Presentation-only provenance; user-selected files keep their actual names. */
    static boolean workspaceObservation(String path) {
        return path.matches("/data/(?:user/0|data)/org\\.agentos\\.assistant/files/runtime/workspace-executions/"
                + "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/observation\\.png");
    }
}
