/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
import com.android.systemui.taku.island.IslandPayload;
import com.android.systemui.taku.island.IslandPayload.Publisher;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host checks for taku.island.v2 and the v1 adapter. Android's own JSON stack is covered by IslandParserTests. */
public final class IslandPayloadTest {
    private static final Publisher TAKU = new Publisher("org.agentos.agent", true);
    private static final Publisher NOTEBOOK = new Publisher("org.agentos.notebook", true);
    private static final Publisher THIRD = new Publisher("org.example.delivery", false);
    private static final Publisher FAKE_TAKU = new Publisher("org.agentos.agent", false);
    private static int checks;

    private static IslandPayload.Result v2(JSONObject payload, Publisher publisher) {
        return IslandPayload.parseV2(payload.toString(), publisher);
    }
    private static JSONObject valid(JSONObject payload, Publisher publisher, String what) {
        checks++;
        IslandPayload.Result result = v2(payload, publisher);
        if (!result.isValid()) throw new AssertionError(what + ": " + result.error);
        return result.payload;
    }
    private static void invalid(JSONObject payload, Publisher publisher, String error, String what) {
        checks++;
        IslandPayload.Result result = v2(payload, publisher);
        if (result.isValid() || !error.equals(result.error)) throw new AssertionError(what + ": " + result.error);
    }
    private static JSONObject v1(String json, Publisher publisher, String what) {
        checks++;
        IslandPayload.Result result = IslandPayload.parseV1(json, publisher);
        if (!result.isValid()) throw new AssertionError(what + ": " + result.error);
        return result.payload;
    }
    private static void v1Invalid(String json, Publisher publisher, String error, String what) {
        checks++;
        IslandPayload.Result result = IslandPayload.parseV1(json, publisher);
        if (result.isValid() || !error.equals(result.error)) throw new AssertionError(what + ": " + result.error);
    }
    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
    private static JSONObject base() throws Exception {
        return new JSONObject().put("version", 2)
                .put("expanded", new JSONObject().put("template", "basic").put("title", "Order ready"));
    }
    private static JSONObject with(String path, Object value) throws Exception {
        JSONObject payload = base();
        String[] parts = path.split("\\.");
        JSONObject target = payload;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!target.has(parts[i])) target.put(parts[i], new JSONObject());
            target = target.getJSONObject(parts[i]);
        }
        target.put(parts[parts.length - 1], value);
        return payload;
    }

    public static void main(String[] args) throws Exception {
        // Shape, renames and optional capsule.
        valid(base(), THIRD, "card-only activity needs no capsule or category");
        JSONObject full = with("compact", new JSONObject()
                .put("a", new JSONObject().put("type", "icon").put("pic", "logo").put("animate", true).put("progress", .4).put("tint", false))
                .put("b", new JSONObject().put("type", "text").put("text", "A123").put("emphasis", "accent")));
        full.put("tap", "open").put("cardPosition", "below").put("alert", true).put("timeoutS", 43200).put("staleAfterS", 600);
        valid(full, THIRD, "full third-party payload");
        invalid(with("compact", new JSONObject()), THIRD, "empty_compact", "empty compact");
        for (String old : new String[]{"category", "relevance", "bigIsland", "smallIsland", "statusBar", "islandTimeoutS"})
            invalid(with(old, "x"), THIRD, "unknown_field", "v1 field " + old + " is not v2");
        invalid(with("version", 1), THIRD, "number_range", "version is 2");
        invalid(with("timeoutS", 43201), THIRD, "number_range", "12 h cap");
        invalid(with("staleAfterS", 0), THIRD, "number_range", "stale lower bound");
        invalid(with("tap", "toggle"), THIRD, "invalid_enum", "tap enum");
        invalid(with("cardPosition", "auto"), THIRD, "invalid_enum", "cardPosition enum");
        invalid(with("alert", "yes"), THIRD, "expected_boolean", "alert type");

        // Group A and B content.
        invalid(with("compact.a", new JSONObject().put("type", "text").put("text", "x")), THIRD, "invalid_enum", "A holds no text");
        valid(with("compact.a", new JSONObject().put("type", "progress").put("value", 1)), THIRD, "A progress ring");
        for (JSONObject b : new JSONObject[]{
                new JSONObject().put("type", "number").put("value", "12").put("unit", "min"),
                new JSONObject().put("type", "timer").put("startEpochMs", 1),
                new JSONObject().put("type", "countdown").put("endEpochMs", 9007199254740991L),
                new JSONObject().put("type", "progress"),
                new JSONObject().put("type", "icon").put("pic", "wave")})
            valid(with("compact.b", b), THIRD, "B " + b.getString("type"));
        invalid(with("compact.b", new JSONObject().put("type", "text").put("text", "seventeen chars!!")), THIRD, "text_length", "B text cap");
        invalid(with("compact.b", new JSONObject().put("type", "number").put("value", "1a")), THIRD, "invalid_digits", "B digits");
        invalid(with("compact.a", new JSONObject().put("type", "icon").put("pic", "Logo")), THIRD, "invalid_key", "pic key grammar");
        invalid(with("compact.a", new JSONObject().put("type", "icon").put("pic", "logo").put("progress", 2)), THIRD, "number_range", "ring range");

        // Animations: every current system animation is Taku Agent's.
        JSONObject working = new JSONObject().put("type", "animation").put("name", "ai-working");
        valid(with("compact.a", working), TAKU, "Taku Agent working loader");
        invalid(with("compact.a", working), NOTEBOOK, "invalid_animation", "platform app cannot use AI loader");
        invalid(with("compact.a", working), FAKE_TAKU, "invalid_animation", "package name alone is not Taku Agent");
        invalid(with("compact.b", new JSONObject().put("type", "animation").put("name", "confetti")), TAKU, "invalid_animation", "unknown animation");

        // Templates.
        JSONObject assistant = base();
        assistant.getJSONObject("expanded").put("template", "assistant").put("actions", new JSONArray().put("open"))
                .put("assistant", new JSONObject().put("token", "t").put("phase", "message").put("heightDp", 300)
                        .put("suggestions", new JSONArray().put("open")));
        valid(assistant, TAKU, "assistant card for Taku Agent");
        invalid(assistant, NOTEBOOK, "reserved_template", "assistant card reserved");
        invalid(with("expanded.template", "assistant"), THIRD, "reserved_template", "assistant template reserved");
        invalid(with("expanded.template", "progress"), THIRD, "missing_progress", "progress needs progress");
        JSONObject progress = with("expanded.template", "progress");
        progress.getJSONObject("expanded").put("progress", new JSONObject());
        valid(progress, THIRD, "indeterminate progress");
        progress.getJSONObject("expanded").put("progress", new JSONObject().put("type", "progress"));
        invalid(progress, THIRD, "unknown_field", "v2 progress has value only");
        invalid(with("expanded.template", "highlight"), THIRD, "missing_highlight", "highlight needs highlight");
        JSONObject highlight = with("expanded.template", "highlight");
        highlight.getJSONObject("expanded").put("highlight", new JSONObject().put("text", "A123").put("label", "Pickup code"));
        valid(highlight, THIRD, "highlight template");
        highlight.getJSONObject("expanded").getJSONObject("highlight").put("text", "thirteen char");
        invalid(highlight, THIRD, "text_length", "highlight text cap");
        invalid(with("expanded.highlight", new JSONObject().put("text", "A1")), THIRD, "highlight_template_only", "highlight only on its template");
        invalid(with("expanded.template", "text"), THIRD, "invalid_enum", "v1 template names are not v2");

        // Actions: no design limit, a technical bound of 32.
        JSONArray many = new JSONArray();
        for (int i = 0; i < 32; i++) many.put("a" + i);
        valid(with("expanded.actions", many), THIRD, "32 actions");
        invalid(with("expanded.actions", new JSONArray(many.toString()).put("a32")), THIRD, "too_many_actions", "33 actions");
        invalid(with("expanded.actions", new JSONArray().put("go").put("go")), THIRD, "duplicate_action", "unique actions");
        JSONObject primary = with("expanded.actions", new JSONArray().put("go"));
        primary.getJSONObject("expanded").put("primaryAction", "stop");
        invalid(primary, THIRD, "missing_primary_action", "primary must be listed");
        valid(with("expanded.tapAction", "open_order"), THIRD, "tap action outside the chip row");
        invalid(with("expanded.tapAction", "Open"), THIRD, "invalid_key", "tap action key grammar");

        // Accent colour contrast against the card.
        valid(with("expanded.accentColor", "#FF8A3D"), THIRD, "orange accent");
        valid(with("expanded.accentColor", "#ffffff"), THIRD, "lowercase hex");
        invalid(with("expanded.accentColor", "#333333"), THIRD, "low_contrast", "dark accent");
        invalid(with("expanded.accentColor", "orange"), THIRD, "text_length", "named colour");
        invalid(with("expanded.accentColor", "#GG0000"), THIRD, "invalid_color", "hex grammar");

        // Remote content surfaces stay with platform-signed publishers.
        JSONObject content = with("expanded.content", new JSONObject().put("service", "org.example.delivery/.Card"));
        invalid(content, THIRD, "reserved_content", "third party cannot embed surfaces");
        valid(with("expanded.content", new JSONObject().put("service", "org.agentos.notebook/.Card")), NOTEBOOK, "platform app surface");
        invalid(with("expanded.content", new JSONObject().put("service", "org.example.delivery/.Card")), NOTEBOOK, "foreign_service", "own service only");

        // Reader bounds shared with v1.
        checks++;
        if (IslandPayload.parseV2("{\"version\":2,\"version\":2,\"expanded\":{}}", THIRD).isValid()) throw new AssertionError("duplicate key");
        checks++;
        if (!"payload_too_large".equals(IslandPayload.parseV2("{\"x\":\"" + "y".repeat(9000) + "\"}", THIRD).error)) throw new AssertionError("size");
        checks++;
        if (!"too_deep".equals(IslandPayload.parseV2("[[[[[[[[[]]]]]]]]]", THIRD).error)) throw new AssertionError("depth");
        checks++;
        if (!"unbalanced_json".equals(IslandPayload.parseV2("{\"version\":2,", THIRD).error)) throw new AssertionError("unbalanced");
        checks++;
        if (!"invalid_payload".equals(IslandPayload.parseV2("{\"version\":2 \"x\":1}", THIRD).error)) throw new AssertionError("malformed");
        checks++;
        if (!"missing_payload".equals(IslandPayload.parseV2(null, THIRD).error)) throw new AssertionError("null");

        // v1 adapter.
        String building = "{\"version\":1,\"category\":\"assistant\",\"compact\":\"building\",\"islandTimeoutS\":28800,"
                + "\"bigIsland\":{\"leading\":{\"type\":\"pic\",\"pic\":\"taku\",\"animated\":\"working\"},"
                + "\"trailing\":{\"type\":\"text\",\"text\":\"Building...\"}},"
                + "\"expanded\":{\"template\":\"text\",\"title\":\"Taku\"},\"statusBar\":\"auto\"}";
        JSONObject adapted = v1(building, TAKU, "Taku Agent building");
        check(adapted.getJSONObject("compact").getJSONObject("a").getString("name").equals("ai-working"), "working pic becomes AI animation");
        check(adapted.getJSONObject("compact").getJSONObject("b").getString("text").equals("Building..."), "publisher text kept");
        check(adapted.getInt("timeoutS") == 28800 && !adapted.has("cardPosition"), "lifetime renamed, auto position defaults");
        check(adapted.getJSONObject("expanded").getString("template").equals("basic"), "text template becomes basic");
        v1Invalid(building, NOTEBOOK, "invalid_animation", "v1 cannot smuggle the AI loader");
        JSONObject quiet = v1(building.replace("\"building\"", "\"auto\""), TAKU, "assistant not building");
        check(!quiet.has("compact"), "assistant without building has no capsule");
        JSONObject hidden = v1(building.replace("\"assistant\"", "\"media\"").replace("\"building\"", "\"hidden\""), TAKU, "hidden");
        check(!hidden.has("compact"), "hidden v1 capsule");
        String progressV1 = "{\"version\":1,\"category\":\"progress\",\"statusBar\":\"below\","
                + "\"bigIsland\":{\"leading\":{\"type\":\"pic\",\"pic\":\"app_icon\"},\"trailing\":{\"type\":\"text\",\"text\":\"Connecting\"}},"
                + "\"expanded\":{\"template\":\"progress\",\"title\":\"Sign in\",\"progress\":{\"type\":\"progress\",\"value\":0.5},"
                + "\"actions\":[\"open\"],\"primaryAction\":\"open\"}}";
        JSONObject p = v1(progressV1, NOTEBOOK, "v1 progress");
        check(p.getJSONObject("compact").getJSONObject("a").getString("pic").equals("app_icon"), "pic becomes A icon");
        check(p.getString("cardPosition").equals("below"), "statusBar below becomes cardPosition");
        check(p.getJSONObject("expanded").getJSONObject("progress").getDouble("value") == .5, "progress value kept");
        String textual = "{\"version\":1,\"category\":\"timer\",\"bigIsland\":{\"leading\":{\"type\":\"countdown\",\"endEpochMs\":5}},"
                + "\"smallIsland\":{\"type\":\"pic\",\"pic\":\"timer\"},\"expanded\":{\"template\":\"text\",\"title\":\"Timer\"}}";
        JSONObject t = v1(textual, THIRD, "textual leading");
        check(t.getJSONObject("compact").getJSONObject("b").getString("type").equals("countdown"), "textual leading moves to B");
        check(t.getJSONObject("compact").getJSONObject("a").getString("pic").equals("timer"), "small island glyph fills A");
        String nativeCard = building.replace("\"template\":\"text\",\"title\":\"Taku\"",
                "\"template\":\"assistant\",\"title\":\"Taku\",\"actions\":[\"open\"],"
                + "\"assistant\":{\"token\":\"t\",\"phase\":\"working\",\"heightDp\":300,\"suggestions\":[]}");
        v1(nativeCard, TAKU, "v1 assistant card from Taku Agent");
        v1Invalid(nativeCard.replace("\"animated\":\"working\"", "\"animated\":\"none\""), NOTEBOOK, "reserved_template", "v1 assistant card reserved");
        v1Invalid(building.replace("\"assistant\"", "\"call\"").replace("\"building\"", "\"auto\""), THIRD, "invalid_payload", "v1 reserved call category");
        // The exact v2 shapes Taku Agent posts (IslandPublisherService, OnboardingIsland).
        JSONObject takuCard = new JSONObject().put("version", 2).put("timeoutS", 28800)
                .put("expanded", new JSONObject().put("template", "assistant").put("title", "Taku is working")
                        .put("body", "Building your app").put("steps", new JSONArray())
                        .put("actions", new JSONArray().put("stop").put("open")).put("primaryAction", "open").put("tapAction", "open")
                        .put("assistant", new JSONObject().put("token", "thread:1").put("phase", "working")
                                .put("suggestions", new JSONArray()).put("heightDp", 300)))
                .put("compact", new JSONObject().put("a", working)
                        .put("b", new JSONObject().put("type", "text").put("text", "Building...")));
        valid(takuCard, TAKU, "Taku Agent building card");
        takuCard.remove("compact");
        valid(takuCard, TAKU, "Taku Agent card without a capsule");
        JSONObject onboarding = new JSONObject().put("version", 2).put("timeoutS", 900)
                .put("compact", new JSONObject().put("a", working).put("b", new JSONObject().put("type", "text").put("text", "Signing...")))
                .put("expanded", new JSONObject().put("template", "basic").put("title", "Signing in to ChatGPT")
                        .put("body", "Finish signing in on the ChatGPT page, then Taku continues setup.")
                        .put("actions", new JSONArray().put("open")).put("primaryAction", "open").put("tapAction", "open"));
        valid(onboarding, TAKU, "Taku onboarding signing island");

        // The published schema's examples must pass the production validator.
        JSONObject schema = new JSONObject(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("../../spec/taku-island-v2.schema.json")), java.nio.charset.StandardCharsets.UTF_8));
        JSONArray examples = schema.getJSONArray("examples");
        for (int i = 0; i < examples.length(); i++) valid(examples.getJSONObject(i), THIRD, "schema example " + i);
        System.out.println("PASS: " + checks + " island payload v2/adapter checks");
    }
}
