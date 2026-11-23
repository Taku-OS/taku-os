/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package org.agentos.island.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.android.systemui.taku.island.IslandPayload;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the production parser on Android's actual JSON implementation, without modifying SystemUI. */
public final class IslandParserTests extends Instrumentation {
    private static final String PKG = "org.agentos.agent";
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runCases();
            result.putString("stream", "PASS: " + checks + " island parser checks\n"
                    + "Boundary: parser only; not SystemUI compilation or window/device acceptance.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL after " + checks + " checks: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runCases() throws Exception {
        String base = "{\"version\":1,\"category\":\"assistant\","
                + "\"bigIsland\":{\"trailing\":{\"type\":\"text\",\"text\":\"Working\"}},"
                + "\"expanded\":{\"template\":\"text\",\"title\":\"A task\"}}";
        valid(base, "minimal");
        for (String mode : new String[]{"auto", "hidden", "building"})
            valid(new JSONObject(base).put("compact", mode).toString(), "compact " + mode);
        invalid(new JSONObject(base).put("compact", "listening").toString(), "no arbitrary compact mode");
        invalid(new JSONObject(base).put("compact", true).toString(), "strict compact type");
        invalid(new JSONObject(base).put("compact", "building").put("category", "media").toString(), "building is assistant-only");
        JSONObject nativeCard = new JSONObject(base);
        JSONObject expandedNative = nativeCard.getJSONObject("expanded");
        expandedNative.put("template", "assistant").put("actions", new JSONArray().put("open").put("reply_0"));
        JSONObject assistant = new JSONObject().put("token", "turn").put("phase", "listening").put("heightDp", 96).put("suggestions", new JSONArray());
        expandedNative.put("assistant", assistant);
        valid(nativeCard.toString(), "native listening card has no suggested prompts");
        assistant.put("phase", "message").put("suggestions", new JSONArray().put("reply_0"));
        valid(nativeCard.toString(), "actual option references an action");
        assistant.put("suggestions", new JSONArray().put("invented"));
        invalid(nativeCard.toString(), "option without an actual action");
        assistant.put("suggestions", new JSONArray().put("reply_0").put("reply_0"));
        invalid(nativeCard.toString(), "duplicate option");
        assistant.put("suggestions", new JSONArray());
        expandedNative.put("content", new JSONObject().put("service", PKG + "/.OldCard"));
        invalid(nativeCard.toString(), "native card cannot embed a remote component");
        expandedNative.remove("content");
        nativeCard.put("category", "media");
        invalid(nativeCard.toString(), "assistant card belongs to assistant category");
        invalid(null, "missing");
        invalid("{}", "empty");
        invalid(base + " trailing", "trailing tokens");
        invalid(base.replace("\"version\":1", "\"version\":1,\"version\":1"), "duplicate keys");
        invalid(base.replace('"', '\''), "non-JSON quote syntax");
        invalid(base.replace("\"version\":1", "\"version\":\"1\""), "no numeric coercion");
        invalid(base.replace("\"version\":1", "\"version\":2"), "unknown version");
        invalid(base.replace("\"version\":1", "\"version\":1.2"), "fractional version");
        invalid(base.replace("\"version\":1", "\"version\":null"), "null version");
        invalid(base.replace("\"version\":1", "\"version\":1e999"), "nonfinite");
        invalid(base.replace("\"category\":\"assistant\"", "\"category\":\"call\""), "reserved call");
        invalid(base.replace("\"category\":\"assistant\"", "\"category\":\"alarm\""), "reserved alarm");
        check(IslandPayload.parse(base.replace("assistant", "call"), PKG, true).isValid(), "trusted system category");
        invalid(base.replace("\"version\":1", "\"version\":1,\"url\":\"https://example.invalid\""), "unknown root key");
        invalid("[".repeat(100) + "]".repeat(100), "bounded recursion");
        invalid(" ".repeat(8193), "bounded ASCII allocation");
        invalid("😀".repeat(2049), "UTF-8 byte budget");
        valid(base.replace("Working", "😀".repeat(16)), "Unicode code points");
        invalid(base.replace("Working", "😀".repeat(17)), "Unicode slot limit");
        for (String field : new String[]{"alert", "relevance", "islandTimeoutS", "staleAfterS"}) {
            JSONObject value = new JSONObject(base).put(field, "1");
            invalid(value.toString(), "strict type " + field);
        }
        for (String field : new String[]{"islandTimeoutS", "staleAfterS"}) {
            invalid(new JSONObject(base).put(field, 0).toString(), "positive lifetime " + field);
            invalid(new JSONObject(base).put(field, 28801).toString(), "eight hour cap " + field);
            valid(new JSONObject(base).put(field, 28800).toString(), "lifetime boundary " + field);
        }
        JSONObject value = new JSONObject(base);
        value.put("bigIsland", new JSONObject());
        invalid(value.toString(), "empty compact");
        value = new JSONObject(base).put("smallIsland", new JSONObject("{\"type\":\"text\",\"text\":\"x\"}"));
        invalid(value.toString(), "small island restricted slots");
        for (String slot : new String[]{
                "{\"type\":\"pic\",\"pic\":\"task\",\"animated\":\"working\"}",
                "{\"type\":\"timer\",\"startEpochMs\":0}",
                "{\"type\":\"countdown\",\"endEpochMs\":1799999999999}",
                "{\"type\":\"progress\",\"value\":0.5}",
                "{\"type\":\"progress\"}",
                "{\"type\":\"number\",\"value\":\"123\",\"unit\":\"km\"}"}) {
            value = new JSONObject(base);
            value.getJSONObject("bigIsland").put("trailing", new JSONObject(slot));
            valid(value.toString(), "supported slot " + slot);
        }
        for (String slot : new String[]{
                "{\"type\":\"progress\",\"value\":1.1}",
                "{\"type\":\"timer\",\"startEpochMs\":-1}",
                "{\"type\":\"number\",\"value\":\"1.2\"}",
                "{\"type\":\"pic\",\"pic\":\"https://example.invalid\"}"}) {
            value = new JSONObject(base);
            value.getJSONObject("bigIsland").put("trailing", new JSONObject(slot));
            invalid(value.toString(), "invalid slot " + slot);
        }
        value = new JSONObject(base);
        value.getJSONObject("expanded").put("template", "steps");
        invalid(value.toString(), "steps template requires steps");
        value.getJSONObject("expanded").put("steps", new JSONArray("[{\"kind\":\"search\",\"text\":\"Finding\",\"running\":true}]"));
        valid(value.toString(), "steps template");
        value = new JSONObject(base);
        value.getJSONObject("expanded").put("primaryAction", "stop");
        invalid(value.toString(), "primary action must exist");
        value.getJSONObject("expanded").put("actions", new JSONArray("[\"stop\"]"));
        valid(value.toString(), "primary action");
        value.getJSONObject("expanded").put("actions", new JSONArray("[\"stop\",\"stop\"]"));
        invalid(value.toString(), "duplicate action");
        value = new JSONObject(base);
        value.getJSONObject("expanded").put("content", new JSONObject().put("service", PKG + "/.IslandContentService"));
        valid(value.toString(), "same package rich host declaration");
        JSONObject content = value.getJSONObject("expanded").getJSONObject("content");
        content.put("layout", "card").put("heightDp", 152);
        valid(value.toString(), "bounded full card declaration");
        content.put("heightDp", 4096);
        invalid(value.toString(), "full card cannot grow beyond host budget");
        content.put("heightDp", 152).put("layout", "window");
        invalid(value.toString(), "unknown remote layout");
        content.put("layout", "card");
        check(!IslandPayload.parse(value.toString(), "com.other.app", false).isValid(), "cross-package host rejected");
        value.getJSONObject("expanded").getJSONObject("content").put("token", "a".repeat(257));
        invalid(value.toString(), "token budget");
        runV2Cases();
    }

    /** v2 and the v1 adapter on Android's JSON implementation; the full matrix runs on the host. */
    private void runV2Cases() throws Exception {
        IslandPayload.Publisher taku = new IslandPayload.Publisher(PKG, true);
        IslandPayload.Publisher notebook = new IslandPayload.Publisher("org.agentos.notebook", true);
        JSONObject v2 = new JSONObject().put("version", 2)
                .put("compact", new JSONObject().put("a", new JSONObject().put("type", "icon").put("pic", "mic"))
                        .put("b", new JSONObject().put("type", "timer").put("startEpochMs", 1)))
                .put("expanded", new JSONObject().put("template", "basic").put("title", "Voice memo")
                        .put("actions", new JSONArray().put("stop")).put("primaryAction", "stop"))
                .put("tap", "open").put("timeoutS", 43200);
        check(IslandPayload.parseV2(v2.toString(), notebook).isValid(), "v2 voice memo");
        v2.put("timeoutS", 43201);
        check("number_range".equals(IslandPayload.parseV2(v2.toString(), notebook).error), "v2 12 h cap");
        v2.put("timeoutS", 60).getJSONObject("compact").put("a", new JSONObject().put("type", "animation").put("name", "ai-working"));
        check("invalid_animation".equals(IslandPayload.parseV2(v2.toString(), notebook).error), "AI loader reserved");
        check(IslandPayload.parseV2(v2.toString(), taku).isValid(), "Taku Agent AI loader");
        String building = "{\"version\":1,\"category\":\"assistant\",\"compact\":\"building\","
                + "\"bigIsland\":{\"leading\":{\"type\":\"pic\",\"pic\":\"taku\",\"animated\":\"working\"},"
                + "\"trailing\":{\"type\":\"text\",\"text\":\"Building...\"}},"
                + "\"expanded\":{\"template\":\"text\",\"title\":\"Taku\"}}";
        IslandPayload.Result adapted = IslandPayload.parseV1(building, taku);
        check(adapted.isValid() && adapted.payload.getJSONObject("compact").getJSONObject("a").getString("name").equals("ai-working"),
                "v1 building adapts to the AI animation");
    }

    private void valid(String json, String name) { check(IslandPayload.parse(json, PKG, false).isValid(), name); }
    private void invalid(String json, String name) { check(!IslandPayload.parse(json, PKG, false).isValid(), name); }
    private void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
