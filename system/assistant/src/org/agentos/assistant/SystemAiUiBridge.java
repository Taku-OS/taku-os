package org.agentos.assistant;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import java.io.IOException;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Narrow UI adapter over the same Settings values used by the native settings screen and CLI. */
final class SystemAiUiBridge {
  private static final String CONFIG = "taku_system_ai_config", GROUPS = "groups", SCENES = "scenes";
  private static final Set<String> SCENE_IDS = Set.of(
      "assistant", "org.agentos.assistant.text", "ime.vision", "screen", "memory.text", "jev");
  private SystemAiUiBridge() {}
  private static JSONObject config(Context c) throws Exception {
    String raw = Settings.Global.getString(c.getContentResolver(), CONFIG);
    if (raw == null) throw new IOException("CONFIG_NOT_READY");
    return new JSONObject(raw);
  }
  private static String value(Context c, String field, String fallback) throws Exception {
    return config(c).optString(field, fallback);
  }
  private static void put(Context c, String field, String value) throws Exception {
    JSONObject latest = config(c);
    latest.put(field, GROUPS.equals(field) ? new JSONArray(value) : new JSONObject(value));
    latest.put("updated_at", System.currentTimeMillis()).put("updated_by", "settings");
    if (!Settings.Global.putString(c.getContentResolver(), CONFIG, latest.toString()))
      throw new IOException("SETTINGS_WRITE_FAILED");
  }
  private static JSONObject group(JSONArray groups, String id) throws Exception {
    for (int i = 0; i < groups.length(); i++) {
      JSONObject g = groups.getJSONObject(i);
      if (id.equals(g.optString("id")))
        return g;
    }
    throw new IOException("GROUP_NOT_FOUND");
  }
  static synchronized JSONObject execute(Context c, String action, JSONObject input)
      throws Exception {
    switch (action) {
      case "readSystemAi": {
        JSONObject state =
            NativeClient.exchange(new JSONObject().put("op", "systemAi").put("action", "read"));
        JSONArray providers = new JSONArray(value(c, "providers", "[]")),
                  visible = new JSONArray();
        for (int i = 0; i < providers.length(); i++) {
          JSONObject p = providers.getJSONObject(i);
          visible.put(new JSONObject()
                          .put("name", p.optString("name", p.optString("preset", "Model service")))
                          .put("configured", !p.optString("credentialRef").isEmpty()));
        }
        String assistantMode = "unknown";
        try {
          assistantMode =
              NativeClient
                  .exchange(new JSONObject().put("op", "modelConnection").put("action", "read"))
                  .optString("mode", "unknown");
        } catch (Exception ignored) {
        }
        if ("openai".equals(assistantMode)) refreshAssistantModel(c);
        return new JSONObject()
            .put("assistantModel", "openai".equals(assistantMode) ? assistantModel : "")
            .put("enabled", state.optBoolean("enabled"))
            .put("connected", state.optBoolean("connected"))
            .put("assistantMode", assistantMode)
            .put("jevEnabled",
                c.getSharedPreferences("agent-settings", 0).getBoolean("jevEnabled", false))
            .put("models", state.optJSONArray("models"))
            .put("groups", new JSONArray(value(c, GROUPS, "[]")))
            .put("bindings", new JSONObject(value(c, SCENES, "{}")))
            .put("providers", visible);
      }
      case "setSystemAi": {
        Object enabled = input.get("enabled");
        if (!(enabled instanceof Boolean))
          throw new IOException("INVALID_ENABLED");
        boolean previous =
            NativeClient.exchange(new JSONObject().put("op", "systemAi").put("action", "read"))
                .getBoolean("enabled");
        NativeClient.exchange(new JSONObject()
                                  .put("op", "systemAi")
                                  .put("action", "configure")
                                  .put("patch", new JSONObject().put("enabled", enabled)));
        return receipt(
            new JSONObject().put("kind", "enabled").put("before", previous).put("after", enabled));
      }
      case "bindSystemAi": {
        String scene = input.getString("scene"), id = input.getString("group");
        if (!SCENE_IDS.contains(scene))
          throw new IOException("INVALID_SCENE");
        ensureAssistantMode(scene);
        group(new JSONArray(value(c, GROUPS, "[]")), id);
        JSONObject state = NativeClient.exchange(
            new JSONObject().put("op", "systemAi").put("action", "read"));
        if (!SystemAiCompatibility.supports(scene, "group/" + id, state.optJSONArray("models")))
          throw new IOException("Model group is unavailable or does not support this feature");
        JSONObject bindings = new JSONObject(value(c, SCENES, "{}"));
        String before = bindings.optString(scene, "");
        bindings.put(scene, "group/" + id);
        put(c, SCENES, bindings.toString());
        return receipt(new JSONObject()
                           .put("kind", "binding")
                           .put("scene", scene)
                           .put("before", before)
                           .put("after", "group/" + id));
      }
      case "bindSystemAiModel": {
        String scene = input.getString("scene"), model = input.getString("model");
        if (!SCENE_IDS.contains(scene) || model.length() > 512)
          throw new IOException("INVALID_SELECTION");
        ensureAssistantMode(scene);
        JSONObject state =
            NativeClient.exchange(new JSONObject().put("op", "systemAi").put("action", "read"));
        JSONArray catalog = state.optJSONArray("models");
        JSONObject selected = null;
        if (catalog != null)
          for (int i = 0; i < catalog.length(); i++) {
            JSONObject candidate = catalog.getJSONObject(i);
            if (model.equals(candidate.optString("id"))) {
              selected = candidate;
              break;
            }
          }
        if (selected == null)
          throw new IOException("MODEL_NOT_FOUND");
        if (!SystemAiCompatibility.supports(scene, model, catalog))
          throw new IOException("Model is unavailable or does not support this feature");
        JSONArray groups = new JSONArray(value(c, GROUPS, "[]"));
        String id = null;
        for (int i = 0; i < groups.length(); i++) {
          JSONObject g = groups.getJSONObject(i);
          JSONArray members = g.optJSONArray("members");
          if (members != null && members.length() == 1 && model.equals(members.optString(0))) {
            id = g.getString("id");
            break;
          }
        }
        if (id == null)
          throw new IOException("MODEL_GROUP_NOT_FOUND");
        JSONObject bindings = new JSONObject(value(c, SCENES, "{}"));
        String before = bindings.optString(scene, "");
        bindings.put(scene, "group/" + id);
        put(c, SCENES, bindings.toString());
        return receipt(new JSONObject()
                           .put("kind", "binding")
                           .put("scene", scene)
                           .put("before", before)
                           .put("after", "group/" + id));
      }
      case "reorderSystemAi": {
        JSONArray groups = new JSONArray(value(c, GROUPS, "[]"));
        JSONObject g = group(groups, input.getString("group"));
        JSONArray before = g.getJSONArray("members"), after = input.getJSONArray("members");
        if (before.length() != after.length())
          throw new IOException("INVALID_MEMBERS");
        java.util.List<String> a = new java.util.ArrayList<>(), b = new java.util.ArrayList<>();
        for (int i = 0; i < before.length(); i++) {
          a.add(before.getString(i));
          b.add(after.getString(i));
        }
        java.util.Collections.sort(a);
        java.util.Collections.sort(b);
        if (!a.equals(b))
          throw new IOException("INVALID_MEMBERS");
        g.put("members", after);
        put(c, GROUPS, groups.toString());
        return receipt(new JSONObject()
                           .put("kind", "members")
                           .put("group", g.getString("id"))
                           .put("before", before)
                           .put("after", after));
      }
      case "restoreSystemAi": {
        JSONObject undo = input.getJSONObject("undo");
        String kind = undo.getString("kind");
        if (kind.equals("enabled")) {
          boolean current =
              NativeClient.exchange(new JSONObject().put("op", "systemAi").put("action", "read"))
                  .getBoolean("enabled");
          if (current != undo.getBoolean("after"))
            throw new IOException("SETTINGS_CHANGED");
          NativeClient.exchange(
              new JSONObject()
                  .put("op", "systemAi")
                  .put("action", "configure")
                  .put("patch", new JSONObject().put("enabled", undo.getBoolean("before"))));
        } else if (kind.equals("binding")) {
          String scene = undo.getString("scene"), before = undo.getString("before"),
                 after = undo.getString("after");
          if (!SCENE_IDS.contains(scene))
            throw new IOException("INVALID_SCENE");
          JSONObject bindings = new JSONObject(value(c, SCENES, "{}"));
          if (!after.equals(bindings.optString(scene, "")))
            throw new IOException("SETTINGS_CHANGED");
          if (before.isEmpty())
            bindings.remove(scene);
          else {
            if (!before.startsWith("group/"))
              throw new IOException("INVALID_GROUP");
            group(new JSONArray(value(c, GROUPS, "[]")), before.substring(6));
            bindings.put(scene, before);
          }
          put(c, SCENES, bindings.toString());
        } else if (kind.equals("members")) {
          JSONArray groups = new JSONArray(value(c, GROUPS, "[]"));
          JSONObject g = group(groups, undo.getString("group"));
          JSONArray current = g.getJSONArray("members"), before = undo.getJSONArray("before"),
                    after = undo.getJSONArray("after");
          if (!current.toString().equals(after.toString()))
            throw new IOException("SETTINGS_CHANGED");
          java.util.List<String> a = new java.util.ArrayList<>(), b = new java.util.ArrayList<>();
          for (int i = 0; i < before.length(); i++) a.add(before.getString(i));
          for (int i = 0; i < after.length(); i++) b.add(after.getString(i));
          java.util.Collections.sort(a);
          java.util.Collections.sort(b);
          if (!a.equals(b))
            throw new IOException("INVALID_MEMBERS");
          g.put("members", before);
          put(c, GROUPS, groups.toString());
        } else
          throw new IOException("INVALID_UNDO");
        return new JSONObject();
      }
      case "deleteSystemAiGroup": {
        String id = input.getString("group");
        JSONArray groups = new JSONArray(value(c, GROUPS, "[]"));
        group(groups, id);
        JSONObject bindings = new JSONObject(value(c, SCENES, "{}"));
        for (java.util.Iterator<String> it = bindings.keys(); it.hasNext();)
          if (("group/" + id).equals(bindings.optString(it.next())))
            throw new IOException("GROUP_IN_USE");
        for (int i = 0; i < groups.length(); i++)
          if (id.equals(groups.getJSONObject(i).optString("id"))) {
            groups.remove(i);
            break;
          }
        put(c, GROUPS, groups.toString());
        return new JSONObject();
      }
      case "openMagpieManagement":
        c.startActivity(new Intent(c, ModelManagementActivity.class)
                            .putExtra("returnPage", "systemAi")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return new JSONObject();
      case "openAssistantConnection":
        c.startActivity(new Intent(c, PanelActivity.class)
                            .putExtra("page", "modelConnection")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return new JSONObject();
      default:
        throw new IOException("UNSUPPORTED_ACTION");
    }
  }
  private static volatile String assistantModel = "";
  private static long assistantModelRequestedAt;
  private static synchronized void refreshAssistantModel(Context c) {
    long now = android.os.SystemClock.elapsedRealtime();
    if (assistantModelRequestedAt != 0 && now - assistantModelRequestedAt < 30000) return;
    assistantModelRequestedAt = now;
    android.content.SharedPreferences prefs = c.getSharedPreferences("agent-settings", 0);
    String thread = prefs.getString("conversation", "");
    String selected = ComposerPreferences.get(prefs, thread, "model", "");
    NativeClient.io.execute(() -> {
      try {
        JSONArray catalog = NativeClient.rpcSync("model/list", new JSONObject().put("limit", 100)).getJSONArray("data");
        String name = "";
        for (int i = 0; i < catalog.length(); i++) {
          JSONObject m = catalog.getJSONObject(i);
          if ((!selected.isEmpty() && selected.equals(m.optString("model"))) ||
              (selected.isEmpty() && m.optBoolean("isDefault"))) {
            name = m.optString("displayName", m.optString("model"));
            break;
          }
        }
        assistantModel = name;
      } catch (Exception ignored) { assistantModel = ""; }
    });
  }
  private static JSONObject receipt(JSONObject undo) throws Exception {
    return new JSONObject().put("undo", undo);
  }
  private static void ensureAssistantMode(String scene) throws Exception {
    if (!scene.equals("assistant"))
      return;
    JSONObject connection =
        NativeClient.exchange(new JSONObject().put("op", "modelConnection").put("action", "read"));
    if (!"magpie".equals(connection.optString("mode")))
      throw new IOException("ASSISTANT_MODE_NOT_MAGPIE");
  }
}
