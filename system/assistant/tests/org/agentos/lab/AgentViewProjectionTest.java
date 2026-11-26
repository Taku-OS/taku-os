package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class AgentViewProjectionTest {
    private static final String THREAD = "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1";

    public static void main(String[] args) throws Exception {
        oversizedHistoryIsBoundedWithoutChangingCanonicalInput();
        smallHistoryRemainsComplete();
        malformedHistoryFailsClearly();
        metadataRefreshRetainsKnownHistory();
        coldMetadataDoesNotPretendHistoryWasLoaded();
        pagedHistoryMergesNativeIdsWithoutReplacingKnownItems();
        metadataIdentityMismatchFailsClearly();
        omissionCountsDoNotGrowWhenReplayingBoundedPages();
        reasoningSummaryIsKeptWithinItsOwnBudget();
        System.out.println("PASS AgentViewProjectionTest: 9 cases");
    }

    private static void reasoningSummaryIsKeptWithinItsOwnBudget() throws Exception {
        String thought = repeat("先比较两条路线。Weigh both routes. ", 400), answer = repeat("建议走第一条。Take the first. ", 400);
        check(thought.length() > 8_000 && answer.length() < 12_000, "fixture exceeds one summary part but not one answer");
        JSONArray turns = new JSONArray();
        for (int i = 0; i < 5; i++) turns.put(new JSONObject().put("id", "turn-" + i).put("status", "completed").put("items", new JSONArray()
                .put(new JSONObject().put("id", "thought-" + i).put("type", "reasoning").put("content", new JSONArray().put("raw private content"))
                        .put("summary", new JSONArray().put("**标题 Title**").put(thought)).put("startedAtMs", 1_000L).put("durationMs", 21_500L)
                        .put("encrypted_content", "sealed"))
                .put(new JSONObject().put("id", "answer-" + i).put("type", "agentMessage").put("text", answer))));
        JSONObject projection = AgentViewProjection.project(new JSONObject().put("id", THREAD).put("turns", turns));
        int kept = 0;
        for (int i = 0; i < 5; i++) {
            JSONArray items = projection.getJSONArray("turns").getJSONObject(i).getJSONArray("items");
            JSONObject item = items.getJSONObject(0);
            check(items.getJSONObject(1).getString("text").equals(answer), "reasoning never folds the answer beside it");
            check(item.getString("type").equals("reasoning") && item.getLong("durationMs") == 21_500L && item.getLong("startedAtMs") == 1_000L,
                    "a reasoning item keeps its place and timing");
            check(!item.has("encrypted_content"), "sealed reasoning is not a display field");
            JSONArray summary = item.optJSONArray("summary");
            if (summary == null) continue;
            for (int j = 0; j < summary.length(); j++) {
                check(summary.getString(j).length() <= 8_000, "one summary part is bounded");
                kept += summary.getString(j).length();
            }
        }
        check(kept > 8_000 && kept <= 24_000, "all reasoning summaries share one bounded budget");
        JSONArray newest = projection.getJSONArray("turns").getJSONObject(4).getJSONArray("items").getJSONObject(0).getJSONArray("summary");
        check(newest.getString(0).equals("**标题 Title**") && newest.getString(1).startsWith("先比较两条路线。") && newest.getString(1).endsWith("Weigh both routes. "),
                "the newest summary keeps its parts, folded in the middle");
        check(!projection.getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(0).has("summary"),
                "the oldest summary gives way first, without a made-up empty one");
        check(projection.optInt("historyOmittedText") == 0, "bounding reasoning is not reported as omitted conversation text");
        JSONObject again = AgentViewProjection.project(projection);
        check(again.getJSONArray("turns").toString().equals(projection.getJSONArray("turns").toString()), "projecting a projection changes nothing");
        JSONObject empty = AgentViewProjection.project(new JSONObject().put("id", THREAD).put("turns", new JSONArray().put(new JSONObject()
                .put("id", "turn").put("items", new JSONArray().put(new JSONObject().put("id", "thought").put("type", "reasoning").put("summary", new JSONArray()))))));
        check(empty.getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(0).getJSONArray("summary").length() == 0,
                "a known empty summary stays an empty summary");
    }

    private static void oversizedHistoryIsBoundedWithoutChangingCanonicalInput() throws Exception {
        String largeText = repeat("半马 Keep 检索内容 abcdefghijklmnop ", 30_000);
        String imageData = repeat("QUJDREVGR0hJSktMTU5PUA==", 45_000);
        JSONObject image = new JSONObject().put("type", "image").put("data", imageData);
        JSONObject latest = new JSONObject().put("id", "turn-new")
                .put("status", "completed")
                .put("items", new JSONArray()
                        .put(new JSONObject().put("id", "user-new").put("type", "userMessage")
                                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "请保留这条最新消息"))))
                        .put(new JSONObject().put("id", "image-result").put("type", "mcpToolCall")
                                .put("result", new JSONObject().put("content", new JSONArray().put(image))))
                        .put(new JSONObject().put("id", "answer-new").put("type", "agentMessage")
                                .put("text", "最近一轮的真实回复仍然可见")));
        JSONObject source = new JSONObject().put("id", THREAD).put("name", "大体积会话")
                .put("turns", new JSONArray()
                        .put(new JSONObject().put("id", "turn-old").put("status", "completed")
                                .put("items", new JSONArray().put(new JSONObject().put("id", "answer-old")
                                        .put("type", "agentMessage").put("text", largeText))))
                        .put(latest));
        int originalLength = source.toString().getBytes(StandardCharsets.UTF_8).length;
        check(originalLength > 2 * 1024 * 1024, "fixture must exceed 2 MiB");

        JSONObject projection = AgentViewProjection.project(source);
        String serialized = projection.toString();
        check(serialized.getBytes(StandardCharsets.UTF_8).length <= AgentViewProjection.MAX_BYTES,
                "display projection must stay below its hard byte limit");
        check(serialized.contains("最近一轮的真实回复仍然可见"), "latest reply remains visible");
        check(serialized.contains("请保留这条最新消息"), "latest user message remains visible");
        check(!serialized.contains("QUJDREVGR0hJSktMTU5PUA=="), "image payload is removed from projection");
        check(projection.optInt("historyOmittedText") > 0, "long text is reported as folded");
        check(projection.optInt("historyOmittedPayloads") > 0, "image payload omission is reported");
        check(source.toString().getBytes(StandardCharsets.UTF_8).length == originalLength,
                "projection must not mutate the canonical thread object");
        check(source.getJSONArray("turns").getJSONObject(0).getJSONArray("items")
                        .getJSONObject(0).getString("text").equals(largeText),
                "full source text remains intact for the native session");
        JSONObject refreshed = AgentViewProjection.project(projection);
        check(refreshed.optInt("historyOmittedText") == projection.optInt("historyOmittedText")
                        && refreshed.optInt("historyOmittedPayloads") == projection.optInt("historyOmittedPayloads"),
                "incremental projection refresh keeps the original omission counts stable");
    }

    private static void smallHistoryRemainsComplete() throws Exception {
        JSONObject source = new JSONObject().put("id", THREAD).put("name", "旧小会话")
                .put("turns", new JSONArray()
                        .put(new JSONObject().put("id", "turn-1").put("status", "completed")
                                .put("items", new JSONArray()
                                        .put(new JSONObject().put("id", "user-1").put("type", "userMessage")
                                                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "你好"))))
                                        .put(new JSONObject().put("id", "assistant-1").put("type", "agentMessage")
                                                .put("phase", "final").put("text", "你好，原会话已恢复。")))));
        JSONObject projection = AgentViewProjection.project(source);
        JSONObject turn = projection.getJSONArray("turns").getJSONObject(0);
        check(turn.getJSONArray("items").length() == 2, "all items in small history remain");
        check(turn.getJSONArray("items").getJSONObject(1).getString("text").equals("你好，原会话已恢复。"),
                "small history text is unchanged");
        check(projection.optInt("historyOmittedTurns") == 0 && projection.optInt("historyOmittedItems") == 0
                        && projection.optInt("historyOmittedText") == 0 && projection.optInt("historyOmittedPayloads") == 0,
                "small history is not marked as truncated");
    }

    private static void malformedHistoryFailsClearly() throws Exception {
        try {
            AgentViewProjection.project(new JSONObject().put("id", THREAD).put("turns", "not-an-array"));
            throw new AssertionError("malformed turns should fail");
        } catch (IOException expected) {
            check(expected.getMessage().contains("Invalid conversation history structure"), "malformed history has a specific diagnostic");
        }
        try {
            AgentViewProjection.project(new JSONObject().put("turns", new JSONArray()));
            throw new AssertionError("missing thread identity should fail");
        } catch (IOException expected) {
            check(expected.getMessage().contains("valid identifier"), "invalid identity has a specific diagnostic");
        }
        JSONObject brokenEntry = AgentViewProjection.project(new JSONObject().put("id", THREAD)
                .put("turns", new JSONArray().put("truncated turn record")));
        check(brokenEntry.optInt("historyOmittedTurns") == 1
                        && brokenEntry.getJSONArray("turns").length() == 0,
                "a damaged turn is reported and skipped without losing the rest of the session");
    }

    private static void metadataRefreshRetainsKnownHistory() throws Exception {
        JSONObject previous = AgentViewProjection.project(new JSONObject().put("id", THREAD)
                .put("name", "原会话").put("turns", new JSONArray().put(turn("turn-1", "原回复"))));
        previous.put("historyOmittedPayloads", 69);
        String before = previous.toString();
        String imageData = repeat("QUJDREVGR0hJSktMTU5PUA==", 400_000);
        check(imageData.length() > 8 * 1024 * 1024, "fixture exceeds the transport safety limit");
        JSONObject metadata = new JSONObject().put("id", THREAD).put("name", "更新后的标题")
                .put("status", new JSONObject().put("type", "idle"))
                .put("turns", new JSONArray().put(new JSONObject().put("image", imageData)));
        JSONObject merged = AgentViewProjection.mergeMetadata(previous, metadata);
        check(merged.getJSONArray("turns").toString().equals(previous.getJSONArray("turns").toString()),
                "metadata refresh retains all known turns and items even if it carries a turns field");
        check(merged.getString("name").equals("更新后的标题"), "fresh metadata is updated");
        check(merged.optInt("historyOmittedPayloads") == 69, "omission counters are retained");
        check(merged.toString().getBytes(StandardCharsets.UTF_8).length <= AgentViewProjection.MAX_BYTES,
                "metadata refresh does not copy giant image history into the projection");
        JSONObject empty = AgentViewProjection.mergeMetadata(merged,
                new JSONObject().put("id", THREAD).put("turns", new JSONArray()));
        check(empty.getJSONArray("turns").toString().equals(merged.getJSONArray("turns").toString()),
                "an explicit empty metadata turns array cannot erase known history");
        check(previous.toString().equals(before) && metadata.getJSONArray("turns").getJSONObject(0)
                        .getString("image").length() == imageData.length(),
                "metadata merging does not mutate either source");
    }

    private static void coldMetadataDoesNotPretendHistoryWasLoaded() throws Exception {
        JSONObject cold = AgentViewProjection.mergeMetadata(null,
                new JSONObject().put("id", THREAD).put("turns", new JSONArray()));
        check(cold.optBoolean("historyNotLoaded") && cold.getJSONArray("turns").length() == 0,
                "cold metadata explicitly marks history as not loaded");
        check(AgentViewProjection.project(cold).optBoolean("historyNotLoaded"),
                "incremental projection preserves the not-loaded marker");
        JSONObject genuinelyEmpty = AgentViewProjection.project(new JSONObject().put("id", THREAD)
                .put("turns", new JSONArray()));
        check(!genuinelyEmpty.optBoolean("historyNotLoaded"),
                "an authoritative empty new-thread history remains distinct from metadata");
    }

    private static void pagedHistoryMergesNativeIdsWithoutReplacingKnownItems() throws Exception {
        JSONObject previous = AgentViewProjection.project(new JSONObject().put("id", THREAD)
                .put("turns", new JSONArray().put(turn("turn-middle", "保留的详细回复"))
                        .put(turn("turn-newest", "最新回复"))));
        String before = previous.toString();
        JSONObject summary = new JSONObject().put("id", "turn-middle").put("status", "completed")
                .put("items", new JSONArray().put(new JSONObject().put("id", "user-middle")
                        .put("type", "userMessage").put("content", new JSONArray()))
                        .put(new JSONObject().put("id", "answer-turn-middle")
                        .put("type", "agentMessage").put("status", "completed")));
        JSONObject merged = AgentViewProjection.mergeTurnPage(previous,
                new JSONArray().put(summary).put(turn("turn-old", "较早回复")), true, true, false);
        JSONArray turns = merged.getJSONArray("turns");
        check(turns.length() == 3 && turns.getJSONObject(0).getString("id").equals("turn-old")
                        && turns.getJSONObject(2).getString("id").equals("turn-newest"),
                "descending pages are merged in chronological order without dropping other turns");
        check(turns.getJSONObject(1).getJSONArray("items").length() == 2
                        && turns.getJSONObject(1).getJSONArray("items").getJSONObject(0).getString("id").equals("user-middle")
                        && turns.getJSONObject(1).getJSONArray("items").getJSONObject(1)
                                .getString("text").equals("保留的详细回复"),
                "summary items merge by native id without replacing retained detail");
        JSONObject repeated = AgentViewProjection.mergeTurnPage(merged,
                new JSONArray().put(summary), true, true, false);
        check(repeated.getJSONArray("turns").length() == 3
                        && repeated.getJSONArray("turns").getJSONObject(1).getJSONArray("items").length() == 2,
                "retrying a page is idempotent");
        JSONObject full = AgentViewProjection.mergeTurnPage(repeated,
                new JSONArray().put(turn("turn-middle", "完整分页中的新回复")), false, false, true);
        check(full.getJSONArray("turns").getJSONObject(1).getJSONArray("items").getJSONObject(1)
                        .getString("text").equals("完整分页中的新回复")
                        && full.getJSONArray("turns").getJSONObject(1).getJSONArray("items").length() == 2,
                "explicit full pages refresh detail while retaining other known items");
        check(previous.toString().equals(before), "page merge does not mutate the prior projection");
    }

    private static void metadataIdentityMismatchFailsClearly() throws Exception {
        try {
            AgentViewProjection.mergeMetadata(new JSONObject().put("id", THREAD),
                    new JSONObject().put("id", "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2"));
            throw new AssertionError("mismatched metadata must fail without replacing stored history");
        } catch (IOException expected) {
            check(expected.getMessage().contains("does not match the existing history identifier"), "metadata identity mismatch is explicit");
        }
    }

    private static void omissionCountsDoNotGrowWhenReplayingBoundedPages() throws Exception {
        JSONArray turns = new JSONArray();
        for (int i = 0; i < 81; i++) turns.put(turn("turn-" + i, "回复" + i));
        JSONObject previous = AgentViewProjection.project(new JSONObject().put("id", THREAD).put("turns", turns));
        check(previous.optInt("historyOmittedTurns") == 1, "fixture has one bounded older turn");
        JSONObject once = AgentViewProjection.mergeTurnPage(previous,
                new JSONArray().put(turn("turn-0", "回复0")), true, true, true);
        JSONObject twice = AgentViewProjection.mergeTurnPage(once,
                new JSONArray().put(turn("turn-0", "回复0")), true, true, true);
        check(once.optInt("historyOmittedTurns") == 1 && twice.optInt("historyOmittedTurns") == 1,
                "replaying an already bounded older page cannot inflate omission counts");
        check(once.optBoolean("historyOmissionCountUnknown") && twice.optBoolean("historyOmissionCountUnknown"),
                "pages do not claim a known complete history or exact omission total");
        check(once.getJSONArray("turns").toString().equals(twice.getJSONArray("turns").toString()),
                "bounded page replay keeps the same visible history");
    }

    private static JSONObject turn(String id, String text) throws Exception {
        return new JSONObject().put("id", id).put("status", "completed").put("items", new JSONArray()
                .put(new JSONObject().put("id", "answer-" + id).put("type", "agentMessage").put("text", text)));
    }

    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
