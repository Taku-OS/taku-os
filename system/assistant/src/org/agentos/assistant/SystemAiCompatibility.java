package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

/** Match a scene against the same effective group capabilities the broker resolves. */
final class SystemAiCompatibility {
  private SystemAiCompatibility() {}

  static boolean supports(String scene, String id, JSONArray catalog) {
    if (catalog == null) return false;
    for (int i = 0; i < catalog.length(); i++) {
      JSONObject model = catalog.optJSONObject(i);
      if (model == null || !id.equals(model.optString("id"))) continue;
      boolean decision = "decision".equals(model.optString("kind"));
      if ("jev".equals(scene)) return decision && model.optBoolean("textInput");
      if ("memory.text".equals(scene)) return model.optBoolean("textInput");
      if (decision) return false;
      return model.optBoolean("ime.vision".equals(scene) || "screen".equals(scene)
          ? "imageInput" : "textInput");
    }
    return false;
  }
}
