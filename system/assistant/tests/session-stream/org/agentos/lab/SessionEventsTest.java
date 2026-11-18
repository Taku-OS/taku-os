package org.agentos.lab;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SessionEventsTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static JSONObject params(String text) throws Exception {
        return new JSONObject().put("turnId", "turn").put("itemId", "message").put("delta", text);
    }
    private static JSONObject snapshot() throws Exception {
        return new JSONObject().put("id", "thread").put("turns", new JSONArray());
    }
    private static JSONObject next(SessionEvents.Subscription stream) throws Exception {
        String line = stream.next(1); if (line == null) throw new AssertionError("missing event"); return new JSONObject(line);
    }

    public static void main(String[] args) throws Exception {
        SessionEvents relay = new SessionEvents();
        relay.publish("thread", "item/agentMessage/delta", params("before"));
        JSONObject view = snapshot();
        SessionEvents.Subscription stream = relay.subscribe("thread", view, "", -1);
        view.put("laterMutation", true);
        JSONObject first = next(stream);
        check(first.getString("method").equals("session/snapshot") && first.getLong("seq") == 1);
        check(!first.getJSONObject("params").getJSONObject("thread").has("laterMutation"));
        String epoch = first.getString("brokerEpoch");
        relay.publish("thread", "item/agentMessage/delta", params("你好🙂"));
        check(next(stream).getJSONObject("params").getString("delta").equals("你好🙂"));
        stream.close();
        relay.publish("thread", "item/completed", new JSONObject().put("turnId", "turn")
                .put("item", new JSONObject().put("id", "message").put("type", "agentMessage").put("text", "corrected")));
        stream = relay.subscribe("thread", snapshot(), epoch, 1);
        check(next(stream).getString("method").equals("session/ready"));
        check(next(stream).getLong("seq") == 2);
        check(next(stream).getJSONObject("params").getJSONObject("item").getString("text").equals("corrected"));
        stream.close();
        stream = relay.subscribe("thread", snapshot(), epoch, 3);
        check(next(stream).getLong("seq") == 3);
        relay.publish("other-thread", "item/agentMessage/delta", params("private"));
        check(stream.next(1) == null);
        stream.close();

        for (int i = 0; i <= SessionEvents.MAX_HISTORY_EVENTS; i++) relay.publish("thread", "item/agentMessage/delta", params("x"));
        stream = relay.subscribe("thread", snapshot(), epoch, 1);
        check(next(stream).getString("method").equals("session/snapshot"));
        stream.close();
        stream = relay.subscribe("thread", snapshot(), "", -1);
        next(stream);
        for (int i = 0; i <= SessionEvents.MAX_QUEUE_EVENTS; i++) relay.publish("thread", "item/agentMessage/delta", params("x"));
        check(next(stream).getString("method").equals("session/resync"));
        check(stream.ended()); stream.close();

        stream = relay.subscribe("thread", snapshot(), "", -1);
        next(stream); relay.reset();
        check(next(stream).getString("method").equals("session/resync")); stream.close();
        stream = relay.subscribe("thread", snapshot(), epoch, 3);
        JSONObject restarted = next(stream);
        check(restarted.getString("method").equals("session/snapshot"));
        check(!restarted.getString("brokerEpoch").equals(epoch) && restarted.getLong("seq") == 0);
        stream.close();

        List<SessionEvents.Subscription> full = new ArrayList<>();
        for (int i = 0; i < SessionEvents.MAX_SUBSCRIBERS; i++) full.add(relay.subscribe("thread", snapshot(), "", -1));
        boolean rejected = false;
        try { relay.subscribe("thread", snapshot(), "", -1); } catch (java.io.IOException expected) { rejected = true; }
        check(rejected); for (SessionEvents.Subscription subscription : full) subscription.close();

        // Snapshot and listener registration share the reducer lock, while publishers race with subscribe.
        SessionEvents raced = new SessionEvents();
        Object reducer = new Object(); JSONObject accumulated = snapshot().put("raw", "");
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) synchronized (reducer) {
                    accumulated.put("raw", accumulated.getString("raw") + "中");
                    raced.publish("thread", "item/agentMessage/delta", params("中"));
                }
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        writer.start();
        synchronized (reducer) { stream = raced.subscribe("thread", accumulated, "", -1); }
        JSONObject initial = next(stream);
        String raw = initial.getJSONObject("params").getJSONObject("thread").getString("raw");
        long seq = initial.getLong("seq"); writer.join();
        while (seq < 100) { JSONObject delta = next(stream); check(delta.getLong("seq") == ++seq); raw += delta.getJSONObject("params").getString("delta"); }
        check(raw.equals(accumulated.getString("raw")) && raw.length() == 100); stream.close();
        System.out.println("SessionEventsTest: " + checks + " assertions passed");
    }
}
