package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Verifies that a poll is based on an unmodified completed primary reply. */
final class DiningPollSendSource {
    private static final Set<String> SOURCE_FIELDS =
            Set.of("kind", "replyId", "turnId", "revision", "contractHash");

    private DiningPollSendSource() {}

    static JSONObject readRequest(JSONObject registration) throws Exception {
        JSONObject source = registration.getJSONObject("sourceReply");
        Set<String> fields = new HashSet<>();
        for (Iterator<String> keys = source.keys(); keys.hasNext(); ) fields.add(keys.next());
        if (!fields.equals(SOURCE_FIELDS)
                || !"primary".equals(source.opt("kind"))
                || !(source.opt("contractHash") instanceof String hash)
                || !hash.matches("[a-f0-9]{64}"))
            throw new IOException("POLL_SOURCE_REPLY_INVALID");
        revision(source);
        return new JSONObject()
                .put("op", "mainReply")
                .put("action", "read")
                .put("threadId", registration.getString("sourceThreadId"))
                .put("replyId", DiningPollSendState.text(source, "replyId", 160))
                .put("turnId", DiningPollSendState.text(source, "turnId", 160));
    }

    static void verify(JSONObject registration, JSONObject result) throws Exception {
        JSONObject source = registration.getJSONObject("sourceReply");
        JSONObject reply = result.optJSONObject("reply");
        if (!Boolean.TRUE.equals(result.opt("ok")) || reply == null)
            throw new IOException("POLL_SOURCE_REPLY_UNAVAILABLE");
        JSONObject provenance = reply.getJSONObject("provenance");
        if (!"taku.main-reply/1".equals(reply.opt("schema"))
                || !"primary-assistant-reply".equals(provenance.opt("kind"))
                || !registration.getString("sourceThreadId").equals(provenance.opt("threadId"))
                || !source.getString("turnId").equals(provenance.opt("turnId"))
                || !source.getString("replyId").equals(reply.opt("id"))
                || !source.getString("contractHash").equals(provenance.opt("contractHash"))
                || revision(source) != revision(reply)
                || !"completed".equals(reply.getJSONObject("lifecycle").opt("phase")))
            throw new IOException("POLL_SOURCE_REPLY_CHANGED");
        JSONObject card = reply.getJSONObject("card").getJSONObject("request");
        if (!"card".equals(card.opt("mode")) || !reply.getString("key").equals(card.opt("key")))
            throw new IOException("POLL_SOURCE_REPLY_CHANGED");
        JSONArray blocks = card.getJSONArray("blocks");
        JSONArray options = registration.getJSONArray("options");
        int matchingCollections = 0;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if ("collection".equals(block.opt("kind"))
                    && matches(block.optJSONArray("items"), options)) matchingCollections++;
        }
        if (matchingCollections != 1) throw new IOException("POLL_SOURCE_OPTIONS_CHANGED");
    }

    private static boolean matches(JSONArray rows, JSONArray options) throws Exception {
        if (rows == null || rows.length() != options.length()) return false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null || !options.getString(i).equals(item.opt("label"))) return false;
        }
        return true;
    }

    private static long revision(JSONObject source) throws IOException {
        if (!(source.opt("revision") instanceof Number value)
                || value.longValue() < 1
                || value.longValue() > 9_007_199_254_740_991L
                || value.doubleValue() != value.longValue())
            throw new IOException("POLL_SOURCE_REPLY_INVALID");
        return value.longValue();
    }
}
