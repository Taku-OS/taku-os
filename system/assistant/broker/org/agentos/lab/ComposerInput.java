package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ComposerInput {
    private ComposerInput() {}

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
