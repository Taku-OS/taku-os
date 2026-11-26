package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Bounded, display-only copy of a native thread. The app-server keeps the canonical history. */
final class AgentViewProjection {
    static final int MAX_BYTES = 1024 * 1024;
    static final List<String> METADATA_FIELDS = List.of(
            "id", "name", "preview", "createdAt", "updatedAt", "status", "model", "modelProvider", "source");
    private static final int MAX_TURNS = 80;
    private static final int MAX_ITEMS = 240;
    private static final int MAX_TEXT_UNITS = 60_000;
    private static final int MAX_NODES = 6_000;
    private static final int MAX_FIELD_UNITS = 12_000;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ARRAY_ITEMS = 40;
    private static final int MAX_OBJECT_FIELDS = 48;
    // Reasoning summaries have a budget of their own, so a long train of thought never folds an answer.
    private static final int MAX_REASONING_UNITS = 24_000;
    private static final int MAX_REASONING_FIELD_UNITS = 8_000;
    private static final int MAX_REASONING_PARTS = 64;
    private static final String TRUNCATED = "…(Middle content collapsed; the native conversation retains the complete content)…";
    private static final String OMITTED_IMAGE = "[Image data omitted; the native conversation retains the complete content]";

    private static final class Budget {
        int text = MAX_TEXT_UNITS;
        int reasoning = MAX_REASONING_UNITS;
        int nodes = MAX_NODES;
        int omittedText;
        int omittedPayloads;
        int omittedItems;
        int omittedTurns;
    }

    private AgentViewProjection() {}

    /** Metadata responses deliberately omit history; their empty turns are not a replacement. */
    static JSONObject mergeMetadata(JSONObject previous, JSONObject metadata) throws JSONException, IOException {
        if (metadata == null || !isId(metadata.optString("id")))
            throw new IOException("Conversation data has no valid identifier");
        if (previous != null && !metadata.getString("id").equals(previous.optString("id")))
            throw new IOException("Conversation metadata does not match the existing history identifier");
        JSONObject merged = previous == null ? new JSONObject() : new JSONObject(previous.toString());
        for (String key : METADATA_FIELDS)
            if (metadata.has(key)) merged.put(key, metadata.opt(key));
        if (previous == null) merged.put("historyNotLoaded", true);
        return project(merged);
    }

    /** Merge an explicitly paged history response by native IDs, retaining every known item. */
    static JSONObject mergeTurnPage(JSONObject previous, JSONArray page, boolean descending,
            boolean prepend, boolean fullItems) throws JSONException, IOException {
        if (previous == null || page == null) throw new IOException("Conversation history pagination data is missing");
        JSONObject merged = new JSONObject(previous.toString());
        JSONArray turns = merged.optJSONArray("turns");
        if (turns == null) turns = new JSONArray();
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < turns.length(); i++) ordered.add(turns.getJSONObject(i));
        List<JSONObject> incoming = new ArrayList<>();
        for (int i = 0; i < page.length(); i++) {
            JSONObject turn = page.getJSONObject(descending ? page.length() - 1 - i : i);
            if (turn.optString("id").isEmpty()) throw new IOException("History page has no native turn identifier");
            incoming.add(turn);
        }
        int previousAt = -1;
        for (int i = 0; i < incoming.size(); i++) {
            JSONObject source = incoming.get(i);
            int at = indexOf(ordered, source.getString("id"));
            if (at < 0) {
                int nextAt = -1;
                for (int j = i + 1; j < incoming.size() && nextAt < 0; j++)
                    nextAt = indexOf(ordered, incoming.get(j).getString("id"));
                at = nextAt >= 0 ? nextAt : previousAt >= 0 ? previousAt + 1 : prepend ? 0 : ordered.size();
                ordered.add(at, new JSONObject().put("id", source.getString("id")).put("items", new JSONArray()));
            }
            JSONObject target = ordered.get(at);
            if (source.has("status")) target.put("status", source.opt("status"));
            JSONArray items = target.optJSONArray("items");
            List<JSONObject> retainedItems = new ArrayList<>();
            if (items != null) for (int j = 0; j < items.length(); j++) retainedItems.add(items.getJSONObject(j));
            JSONArray received = source.optJSONArray("items");
            int previousItemAt = -1;
            if (received != null) for (int j = 0; j < received.length(); j++) {
                JSONObject item = received.getJSONObject(j);
                String id = item.optString("id");
                if (id.isEmpty()) throw new IOException("History page has no native record identifier");
                int found = indexOf(retainedItems, id);
                if (found < 0) {
                    int nextItemAt = -1;
                    for (int k = j + 1; k < received.length() && nextItemAt < 0; k++)
                        nextItemAt = indexOf(retainedItems, received.getJSONObject(k).optString("id"));
                    found = nextItemAt >= 0 ? nextItemAt : previousItemAt >= 0 ? previousItemAt + 1 : retainedItems.size();
                    retainedItems.add(found, new JSONObject(item.toString()));
                } else {
                    // Only an explicit full-items page may replace retained display detail.
                    JSONObject retained = retainedItems.get(found);
                    for (Iterator<String> keys = item.keys(); keys.hasNext();) {
                        String key = keys.next();
                        if (fullItems || !retained.has(key) || key.equals("status")) retained.put(key, item.opt(key));
                    }
                }
                previousItemAt = found;
            }
            JSONArray retained = new JSONArray();
            for (JSONObject item : retainedItems) retained.put(item);
            target.put("items", retained);
            previousAt = at;
        }
        JSONArray history = new JSONArray();
        for (JSONObject turn : ordered) history.put(turn);
        merged.put("turns", history);
        // A page does not establish the total history boundary or unique omission count.
        merged.put("historyOmissionCountUnknown", true);
        JSONObject bounded = project(merged);
        for (String key : new String[]{"historyOmittedTurns", "historyOmittedItems", "historyOmittedText", "historyOmittedPayloads"}) {
            int before = Math.max(0, previous.optInt(key));
            bounded.put(key, Math.max(before, bounded.optInt(key) - before));
        }
        return bounded;
    }

    private static int indexOf(List<JSONObject> turns, String id) {
        for (int i = 0; i < turns.size(); i++) if (id.equals(turns.get(i).optString("id"))) return i;
        return -1;
    }

    static JSONObject project(JSONObject source) throws JSONException, IOException {
        if (source == null || !isId(source.optString("id")))
            throw new IOException("Conversation data has no valid identifier");

        Budget budget = new Budget();
        boolean priorProjection = source.optInt("_agentViewVersion") == 1;
        if (priorProjection) {
            budget.omittedTurns = Math.max(0, source.optInt("historyOmittedTurns"));
            budget.omittedItems = Math.max(0, source.optInt("historyOmittedItems"));
            budget.omittedText = Math.max(0, source.optInt("historyOmittedText"));
            budget.omittedPayloads = Math.max(0, source.optInt("historyOmittedPayloads"));
        }
        JSONObject view = new JSONObject();
        for (String key : new String[] {"id", "name", "preview", "createdAt", "updatedAt", "status", "model", "modelProvider"})
            if (source.has(key)) view.put(key, boundedScalar(source.opt(key), key, budget, 0));
        // Native routing metadata must survive display text/payload budgets unchanged.
        if (source.has("source")) {
            Object origin = source.get("source");
            if (origin instanceof JSONObject) origin = new JSONObject(origin.toString());
            view.put("source", origin);
        }

        JSONArray nativeTurns = source.optJSONArray("turns");
        if (source.has("turns") && nativeTurns == null)
            throw new IOException("Invalid conversation history structure");
        if (nativeTurns == null) nativeTurns = new JSONArray();

        JSONArray turns = new JSONArray();
        int start = Math.max(0, nativeTurns.length() - MAX_TURNS);
        budget.omittedTurns += start;
        int remainingItems = MAX_ITEMS;
        for (int i = nativeTurns.length() - 1; i >= start; i--) {
            Object rawTurn = nativeTurns.opt(i);
            if (!(rawTurn instanceof JSONObject)) {
                budget.omittedTurns++;
                continue;
            }
            JSONObject nativeTurn = (JSONObject) rawTurn;
            JSONArray nativeItems = nativeTurn.optJSONArray("items");
            if (nativeTurn.has("items") && nativeItems == null) {
                budget.omittedTurns++;
                continue;
            }
            if (nativeItems == null) nativeItems = new JSONArray();

            JSONObject turn = new JSONObject();
            for (String key : new String[] {"id", "status"})
                if (nativeTurn.has(key)) turn.put(key, boundedScalar(nativeTurn.opt(key), key, budget, 0));

            JSONArray items = new JSONArray();
            int itemStart = Math.max(0, nativeItems.length() - remainingItems);
            int omittedHere = itemStart;
            for (int j = nativeItems.length() - 1; j >= itemStart; j--) {
                Object rawItem = nativeItems.opt(j);
                if (!(rawItem instanceof JSONObject)) {
                    omittedHere++;
                    continue;
                }
                if (budget.nodes <= 0) {
                    omittedHere += j - itemStart + 1;
                    break;
                }
                JSONObject item = projectItem((JSONObject) rawItem, budget);
                items.put(item);
                remainingItems--;
            }
            budget.omittedItems += omittedHere;
            reverse(items);
            turn.put("items", items);
            turns.put(turn);
        }
        reverse(turns);
        view.put("turns", turns);
        if (source.optBoolean("historyNotLoaded")) view.put("historyNotLoaded", true);
        if (source.optBoolean("historyOmissionCountUnknown")) view.put("historyOmissionCountUnknown", true);
        view.put("historyOmittedTurns", budget.omittedTurns);
        view.put("historyOmittedItems", budget.omittedItems);
        view.put("historyOmittedText", budget.omittedText);
        view.put("historyOmittedPayloads", budget.omittedPayloads);
        view.put("_agentViewVersion", 1);

        String serialized = view.toString();
        while (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES && turns.length() > 0) {
            if (turns.length() > 1) {
                JSONObject removed = turns.optJSONObject(0);
                if (removed != null) budget.omittedItems += removed.optJSONArray("items") == null ? 0 : removed.getJSONArray("items").length();
                turns.remove(0);
                budget.omittedTurns++;
            } else {
                JSONObject latest = turns.optJSONObject(0);
                JSONArray latestItems = latest == null ? null : latest.optJSONArray("items");
                if (latestItems != null && latestItems.length() > 0) {
                    latestItems.remove(0);
                    budget.omittedItems++;
                } else {
                    turns.remove(0);
                    budget.omittedTurns++;
                }
            }
            view.put("historyOmittedTurns", budget.omittedTurns);
            view.put("historyOmittedItems", budget.omittedItems);
            serialized = view.toString();
        }
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("Conversation display content exceeds the safety limit");
        return view;
    }

    private static JSONObject projectItem(JSONObject source, Budget budget) throws JSONException {
        JSONObject item = new JSONObject();
        budget.nodes--;
        String[] fields = {
            "id", "type", "status", "success", "exitCode", "phase", "text", "command",
            "server", "tool", "namespace", "arguments", "aggregatedOutput", "contentItems",
            "result", "changes", "error", "content"
        };
        for (String key : fields) {
            if (!source.has(key) || budget.nodes <= 0) continue;
            Object value = source.opt(key);
            Object bounded = boundedValue(value, key, budget, 0);
            if (bounded != null) item.put(key, bounded);
        }
        if ("reasoning".equals(source.optString("type"))) {
            JSONArray summary = reasoningSummary(source.optJSONArray("summary"), budget);
            if (summary != null) item.put("summary", summary);
            for (String key : new String[] {"startedAtMs", "durationMs"})
                if (source.opt(key) instanceof Number) item.put(key, source.get(key));
        }
        return item;
    }

    /** Applies the display budget to the current indexed view without replacing its items. */
    static void boundReasoningSummaries(JSONObject view) throws JSONException {
        JSONArray turns = view.getJSONArray("turns");
        Budget budget = new Budget();
        for (int i = turns.length() - 1; i >= 0; i--) {
            JSONArray items = turns.getJSONObject(i).getJSONArray("items");
            for (int j = items.length() - 1; j >= 0; j--) {
                JSONObject item = items.getJSONObject(j);
                if (!"reasoning".equals(item.optString("type"))) continue;
                item.put("summary", reasoningSummary(item.optJSONArray("summary"), budget));
            }
        }
    }

    /**
     * The model's public reasoning summary, newest turns first within its own budget. Null once that
     * budget is spent: the item stays, without the text. Raw reasoning content is not a display field.
     */
    private static JSONArray reasoningSummary(JSONArray source, Budget budget) throws JSONException {
        if (source == null) return null;
        JSONArray summary = new JSONArray();
        for (int i = 0; i < source.length() && i < MAX_REASONING_PARTS && budget.nodes > 0; i++) {
            if (!(source.opt(i) instanceof String)) continue;
            budget.nodes--;
            String part = (String) source.opt(i);
            int allowance = Math.min(MAX_REASONING_FIELD_UNITS, budget.reasoning);
            if (part.length() > allowance) {
                if (allowance <= TRUNCATED.length()) return summary.length() > 0 ? summary : null;
                int keep = allowance - TRUNCATED.length(), head = keep / 2;
                part = part.substring(0, head) + TRUNCATED + part.substring(part.length() - (keep - head));
            }
            budget.reasoning -= part.length();
            summary.put(part);
        }
        return summary;
    }

    private static Object boundedScalar(Object value, String key, Budget budget, int depth)
            throws JSONException {
        return boundedValue(value, key, budget, depth);
    }

    private static Object boundedValue(Object value, String key, Budget budget, int depth)
            throws JSONException {
        if (budget.nodes <= 0) return null;
        budget.nodes--;
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof String) return boundedText((String) value, key, budget);
        if (depth >= MAX_DEPTH || budget.nodes <= 0) return null;

        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            String type = source.optString("type", "");
            if (type.equals("image") || type.equals("inputImage")) {
                String text = source.optString("text", "");
                if (!(text.startsWith("[Image data omitted") || text.startsWith("[图片数据已省略"))) budget.omittedPayloads++;
                JSONObject marker = new JSONObject().put("type", type)
                        .put("text", boundedText((text.startsWith("[Image data omitted") || text.startsWith("[图片数据已省略")) ? text : OMITTED_IMAGE, "text", budget));
                if (source.has("mimeType")) marker.put("mimeType", boundedValue(source.opt("mimeType"), "mimeType", budget, depth + 1));
                return marker;
            }
            if (type.equals("localImage")) {
                JSONObject marker = new JSONObject().put("type", type);
                String path = source.optString("path", "");
                int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                if (!path.isEmpty()) marker.put("path", boundedText(path.substring(slash + 1), "path", budget));
                if (source.has("mimeType")) marker.put("mimeType", boundedValue(source.opt("mimeType"), "mimeType", budget, depth + 1));
                return marker;
            }
            JSONObject copy = new JSONObject();
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = source.keys(); it.hasNext();) keys.add(it.next());
            Collections.sort(keys, (a, b) -> fieldRank(a) - fieldRank(b));
            int copied = 0;
            for (String child : keys) {
                if (copied >= MAX_OBJECT_FIELDS || budget.nodes <= 0) break;
                if (child.length() > 128 || budget.text < child.length()) {
                    budget.omittedText++;
                    continue;
                }
                budget.text -= child.length();
                Object childValue = source.opt(child);
                if (isBinaryValue(child, childValue)) {
                    budget.omittedPayloads++;
                    continue;
                }
                Object bounded = boundedValue(childValue, child, budget, depth + 1);
                if (bounded != null) {
                    copy.put(child, bounded);
                    copied++;
                }
            }
            return copy;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray copy = new JSONArray();
            int start = Math.max(0, source.length() - MAX_ARRAY_ITEMS);
            if (start > 0) budget.omittedItems += start;
            for (int i = start; i < source.length() && budget.nodes > 0; i++) {
                Object bounded = boundedValue(source.opt(i), key, budget, depth + 1);
                if (bounded != null) copy.put(bounded);
            }
            return copy;
        }
        return null;
    }

    private static Object boundedText(String value, String key, Budget budget) {
        if (value.isEmpty()) return value;
        if (isDataUri(value) || isBinaryValue(key, value)
                || value.length() > 4096 && looksBase64(value)) {
            budget.omittedPayloads++;
            return OMITTED_IMAGE;
        }
        int allowance = Math.min(MAX_FIELD_UNITS, budget.text);
        if (value.length() <= allowance) {
            budget.text -= value.length();
            return value;
        }
        boolean alreadyFolded = value.contains(TRUNCATED) || value.contains("…（中间内容已折叠") || value.startsWith("…(Earlier content collapsed") || value.startsWith("…（前文已折叠");
        if (!alreadyFolded) budget.omittedText++;
        if (allowance <= 0) return "…";
        String result;
        if (key.equals("aggregatedOutput") || key.equals("diff")) {
            String suffix = "…(Earlier content collapsed; the native conversation retains the complete content)";
            int keep = Math.max(0, allowance - suffix.length());
            result = suffix + value.substring(Math.max(0, value.length() - keep));
        } else {
            int markerLength = Math.min(TRUNCATED.length(), allowance);
            int keep = allowance - markerLength;
            int head = keep / 2;
            int tail = keep - head;
            result = value.substring(0, head) + TRUNCATED.substring(0, markerLength)
                    + value.substring(Math.max(head, value.length() - tail));
        }
        budget.text -= result.length();
        return result;
    }

    private static boolean isBinaryValue(String key, Object value) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if (!(normalized.equals("image") || normalized.equals("images") || normalized.equals("bytes")
                || normalized.equals("base64") || normalized.equals("blob") || normalized.equals("imagedata")
                || normalized.equals("data")))
            return false;
        if (!(value instanceof String)) return !normalized.equals("data");
        String text = (String) value;
        return text.length() > 1024 && (normalized.equals("image") || normalized.equals("images")
                || normalized.equals("bytes") || normalized.equals("base64") || normalized.equals("blob")
                || normalized.equals("imagedata") || looksBase64(text)) || isDataUri(text);
    }

    private static boolean isDataUri(String value) {
        String lower = value.length() > 48 ? value.substring(0, 48).toLowerCase(java.util.Locale.ROOT) : value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("data:image/") || lower.startsWith("data:application/octet-stream")
                || lower.startsWith("data:audio/") || lower.startsWith("data:video/");
    }

    private static boolean looksBase64(String value) {
        int checked = Math.min(value.length(), 512);
        int valid = 0;
        for (int i = 0; i < checked; i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '\r' || c == '\n') valid++;
        }
        return checked > 0 && valid * 100 / checked >= 98;
    }

    private static int fieldRank(String key) {
        switch (key) {
            case "type": return 0;
            case "text": return 1;
            case "path": return 2;
            case "mimeType": return 3;
            default: return 10;
        }
    }

    private static void reverse(JSONArray array) {
        List<Object> values = new ArrayList<>();
        for (int i = array.length() - 1; i >= 0; i--) values.add(array.opt(i));
        while (array.length() > 0) array.remove(array.length() - 1);
        for (Object value : values) array.put(value);
    }

    private static boolean isId(String id) {
        return id != null && id.matches("[a-f0-9-]{36}");
    }
}
