package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ComposerInput {
    private ComposerInput() {}

    /** Longest card JSON a panel note carries before it is cut with {@link #PANEL_TRUNCATED}. */
    public static final int PANEL_CONTENT_LIMIT = 24_000;
    /** Longest whole panel note (first line plus content) a task request may carry. */
    public static final int PANEL_CONTEXT_LIMIT = 25_000;
    public static final String PANEL_TRUNCATED = "\n[panel content truncated]";
    /**
     * First line of the panel note, then a newline and the card JSON. Chat projections hide a text
     * input that matches this, as they hide {@link #appendPlugins}'s note: it is not user-typed text.
     */
    public static final Pattern PANEL_NOTE = Pattern.compile(
            "The user is editing the pinned panel \".*\" \\(panelId: [0-9a-f-]{36}, matterId: [0-9a-f-]{36}/[a-z0-9-]{1,64}, "
                    + "sourceVersion: [0-9]{1,16}\\)\\. Update this panel in your reply\\. Its current content:\n(?s:.*)");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern MATTER = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[a-z0-9-]{1,64}");

    /**
     * The model-facing note for a panel update: which panel is edited and its current card
     * ({@code content}, compact JSON). Capability refs ({@code actionRef}) are dropped, control
     * characters other than newlines are removed and the JSON is cut at {@link #PANEL_CONTENT_LIMIT}.
     */
    public static String panelContext(String title, String panelId, String matterId, long sourceVersion, JSONObject content) throws Exception {
        if (!UUID.matcher(panelId).matches() || !MATTER.matcher(matterId).matches() || sourceVersion < 0 || sourceVersion > 9_007_199_254_740_991L)
            throw new IOException("INVALID_ARGUMENT");
        String name = clean(title, false).trim();
        if (name.isEmpty()) name = "Panel";
        if (name.codePointCount(0, name.length()) > 120) name = name.substring(0, name.offsetByCodePoints(0, 120));
        String json = clean(withoutCapabilities(new JSONObject(content.toString())).toString(), true);
        if (json.length() > PANEL_CONTENT_LIMIT)
            json = json.substring(0, cut(json, PANEL_CONTENT_LIMIT - PANEL_TRUNCATED.length())) + PANEL_TRUNCATED;
        return "The user is editing the pinned panel \"" + name + "\" (panelId: " + panelId + ", matterId: " + matterId
                + ", sourceVersion: " + sourceVersion + "). Update this panel in your reply. Its current content:\n" + json;
    }

    /** Validates a request's {@code panelContext}: a bounded, clean string in the {@link #PANEL_NOTE} format. */
    public static String checkPanelContext(Object value) throws IOException {
        if (!(value instanceof String)) throw new IOException("INVALID_ARGUMENT");
        String text = (String) value;
        if (text.length() > PANEL_CONTEXT_LIMIT || !text.equals(clean(text, true)) || !isPanelContext(text)) throw new IOException("INVALID_ARGUMENT");
        return text;
    }

    /** Copies a validated {@code panelContext} into a task request; only a panel update carries one, describing that same target. */
    public static void copyPanelContext(JSONObject options, JSONObject request) throws Exception {
        if (!options.has("panelContext")) return;
        JSONObject target = options.optJSONObject("panelUpdate");
        if (target == null) throw new IOException("INVALID_ARGUMENT");
        String text = checkPanelContext(options.get("panelContext"));
        String bound = "\" (panelId: " + target.optString("panelId") + ", matterId: " + target.optString("matterId") + ", sourceVersion: "
                + target.opt("sourceVersion") + "). Update this panel in your reply. Its current content:";
        if (!text.substring(0, text.indexOf('\n')).endsWith(bound)) throw new IOException("INVALID_ARGUMENT");
        request.put("panelContext", text);
    }

    public static boolean isPanelContext(String text) {
        return text != null && PANEL_NOTE.matcher(text).matches();
    }

    /** One text item with the edited panel's context, right after the user's text; nothing for ordinary turns. */
    public static void appendPanelContext(JSONArray input, String context) throws Exception {
        if (context == null || context.isEmpty()) return;
        input.put(new JSONObject().put("type", "text").put("text", checkPanelContext(context)));
    }

    private static Object withoutCapabilities(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            object.remove("actionRef");
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) withoutCapabilities(object.get(keys.next()));
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) withoutCapabilities(array.get(index));
        }
        return value;
    }

    /**
     * Drops ISO control characters (newlines kept only when {@code newlines}, else a space), format
     * controls such as bidi overrides (ZWJ kept for emoji) and lone surrogates; line/paragraph separators become spaces.
     */
    private static String clean(String text, boolean newlines) {
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (Character.isHighSurrogate(c) && index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                out.append(c).append(text.charAt(++index));
            } else if (c == '\n') {
                out.append(newlines ? '\n' : ' ');
            } else if (c == '\u2028' || c == '\u2029') {
                out.append(' ');
            } else if (!Character.isISOControl(c) && !Character.isSurrogate(c) && (c == '\u200d' || Character.getType(c) != Character.FORMAT)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A cut point at or below {@code limit} that does not split a surrogate pair. */
    private static int cut(String text, int limit) {
        return limit > 0 && Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
    }

    public static JSONArray text(String text) throws Exception {
        JSONArray input = new JSONArray();
        if (!text.isBlank()) input.put(new JSONObject().put("type", "text").put("text", text));
        return input;
    }

    /** One text item per plugin the user attached with `@`, after the user's own text. */
    public static void appendPlugins(JSONArray input, JSONArray plugins) throws Exception {
        if (plugins == null) return;
        if (plugins.length() > 4) throw new IOException("PLUGIN_LIMIT");
        for (int index = 0; index < plugins.length(); index++) {
            JSONObject item = plugins.getJSONObject(index);
            StringBuilder text = new StringBuilder("The user attached the plugin \"").append(item.getString("name"))
                    .append("\" (id: ").append(item.getString("id")).append(") to this message.");
            JSONArray tools = item.optJSONArray("tools");
            if (tools != null && tools.length() > 0) {
                text.append(" Prefer this plugin's tools for this request: ");
                for (int tool = 0; tool < tools.length(); tool++) text.append(tool == 0 ? "" : ", ").append(tools.getString(tool));
                text.append('.');
            }
            input.put(new JSONObject().put("type", "text").put("text", text.toString()));
        }
    }

    public static void appendAttachments(JSONArray input, JSONArray attachments) throws Exception {
        if (attachments == null) return;
        if (attachments.length() > 8) throw new IOException("ATTACHMENT_LIMIT");
        File root = new File("/data/user/0/org.agentos.assistant/files/composer-attachments").getCanonicalFile();
        for (int index = 0; index < attachments.length(); index++) {
            JSONObject item = attachments.getJSONObject(index);
            File file = new File(item.getString("path")).getCanonicalFile();
            if (!root.equals(file.getParentFile()) || !file.isFile() || !file.canRead() || file.length() > 20_000_000)
                throw new IOException("ATTACHMENT_UNAVAILABLE");
            boolean image = item.optString("mime").startsWith("image/");
            input.put(image ? new JSONObject().put("type", "localImage").put("path", file.getPath())
                    : new JSONObject().put("type", "text").put("text", "User-selected attachment: " + item.getString("name") + "\nLocal path: " + file.getPath()));
        }
    }
}
