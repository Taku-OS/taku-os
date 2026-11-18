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

/** Bounded draft-v1 validation. No class loading, URI fetching, action execution or view inflation. */
public final class IslandPayload {
    public static final int MAX_BYTES = 8192;

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
