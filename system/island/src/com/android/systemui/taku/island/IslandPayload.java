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
 * Bounded validation of the Taku Island extras ({@code taku.island.v2}, the only protocol). No
 * class loading, URI fetching, action execution or view inflation. {@link #parse} returns the
 * validated payload SystemUI renders.
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

    /** Validates {@code taku.island.v2}. The result is the payload SystemUI renders. */
    public static Result parse(String json, Publisher publisher) {
        try {
            return validate(read(json), publisher);
        } catch (IllegalArgumentException error) {
            return new Result(null, code(error));
        } catch (JSONException | IOException | IllegalStateException error) {
            return new Result(null, "invalid_payload");
        }
    }

    /** Only our own identifier-style codes reach diagnostics; never parser or platform text. */
    private static String code(IllegalArgumentException error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z][a-z_]{0,47}") ? message : "invalid_payload";
    }

    /** Strict reader: size, depth, duplicate keys and number grammar. */
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

    private static Result validate(JSONObject value, Publisher publisher) throws JSONException {
        keys(value, "version compact expanded tap cardPosition alert timeoutS staleAfterS");
        integer(value, "version", 2, 2, true);
        if (value.has("compact")) {
            JSONObject compact = object(value, "compact");
            keys(compact, "a b");
            require(compact.length() > 0, "empty_compact");
            if (compact.has("a")) slot(object(compact, "a"), true, publisher);
            if (compact.has("b")) slot(object(compact, "b"), false, publisher);
        }
        expanded(object(value, "expanded"), publisher);
        choice(value, "tap", "expand open", false);
        choice(value, "cardPosition", "cover below", false);
        bool(value, "alert");
        integer(value, "timeoutS", 1, MAX_LIFETIME_S, false);
        integer(value, "staleAfterS", 1, MAX_LIFETIME_S, false);
        return new Result(value, null);
    }

    private static void slot(JSONObject slot, boolean groupA, Publisher publisher) throws JSONException {
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

    private static void expanded(JSONObject value, Publisher publisher) throws JSONException {
        keys(value, "template title body pic steps progress highlight actions primaryAction tapAction accentColor backgroundColor assistant content");
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
        // A template style: the card's background (white, say). Text and controls follow it.
        int background = CARD_RGB;
        if (value.has("backgroundColor")) {
            background = color(value, "backgroundColor");
            require(!template.equals("assistant"), "reserved_template");
        }
        if (value.has("accentColor")) {
            require(contrast(color(value, "accentColor"), background) >= 4.5, "low_contrast");
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

    /** A `#RRGGBB` field as 0xRRGGBB. */
    private static int color(JSONObject value, String name) throws JSONException {
        text(value, name, 7, 7, true);
        String color = value.getString(name);
        require(color.matches("#[0-9A-Fa-f]{6}"), "invalid_color");
        return Integer.parseInt(color.substring(1), 16);
    }

    /** WCAG 2 contrast ratio between two sRGB colours. */
    static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    /** WCAG 2 relative luminance of 0xRRGGBB (any alpha bits are ignored). */
    public static double luminance(int rgb) {
        double[] channels = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        for (int i = 0; i < 3; i++) {
            double c = channels[i] / 255;
            channels[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
        }
        return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
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
