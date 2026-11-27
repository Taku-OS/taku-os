package org.agentos.lab;

import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sends Taku conversation turns to Taku Memory's Broker-only "chat" method, which keeps them only while the user has
 * "Remember Taku conversations" (and capture) on and drops what memory itself handed to Taku. Only primary chat turns are
 * sent: never organizer threads or structured tasks. The user's message is the turn's first text item (attachments,
 * plugins and panel context follow it); Taku's reply is the readable text of the turn's final message, sent once the turn
 * completes. Best effort on its own thread: a failure never touches the conversation.
 */
final class TakuChatMemory {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "taku-chat-memory");
        t.setDaemon(true);
        return t;
    });
    /** Thread -> the user's message of the turn just requested, until the runtime names the turn. */
    private static final Map<String, String> pending = new ConcurrentHashMap<>();
    /** Thread and turn of turns whose message was sent -> Taku's latest message in that turn. */
    private static final Map<String, String> replies = new ConcurrentHashMap<>();
    private static final int MAX_TEXT = 8000;

    private TakuChatMemory() {}

    /** Before a native turn/start is sent: remember the user's message when this is a primary chat turn. */
    static void starting(String method, JSONObject params) {
        if (!"turn/start".equals(method) || params == null || params.has("outputSchema")) return;
        String thread = params.optString("threadId");
        if (thread.isEmpty() || MemoryGen.owns(thread)) return;
        JSONArray input = params.optJSONArray("input");
        for (int i = 0; input != null && i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if (item == null || !"text".equals(item.optString("type"))) continue;
            String text = item.optString("text").trim();
            if (!text.isEmpty()) pending.put(thread, text);
            return;
        }
    }

    /** Every runtime event: turn/started sends the user's message, turn/completed sends Taku's final reply. */
    static void event(JSONObject event) {
        String method = event.optString("method");
        JSONObject p = event.optJSONObject("params");
        if (p == null) return;
        String thread = p.optString("threadId");
        if (method.equals("turn/started")) {
            JSONObject turn = p.optJSONObject("turn");
            String text = pending.remove(thread);
            if (turn == null || text == null) return;
            replies.put(thread + "\n" + turn.optString("id"), "");
            send(thread, turn.optString("id"), "user", text);
        } else if (method.equals("item/completed")) {
            JSONObject item = p.optJSONObject("item");
            String key = thread + "\n" + p.optString("turnId");
            if (item != null && "agentMessage".equals(item.optString("type")) && replies.containsKey(key))
                replies.put(key, item.optString("text"));
        } else if (method.equals("turn/completed")) {
            JSONObject turn = p.optJSONObject("turn");
            if (turn == null) return;
            String text = replies.remove(thread + "\n" + turn.optString("id"));
            if (text == null || text.isEmpty() || !"completed".equals(turn.optString("status"))) return;
            String readable = readable(thread, turn.optString("id"), text);
            if (!readable.isBlank()) send(thread, turn.optString("id"), "assistant", readable);
        }
    }

    /**
     * What the user saw: a primary reply is framed records rendered as a card, so the card's readable text is kept; a plain
     * reply is kept as written. A reply that does not parse is not kept rather than stored as protocol text.
     */
    static String readable(String thread, String turn, String text) {
        try {
            MainReplyStream stream = new MainReplyStream(HarnessCatalog.replyContract(), thread, turn, 0, "");
            stream.push(text);
            stream.finish("completed");
            if (stream.isPlain()) return text;
            JSONObject reply = stream.snapshot();
            if (reply != null && "completed".equals(reply.getJSONObject("lifecycle").optString("phase"))) return reply.optString("readableText");
        } catch (Exception unreadable) {
        }
        return "";
    }

    private static void send(String thread, String turn, String speaker, String text) {
        int cut = text.length() > MAX_TEXT ? (Character.isHighSurrogate(text.charAt(MAX_TEXT - 1)) ? MAX_TEXT - 1 : MAX_TEXT) : text.length();
        String bounded = text.substring(0, cut);
        WORKER.execute(() -> {
            try {
                MemoryBridge.provider("chat", new JSONObject().put("thread", thread).put("turn", turn).put("speaker", speaker).put("text", bounded));
            } catch (Exception notKept) {
            }
        });
    }
}
