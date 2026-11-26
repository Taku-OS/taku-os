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

    private static final IslandPayload.Publisher TAKU = new IslandPayload.Publisher(PKG, true);
    private static final IslandPayload.Publisher NOTEBOOK = new IslandPayload.Publisher("org.agentos.notebook", true);
    private static final IslandPayload.Publisher THIRD = new IslandPayload.Publisher("org.example.delivery", false);

    /** The Taku Island protocol on Android's JSON implementation; the full matrix runs on the host. */
    private void runCases() throws Exception {
        String base = "{\"version\":2,"
                + "\"compact\":{\"b\":{\"type\":\"text\",\"text\":\"Working\"}},"
                + "\"expanded\":{\"template\":\"basic\",\"title\":\"A task\"}}";
        valid(base, THIRD, "minimal");
        invalid(null, THIRD, "missing");
        invalid("{}", THIRD, "empty");
        invalid(base + " trailing", THIRD, "trailing tokens");
        invalid(base.replace("\"version\":2", "\"version\":2,\"version\":2"), THIRD, "duplicate keys");
        invalid(base.replace('"', '\''), THIRD, "non-JSON quote syntax");
        invalid(base.replace("\"version\":2", "\"version\":\"2\""), THIRD, "no numeric coercion");
        invalid(base.replace("\"version\":2", "\"version\":1"), THIRD, "only the current protocol");
        invalid(base.replace("\"version\":2", "\"version\":2.5"), THIRD, "fractional version");
        invalid(base.replace("\"version\":2", "\"version\":1e999"), THIRD, "nonfinite");
        invalid(base.replace("\"version\":2", "\"version\":2,\"url\":\"https://example.invalid\""), THIRD, "unknown root key");
        invalid(base.replace("\"version\":2", "\"version\":2,\"bigIsland\":{}"), THIRD, "v1 fields are unknown");
        invalid("[".repeat(100) + "]".repeat(100), THIRD, "bounded recursion");
        invalid(" ".repeat(8193), THIRD, "bounded ASCII allocation");
        invalid("😀".repeat(2049), THIRD, "UTF-8 byte budget");
        valid(base.replace("Working", "😀".repeat(16)), THIRD, "Unicode code points");
        invalid(base.replace("Working", "😀".repeat(17)), THIRD, "Unicode slot limit");
        for (String field : new String[]{"alert", "timeoutS", "staleAfterS"})
            invalid(new JSONObject(base).put(field, "1").toString(), THIRD, "strict type " + field);
        for (String field : new String[]{"timeoutS", "staleAfterS"}) {
            invalid(new JSONObject(base).put(field, 0).toString(), THIRD, "positive lifetime " + field);
            invalid(new JSONObject(base).put(field, 43201).toString(), THIRD, "12 h cap " + field);
            valid(new JSONObject(base).put(field, 43200).toString(), THIRD, "lifetime boundary " + field);
        }
        invalid(new JSONObject(base).put("compact", new JSONObject()).toString(), THIRD, "empty compact");
        for (String slot : new String[]{
                "{\"type\":\"timer\",\"startEpochMs\":0}",
                "{\"type\":\"countdown\",\"endEpochMs\":1799999999999}",
                "{\"type\":\"progress\",\"value\":0.5}",
                "{\"type\":\"number\",\"value\":\"123\",\"unit\":\"km\"}"}) {
            JSONObject value = new JSONObject(base);
            value.getJSONObject("compact").put("b", new JSONObject(slot));
            valid(value.toString(), THIRD, "supported slot " + slot);
        }
        for (String slot : new String[]{
                "{\"type\":\"progress\",\"value\":1.1}",
                "{\"type\":\"timer\",\"startEpochMs\":-1}",
                "{\"type\":\"number\",\"value\":\"1.2\"}"}) {
            JSONObject value = new JSONObject(base);
            value.getJSONObject("compact").put("b", new JSONObject(slot));
            invalid(value.toString(), THIRD, "invalid slot " + slot);
        }
        JSONObject value = new JSONObject(base);
        value.getJSONObject("expanded").put("primaryAction", "stop");
        invalid(value.toString(), THIRD, "primary action must be listed");
        value.getJSONObject("expanded").put("actions", new JSONArray("[\"stop\"]"));
        valid(value.toString(), THIRD, "primary action");
        value.getJSONObject("expanded").put("actions", new JSONArray("[\"stop\",\"stop\"]"));
        invalid(value.toString(), THIRD, "duplicate action");

        // Taku Agent's native card.
        JSONObject nativeCard = new JSONObject(base);
        JSONObject expandedNative = nativeCard.getJSONObject("expanded");
        expandedNative.put("template", "assistant").put("actions", new JSONArray().put("open").put("reply_0"));
        JSONObject assistant = new JSONObject().put("token", "turn").put("phase", "listening").put("heightDp", 96).put("suggestions", new JSONArray());
        expandedNative.put("assistant", assistant);
        valid(nativeCard.toString(), TAKU, "native listening card has no suggested prompts");
        invalid(nativeCard.toString(), NOTEBOOK, "assistant template belongs to Taku Agent");
        assistant.put("phase", "message").put("suggestions", new JSONArray().put("reply_0"));
        valid(nativeCard.toString(), TAKU, "actual option references an action");
        assistant.put("suggestions", new JSONArray().put("invented"));
        invalid(nativeCard.toString(), TAKU, "option without an actual action");
        assistant.put("suggestions", new JSONArray());
        expandedNative.put("content", new JSONObject().put("service", PKG + "/.OldCard"));
        invalid(nativeCard.toString(), TAKU, "native card cannot embed a remote component");

        // Remote content: platform-signed publishers, own package only, bounded height.
        value = new JSONObject(base);
        value.getJSONObject("expanded").put("content", new JSONObject().put("service", "org.agentos.notebook/.IslandContentService"));
        valid(value.toString(), NOTEBOOK, "same package rich host declaration");
        invalid(value.toString(), THIRD, "remote content is platform-only");
        JSONObject content = value.getJSONObject("expanded").getJSONObject("content");
        content.put("layout", "card").put("heightDp", 152);
        valid(value.toString(), NOTEBOOK, "bounded full card declaration");
        content.put("heightDp", 4096);
        invalid(value.toString(), NOTEBOOK, "full card cannot grow beyond host budget");
        content.put("heightDp", 152).put("layout", "window");
        invalid(value.toString(), NOTEBOOK, "unknown remote layout");
        content.put("layout", "card").put("service", "com.other.app/.Card");
        invalid(value.toString(), NOTEBOOK, "cross-package host rejected");

        // Voice memo, the 12 h cap and the reserved AI animation.
        JSONObject memo = new JSONObject().put("version", 2)
                .put("compact", new JSONObject().put("a", new JSONObject().put("type", "icon").put("pic", "mic"))
                        .put("b", new JSONObject().put("type", "timer").put("startEpochMs", 1)))
                .put("expanded", new JSONObject().put("template", "basic").put("title", "Voice memo")
                        .put("actions", new JSONArray().put("stop")).put("primaryAction", "stop"))
                .put("tap", "open").put("timeoutS", 43200);
        valid(memo.toString(), NOTEBOOK, "voice memo");
        memo.put("timeoutS", 60).getJSONObject("compact").put("a", new JSONObject().put("type", "animation").put("name", "ai-working"));
        check("invalid_animation".equals(IslandPayload.parse(memo.toString(), NOTEBOOK).error), "AI loader reserved");
        valid(memo.toString(), TAKU, "Taku Agent AI loader");
        // A white template style with a contrasting accent.
        JSONObject white = new JSONObject(base);
        white.getJSONObject("expanded").put("backgroundColor", "#FFFFFF").put("accentColor", "#0A5BD3");
        valid(white.toString(), THIRD, "white card");
        white.getJSONObject("expanded").put("accentColor", "#FFE082");
        invalid(white.toString(), THIRD, "accent must contrast with the background");
    }

    private void valid(String json, IslandPayload.Publisher publisher, String name) {
        check(IslandPayload.parse(json, publisher).isValid(), name);
    }
    private void invalid(String json, IslandPayload.Publisher publisher, String name) {
        check(!IslandPayload.parse(json, publisher).isValid(), name);
    }
    private void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
