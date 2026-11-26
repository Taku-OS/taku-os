package org.agentos.lab;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NotebookVideoProjectionProbe {
    public static void main(String[] args) throws Exception {
        String imagePayload = "data:image/jpeg;base64,QUJD";
        JSONArray frames = new JSONArray();
        for (int i = 0; i < 32; i++) {
            frames.put(new JSONObject().put("requestedTimeMs", i * 500).put("sha256", "a".repeat(64)));
        }
        String receipt = new JSONObject().put("schema", "taku.notebook.video/1").put("ok", true)
                .put("referenceId", "b".repeat(64)).put("postId", "1234567890123456789")
                .put("sourceUrl", "https://x.com/example/status/1234567890123456789").put("frames", frames).toString();
        var content = new JSONArray().put(new JSONObject().put("type", "inputText").put("text", receipt))
                .put(new JSONObject().put("type", "inputImage").put("imageUrl", imagePayload));
        for (String tool : new String[]{"notebook_read_video", "taku_phone.notebook_read_video"}) {
            var item = new JSONObject().put("id", "tool-fixture").put("type", "dynamicToolCall")
                    .put("tool", tool).put("success", true).put("status", "completed").put("contentItems", content);
            if (tool.startsWith("notebook_")) item.put("namespace", "taku_phone");
            var turn = new JSONObject().put("id", "turn-fixture").put("status", "completed")
                    .put("items", new JSONArray().put(item));
            var thread = new JSONObject().put("id", "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1")
                    .put("turns", new JSONArray().put(turn));
            JSONObject projected = AgentViewProjection.project(thread).getJSONArray("turns").getJSONObject(0)
                    .getJSONArray("items").getJSONObject(0);
            if (!receipt.equals(projected.getJSONArray("contentItems").getJSONObject(0).getString("text"))
                    || projected.toString().contains(imagePayload)) {
                throw new AssertionError("Exact video receipt should survive projection without binary frames");
            }
        }
        System.out.println("NotebookVideoProjectionProbe: exact receipts survived and image bytes were omitted");
    }
}
