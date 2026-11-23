/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bounded validation of the custom island extras. No class loading, URI fetching, action execution
 * or view inflation. {@link #parseV2} returns the canonical (v2-shaped) payload SystemUI renders;
 * {@link #adaptV1} maps an already validated v1 payload onto that shape and re-validates it under
 * v2 rules, so both wire versions obey one set of publisher restrictions.
 */
public final class IslandPayload {
    public static final int MAX_BYTES = 8192;
    public static final int MAX_LIFETIME_S = 43200;
    public static final int MAX_ACTIONS = 32;
    public static final String TAKU_PACKAGE = "org.agentos.agent";
    /** Card background the accent colour must contrast with (IslandStyle card, opaque fallback). */
    private static final int CARD_RGB = 0x121212;
    private static final Set<String> AI_ANIMATIONS = new HashSet<>(Arrays.asList("ai-working", "ai-listening"));

    /** What the extractor verified about the posting package; never taken from the payload. */
    public static final class Publisher {
        public final String packageName;
        /** Signed with the platform certificate. */
        public final boolean platform;
        /** Taku Agent: {@link #TAKU_PACKAGE} with the platform certificate. */
        public final boolean taku;

        public Publisher(String packageName, boolean platform) {
            this.packageName = packageName;
            this.platform = platform;
            this.taku = platform && TAKU_PACKAGE.equals(packageName);
        }
    }

    public static final class Result {
        public final JSONObject payload;
        public final String error;

        private Result(JSONObject payload, String error) {
            this.payload = payload;
            this.error = error;
        }

        public boolean isValid() { return payload != null; }
    }

    /** Producer ownership/permission and referenced Icon/Action checks belong to the extractor. */
    public static Result parse(String json, String postingPackage, boolean systemSource) {
        if (json == null) return new Result(null, "missing_payload");
        // Reject before allocating an arbitrarily large UTF-8 copy or entering the JSON parser.
        if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return new Result(null, "payload_too_large");
        }
        try {
            checkDepth(json);
            JSONObject value;
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.setLenient(false);
                Object parsed = readJson(reader);
                require(parsed instanceof JSONObject && reader.peek() == JsonToken.END_DOCUMENT, "invalid_json");
                value = (JSONObject) parsed;
            }
            keys(value, "version category bigIsland smallIsland expanded relevance statusBar alert islandTimeoutS staleAfterS compact");
            integer(value, "version", 1, 1, true);
            choice(value, "category", "call alarm recording navigation timer assistant media progress other", true);
            String category = value.getString("category");
            choice(value, "compact", "auto hidden building", false);
            require(!"building".equals(value.optString("compact")) || category.equals("assistant"), "invalid_building_category");
            require(systemSource || !(category.equals("call") || category.equals("alarm")), "reserved_category");
            number(value, "relevance", 0, 1);
            JSONObject big = object(value, "bigIsland");
            keys(big, "leading trailing");
            require(big.length() > 0, "empty_big_island");
            for (String side : new String[]{"leading", "trailing"}) {
                if (big.has(side)) slot(object(big, side), false);
            }
            if (value.has("smallIsland")) slot(object(value, "smallIsland"), true);
            expanded(object(value, "expanded"), postingPackage);
            if (object(value, "expanded").has("assistant")) require(category.equals("assistant"), "assistant_category");
            choice(value, "statusBar", "auto cover below", false);
            bool(value, "alert");
            integer(value, "islandTimeoutS", 1, 28800, false);
            integer(value, "staleAfterS", 1, 28800, false);
            return new Result(value, null);
        } catch (JSONException | IOException | IllegalArgumentException | IllegalStateException error) {
            // Never return untrusted text or a parser exception to the status bar renderer.
            return new Result(null, "invalid_payload");
        }
    }

    /** Validates {@code taku.island.v2}. The result is the canonical payload. */
    public static Result parseV2(String json, Publisher publisher) {
        try {
            return validateV2(read(json), publisher);
        } catch (IllegalArgumentException error) {
            return new Result(null, code(error));
        } catch (JSONException | IOException | IllegalStateException error) {
            return new Result(null, "invalid_payload");
        }
    }

    /** Validates {@code taku.island.v1}, then maps it onto the canonical v2 shape. */
    public static Result parseV1(String json, Publisher publisher) {
        Result v1 = parse(json, publisher.packageName, false);
        if (!v1.isValid()) return v1;
        try {
            return validateV2(adaptV1(v1.payload), publisher);
        } catch (IllegalArgumentException error) {
            return new Result(null, code(error));
        } catch (JSONException error) {
            return new Result(null, "invalid_payload");
        }
    }

    /** Only our own identifier-style codes reach diagnostics; never parser or platform text. */
    private static String code(IllegalArgumentException error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z][a-z_]{0,47}") ? message : "invalid_payload";
    }

    /** Strict reader shared by both versions: size, depth, duplicate keys and number grammar. */
    private static JSONObject read(String json) throws IOException, JSONException {
        require(json != null, "missing_payload");
        // Reject before allocating an arbitrarily large UTF-8 copy or entering the JSON parser.
        require(json.length() <= MAX_BYTES && json.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES,
                "payload_too_large");
        checkDepth(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            Object parsed = readJson(reader);
            require(parsed instanceof JSONObject && reader.peek() == JsonToken.END_DOCUMENT, "invalid_json");
            return (JSONObject) parsed;
        }
    }

    private static Result validateV2(JSONObject value, Publisher publisher) throws JSONException {
        keys(value, "version compact expanded tap cardPosition alert timeoutS staleAfterS");
        integer(value, "version", 2, 2, true);
        if (value.has("compact")) {
            JSONObject compact = object(value, "compact");
            keys(compact, "a b");
            require(compact.length() > 0, "empty_compact");
            if (compact.has("a")) slotV2(object(compact, "a"), true, publisher);
            if (compact.has("b")) slotV2(object(compact, "b"), false, publisher);
        }
        expandedV2(object(value, "expanded"), publisher);
        choice(value, "tap", "expand open", false);
        choice(value, "cardPosition", "cover below", false);
        bool(value, "alert");
        integer(value, "timeoutS", 1, MAX_LIFETIME_S, false);
        integer(value, "staleAfterS", 1, MAX_LIFETIME_S, false);
        return new Result(value, null);
    }

    private static void slotV2(JSONObject slot, boolean groupA, Publisher publisher) throws JSONException {
        choice(slot, "type", groupA ? "icon animation progress" : "text number timer countdown progress icon animation", true);
        switch (slot.getString("type")) {
            case "icon":
                keys(slot, "type pic animate progress tint contentDescription");
                key(slot, "pic", true);
                bool(slot, "animate");
                bool(slot, "tint");
                number(slot, "progress", 0, 1);
                text(slot, "contentDescription", 0, 80, false);
                break;
            case "animation":
                keys(slot, "type name contentDescription");
                text(slot, "name", 1, 32, true);
                // Every current system animation belongs to Taku Agent's AI loading/listening.
                require(AI_ANIMATIONS.contains(slot.getString("name")) && publisher.taku, "invalid_animation");
                text(slot, "contentDescription", 0, 80, false);
                break;
            case "progress":
                keys(slot, "type value");
                number(slot, "value", 0, 1);
                break;
            case "text":
                keys(slot, "type text emphasis");
                text(slot, "text", 1, 16, true);
                choice(slot, "emphasis", "normal accent muted", false);
                break;
            case "number":
                keys(slot, "type value unit");
                text(slot, "value", 1, 3, true);
                require(slot.getString("value").matches("[0-9]{1,3}"), "invalid_digits");
                text(slot, "unit", 0, 4, false);
                break;
            case "timer":
            case "countdown":
                String field = slot.getString("type").equals("timer") ? "startEpochMs" : "endEpochMs";
                keys(slot, "type " + field);
                integer(slot, field, 0, 9007199254740991L, true);
                break;
            default: throw new IllegalArgumentException("unsupported_slot");
        }
    }

    private static void expandedV2(JSONObject value, Publisher publisher) throws JSONException {
        keys(value, "template title body pic steps progress highlight actions primaryAction tapAction accentColor assistant content");
        choice(value, "template", "basic progress highlight assistant", true);
        String template = value.getString("template");
        text(value, "title", 1, 80, true);
        text(value, "body", 0, 2000, false);
        key(value, "pic", false);
        if (value.has("steps")) steps(value);
        if (value.has("progress")) {
            JSONObject progress = object(value, "progress");
            keys(progress, "value");
            number(progress, "value", 0, 1);
        }
        require(!template.equals("progress") || value.has("progress"), "missing_progress");
        if (value.has("highlight")) {
            require(template.equals("highlight"), "highlight_template_only");
            JSONObject highlight = object(value, "highlight");
            keys(highlight, "text label");
            text(highlight, "text", 1, 12, true);
            text(highlight, "label", 0, 40, false);
        }
        require(!template.equals("highlight") || value.has("highlight"), "missing_highlight");
        Set<String> actions = new HashSet<>();
        if (value.has("actions")) {
            Object raw = value.get("actions");
            require(raw instanceof JSONArray, "expected_array");
            JSONArray list = (JSONArray) raw;
            require(list.length() <= MAX_ACTIONS, "too_many_actions");
            for (int index = 0; index < list.length(); index++) {
                Object action = list.get(index);
                require(action instanceof String && validKey((String) action), "invalid_action_key");
                require(actions.add((String) action), "duplicate_action");
            }
        }
        key(value, "primaryAction", false);
        if (value.has("primaryAction")) require(actions.contains(value.getString("primaryAction")), "missing_primary_action");
        key(value, "tapAction", false);
        if (value.has("accentColor")) {
            text(value, "accentColor", 7, 7, true);
            String color = value.getString("accentColor");
            require(color.matches("#[0-9A-Fa-f]{6}"), "invalid_color");
            require(contrast(Integer.parseInt(color.substring(1), 16), CARD_RGB) >= 4.5, "low_contrast");
        }
        if (template.equals("assistant") || value.has("assistant")) {
            require(publisher.taku, "reserved_template");
            require(template.equals("assistant") && value.has("assistant") && !value.has("content"), "native_assistant_only");
            assistant(object(value, "assistant"), actions);
        }
        if (value.has("content")) {
            require(publisher.platform, "reserved_content");
            content(object(value, "content"), publisher.packageName);
        }
    }

    /** WCAG 2 contrast ratio between two sRGB colours. */
    static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    private static double luminance(int rgb) {
        double[] channels = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        for (int i = 0; i < 3; i++) {
            double c = channels[i] / 255;
            channels[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
        }
        return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
    }

    /** Maps a validated v1 payload onto the canonical shape; category/relevance have no v2 meaning. */
    static JSONObject adaptV1(JSONObject v1) throws JSONException {
        JSONObject v2 = new JSONObject().put("version", 2);
        String category = v1.getString("category");
        String mode = v1.optString("compact", "auto");
        boolean capsule = category.equals("assistant") ? mode.equals("building") : !mode.equals("hidden");
        if (capsule) {
            JSONObject big = v1.getJSONObject("bigIsland");
            JSONObject leading = big.optJSONObject("leading");
            JSONObject trailing = big.optJSONObject("trailing");
            JSONObject a = glyphV1(leading);
            if (a == null) a = glyphV1(v1.optJSONObject("smallIsland"));
            JSONObject b = trailing != null ? valueV1(trailing) : glyphV1(leading) == null && leading != null ? valueV1(leading) : null;
            JSONObject compact = new JSONObject();
            if (a != null) compact.put("a", a);
            if (b != null) compact.put("b", b);
            if (compact.length() > 0) v2.put("compact", compact);
        }
        JSONObject old = v1.getJSONObject("expanded");
        JSONObject expanded = new JSONObject();
        String template = old.getString("template");
        expanded.put("template", template.equals("progress") || template.equals("assistant") ? template : "basic");
        for (String field : new String[]{"title", "body", "pic", "steps", "actions", "primaryAction", "assistant", "content"}) {
            if (old.has(field)) expanded.put(field, old.get(field));
        }
        if (old.has("progress")) {
            JSONObject progress = new JSONObject();
            if (old.getJSONObject("progress").has("value")) progress.put("value", old.getJSONObject("progress").get("value"));
            expanded.put("progress", progress);
        }
        v2.put("expanded", expanded);
        if ("below".equals(v1.optString("statusBar"))) v2.put("cardPosition", "below");
        if (v1.has("alert")) v2.put("alert", v1.get("alert"));
        if (v1.has("islandTimeoutS")) v2.put("timeoutS", v1.get("islandTimeoutS"));
        if (v1.has("staleAfterS")) v2.put("staleAfterS", v1.get("staleAfterS"));
        return v2;
    }

    /** v1 glyphs (pic, progress, host animation) become group A content. */
    private static JSONObject glyphV1(JSONObject slot) throws JSONException {
        if (slot == null) return null;
        String type = slot.getString("type");
        if (type.equals("progress")) {
            JSONObject result = new JSONObject().put("type", "progress");
            if (slot.has("value")) result.put("value", slot.get("value"));
            return result;
        }
        if (!type.equals("pic")) return null;
        String animated = slot.optString("animated", "none");
        if (!animated.equals("none")) return new JSONObject().put("type", "animation").put("name", "ai-" + animated);
        JSONObject icon = new JSONObject().put("type", "icon").put("pic", slot.getString("pic"));
        if (slot.has("contentDescription")) icon.put("contentDescription", slot.get("contentDescription"));
        return icon;
    }

    /** v1 textual slots become group B content; the pill never drew their optional pic. */
    private static JSONObject valueV1(JSONObject slot) throws JSONException {
        String type = slot.getString("type");
        JSONObject result = new JSONObject().put("type", type);
        switch (type) {
            case "text":
                result.put("text", slot.get("text"));
                if (slot.has("emphasis")) result.put("emphasis", slot.get("emphasis"));
                return result;
            case "number":
                result.put("value", slot.get("value"));
                if (slot.has("unit")) result.put("unit", slot.get("unit"));
                return result;
            case "timer": return result.put("startEpochMs", slot.get("startEpochMs"));
            case "countdown": return result.put("endEpochMs", slot.get("endEpochMs"));
            case "progress":
                if (slot.has("value")) result.put("value", slot.get("value"));
                return result;
            default: return glyphV1(slot);
        }
    }

    private static void steps(JSONObject value) throws JSONException {
        JSONArray steps = array(value, "steps", 8);
        for (int index = 0; index < steps.length(); index++) {
            Object raw = steps.get(index);
            require(raw instanceof JSONObject, "expected_object");
            JSONObject step = (JSONObject) raw;
            keys(step, "kind text running");
            choice(step, "kind", "search browse act info", true);
            text(step, "text", 1, 120, true);
            bool(step, "running");
        }
    }

    private static void assistant(JSONObject assistant, Set<String> actions) throws JSONException {
        keys(assistant, "token phase heightDp suggestions");
        text(assistant, "token", 1, 256, true);
        choice(assistant, "phase", "listening working message", true);
        integer(assistant, "heightDp", 96, 340, true);
        JSONArray suggestions = array(assistant, "suggestions", 4);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < suggestions.length(); i++) {
            String id = suggestions.getString(i);
            require(actions.contains(id) && seen.add(id), "invalid_suggestion_action");
        }
    }

    private static void content(JSONObject content, String postingPackage) throws JSONException {
        keys(content, "service token layout heightDp");
        choice(content, "layout", "body card", false);
        integer(content, "heightDp", 96, 340, false);
        text(content, "service", 1, 512, true);
        String service = content.getString("service");
        require(service.matches("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$"), "invalid_service");
        require(postingPackage != null && service.substring(0, service.indexOf('/')).equals(postingPackage), "foreign_service");
        text(content, "token", 0, 256, false);
    }

    private static void expanded(JSONObject value, String postingPackage) throws JSONException {
        keys(value, "template title body pic steps progress actions primaryAction content assistant");
        choice(value, "template", "text steps progress media assistant", true);
        text(value, "title", 1, 80, true);
        text(value, "body", 0, 2000, false);
        key(value, "pic", false);
        String template = value.getString("template");
        require(!template.equals("steps") || value.has("steps"), "missing_steps");
        require(!template.equals("progress") || value.has("progress"), "missing_progress");
        if (value.has("steps")) {
            JSONArray steps = array(value, "steps", 8);
            for (int index = 0; index < steps.length(); index++) {
                JSONObject step = steps.getJSONObject(index);
                keys(step, "kind text running");
                choice(step, "kind", "search browse act info", true);
                text(step, "text", 1, 120, true);
                bool(step, "running");
            }
        }
        if (value.has("progress")) {
            JSONObject progress = object(value, "progress");
            require("progress".equals(progress.opt("type")), "invalid_progress");
            slot(progress, true);
        }
        Set<String> actions = new HashSet<>();
        if (value.has("actions")) {
            JSONArray list = array(value, "actions", 4);
            for (int index = 0; index < list.length(); index++) {
                Object action = list.get(index);
                require(action instanceof String && validKey((String) action), "invalid_action_key");
                require(actions.add((String) action), "duplicate_action");
            }
        }
        key(value, "primaryAction", false);
        if (value.has("primaryAction")) {
            require(actions.contains(value.getString("primaryAction")), "missing_primary_action");
        }
        if (value.has("assistant")) {
            require(template.equals("assistant") && !value.has("content"), "native_assistant_only");
            JSONObject assistant = object(value, "assistant");
            keys(assistant, "token phase heightDp suggestions");
            text(assistant, "token", 1, 256, true);
            choice(assistant, "phase", "listening working message", true);
            integer(assistant, "heightDp", 96, 340, true);
            JSONArray suggestions = array(assistant, "suggestions", 4);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < suggestions.length(); i++) {
                String id = suggestions.getString(i);
                require(actions.contains(id) && seen.add(id), "invalid_suggestion_action");
            }
        }
        require(!template.equals("assistant") || value.has("assistant"), "missing_assistant");
        if (value.has("content")) {
            JSONObject content = object(value, "content");
            keys(content, "service token layout heightDp");
            choice(content, "layout", "body card", false);
            integer(content, "heightDp", 96, 340, false);
            text(content, "service", 1, 512, true);
            String service = content.getString("service");
            require(service.matches("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+$"), "invalid_service");
            require(postingPackage != null && service.substring(0, service.indexOf('/')).equals(postingPackage), "foreign_service");
            text(content, "token", 0, 256, false);
        }
    }

    private static void slot(JSONObject slot, boolean small) throws JSONException {
        choice(slot, "type", small ? "pic progress" : "pic text timer countdown progress number", true);
        String type = slot.getString("type");
        switch (type) {
            case "pic":
                keys(slot, "type pic animated contentDescription");
                key(slot, "pic", true);
                choice(slot, "animated", "none working listening", false);
                text(slot, "contentDescription", 0, 80, false);
                break;
            case "text":
                keys(slot, "type text pic emphasis");
                text(slot, "text", 1, 16, true);
                choice(slot, "emphasis", "normal accent muted", false);
                break;
            case "timer":
            case "countdown":
                String field = type.equals("timer") ? "startEpochMs" : "endEpochMs";
                keys(slot, "type pic " + field);
                // Keep integer arithmetic exact across native/JS producers.
                integer(slot, field, 0, 9007199254740991L, true);
                break;
            case "progress":
                keys(slot, "type value pic");
                number(slot, "value", 0, 1);
                break;
            case "number":
                keys(slot, "type value unit pic");
                text(slot, "value", 1, 3, true);
                require(slot.getString("value").matches("[0-9]{1,3}"), "invalid_digits");
                text(slot, "unit", 0, 4, false);
                break;
            default: throw new IllegalArgumentException("unsupported_slot");
        }
        key(slot, "pic", false);
    }

    private static void keys(JSONObject object, String allowed) {
        Set<String> fields = new HashSet<>(Arrays.asList(allowed.split(" ")));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) require(fields.contains(keys.next()), "unknown_field");
    }

    private static JSONObject object(JSONObject value, String field) throws JSONException {
        Object result = value.get(field);
        require(result instanceof JSONObject, "expected_object");
        return (JSONObject) result;
    }

    private static JSONArray array(JSONObject value, String field, int max) throws JSONException {
        Object result = value.get(field);
        require(result instanceof JSONArray, "expected_array");
        JSONArray array = (JSONArray) result;
        require(array.length() <= max, "too_many_items");
        return array;
    }

    private static void text(JSONObject value, String field, int min, int max, boolean required) throws JSONException {
        if (!required && !value.has(field)) return;
        Object raw = value.get(field);
        require(raw instanceof String, "expected_string");
        String text = (String) raw;
        int count = text.codePointCount(0, text.length());
        require(count >= min && count <= max, "text_length");
    }

    private static boolean validKey(String key) { return key.matches("[a-z][a-z0-9_]{0,31}"); }

    private static void key(JSONObject value, String field, boolean required) throws JSONException {
        text(value, field, 1, 32, required);
        if (value.has(field)) require(validKey(value.getString(field)), "invalid_key");
    }

    private static void choice(JSONObject value, String field, String choices, boolean required) throws JSONException {
        text(value, field, 1, 32, required);
        if (value.has(field)) require(Arrays.asList(choices.split(" ")).contains(value.getString(field)), "invalid_enum");
    }

    private static void number(JSONObject value, String field, double min, double max) throws JSONException {
        if (!value.has(field)) return;
        Object raw = value.get(field);
        require(raw instanceof Number, "expected_number");
        double number = ((Number) raw).doubleValue();
        require(Double.isFinite(number) && number >= min && number <= max, "number_range");
    }

    private static void integer(JSONObject value, String field, long min, long max, boolean required) throws JSONException {
        if (!required && !value.has(field)) return;
        require(value.has(field), "missing_integer");
        number(value, field, min, max);
        double number = ((Number) value.get(field)).doubleValue();
        require(number == Math.floor(number), "expected_integer");
    }

    private static void bool(JSONObject value, String field) throws JSONException {
        if (value.has(field)) require(value.get(field) instanceof Boolean, "expected_boolean");
    }

    private static void require(boolean condition, String error) {
        if (!condition) throw new IllegalArgumentException(error);
    }

    private static void checkDepth(String json) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '{' || character == '[') require(++depth <= 8, "too_deep");
            else if (character == '}' || character == ']') require(--depth >= 0, "unbalanced_json");
        }
        require(!quoted && depth == 0, "unbalanced_json");
    }

    private static Object readJson(JsonReader reader) throws IOException, JSONException {
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(!object.has(name), "duplicate_field");
                    object.put(name, readJson(reader));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) array.put(readJson(reader));
                reader.endArray();
                return array;
            case STRING: return reader.nextString();
            case BOOLEAN: return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return JSONObject.NULL;
            case NUMBER:
                String number = reader.nextString();
                require(number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"), "invalid_number");
                double parsed = Double.parseDouble(number);
                require(Double.isFinite(parsed), "nonfinite_number");
                return parsed;
            default: throw new IllegalArgumentException("invalid_json_token");
        }
    }

    private IslandPayload() {}
}
