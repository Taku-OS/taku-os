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
  private static final String ASSIST_SCREENSHOTS = "assist_screenshot_enabled";
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
          assistantMode = ComposerOptions.readConnection(c).optString("mode", "unknown");
        } catch (Exception ignored) {
        }
        if ("openai".equals(assistantMode)) refreshAssistantModel(c);
        return new JSONObject()
            .put("assistantModel", "openai".equals(assistantMode) ? assistantModel : "")
            .put("enabled", state.optBoolean("enabled"))
            .put("assistScreenshotsEnabled", Settings.Secure.getInt(c.getContentResolver(), ASSIST_SCREENSHOTS, 1) != 0)
            .put("apps", apps(c, state.getJSONObject("apps")))
            .put("usage", usage(state.optJSONArray("usage")))
            .put("audioAvailable", AudioEngine.available(c))
            .put("connected", state.optBoolean("connected"))
            .put("assistantMode", assistantMode)
            .put("jevEnabled",
                c.getSharedPreferences("agent-settings", 0).getBoolean("jevEnabled", false))
            .put("models", state.optJSONArray("models"))
            .put("groups", new JSONArray(value(c, GROUPS, "[]")))
            .put("bindings", new JSONObject(value(c, SCENES, "{}")))
            .put("providers", visible);
      }
      case "manageSystemAi":
        return manage(c, input);
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
      default:
        throw new IOException("UNSUPPORTED_ACTION");
    }
  }
  private static JSONArray apps(Context c, JSONObject permissions) throws Exception {
    java.util.List<String> ids = new java.util.ArrayList<>();
    for (java.util.Iterator<String> it = permissions.keys(); it.hasNext();) ids.add(it.next());
    java.util.Collections.sort(ids);
    JSONArray result = new JSONArray();
    for (String id : ids) {
      String name;
      switch (id) {
        case "ime": name = "Input method"; break;
        case "memory": name = "Memory"; break;
        case "agent-ui": name = "Agent UI cards"; break;
        default:
          try {
            name = c.getPackageManager().getApplicationLabel(
                c.getPackageManager().getApplicationInfo(id, 0)).toString();
          } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
            name = id;
          }
      }
      result.put(new JSONObject().put("id", id).put("name", name)
          .put("enabled", permissions.getBoolean(id)));
    }
    return result;
  }
  private static JSONArray usage(JSONArray records) throws Exception {
    JSONArray result = new JSONArray();
    if (records != null) for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.optJSONObject(i);
      if (record == null) continue;
      JSONObject visible = new JSONObject();
      for (String key : new String[]{"consumer", "model", "at", "elapsedMs", "status", "code"})
        if (record.has(key)) visible.put(key, record.get(key));
      result.put(visible);
    }
    return result;
  }
  private static void fields(JSONObject input, String... expected) throws IOException {
    if (input.length() != expected.length + 1) throw new IOException("INVALID_ARGUMENT");
    for (String field : expected)
      if (!input.has(field) || input.isNull(field)) throw new IOException("INVALID_ARGUMENT");
  }
  private static String string(JSONObject input, String key) throws Exception {
    Object value = input.opt(key);
    if (!(value instanceof String) || ((String) value).length() > 512)
      throw new IOException("INVALID_ARGUMENT");
    String text = (String) value;
    for (int i = 0; i < text.length(); i++)
      if (Character.isISOControl(text.charAt(i))) throw new IOException("INVALID_ARGUMENT");
    return text;
  }
  private static boolean bool(JSONObject input, String key) throws Exception {
    Object value = input.opt(key);
    if (!(value instanceof Boolean)) throw new IOException("INVALID_ARGUMENT");
    return (Boolean) value;
  }
  private static JSONObject manage(Context c, JSONObject input) throws Exception {
    String operation = string(input, "operation");
    switch (operation) {
      case "saveGroup": {
        // A model group typed by hand: its id, name and ordered member model ids.
        fields(input, "id", "name", "members", "create");
        String id = string(input, "id"), name = string(input, "name").trim();
        boolean create = bool(input, "create");
        JSONArray typed = input.optJSONArray("members"), members = new JSONArray();
        if (!id.matches("[A-Za-z0-9_-]{1,64}") || name.isEmpty() || name.length() > 120 || typed == null || typed.length() > 32)
          throw new IOException("INVALID_GROUP");
        for (int i = 0; i < typed.length(); i++) {
          Object member = typed.opt(i);
          if (!(member instanceof String)) throw new IOException("INVALID_MEMBERS");
          String model = ((String) member).trim();
          if (model.length() > 512) throw new IOException("INVALID_MEMBERS");
          for (int n = 0; n < model.length(); n++)
            if (Character.isISOControl(model.charAt(n))) throw new IOException("INVALID_MEMBERS");
          if (!model.isEmpty()) members.put(model);
        }
        if (members.length() == 0) throw new IOException("INVALID_MEMBERS");
        JSONArray groups = new JSONArray(value(c, GROUPS, "[]"));
        int found = -1;
        for (int i = 0; i < groups.length(); i++)
          if (id.equals(groups.getJSONObject(i).optString("id"))) { found = i; break; }
        if (create && found >= 0) throw new IOException("GROUP_EXISTS");
        if (!create && found < 0) throw new IOException("GROUP_NOT_FOUND");
        JSONObject group = found < 0 ? new JSONObject().put("id", id).put("routing", "order") : groups.getJSONObject(found);
        group.put("name", name).put("members", members);
        if (found < 0) groups.put(group);
        put(c, GROUPS, groups.toString());
        return new JSONObject().put("group", group);
      }
      case "bindScene": {
        // Any scene id, including ones this build has no feature row for. The built-in scenes
        // keep the compatibility checks of bindSystemAi.
        fields(input, "scene", "group");
        String scene = string(input, "scene"), id = string(input, "group");
        if (!scene.matches("[A-Za-z0-9_.-]{1,128}")) throw new IOException("INVALID_SCENE");
        if (SCENE_IDS.contains(scene))
          return execute(c, "bindSystemAi", new JSONObject().put("scene", scene).put("group", id));
        group(new JSONArray(value(c, GROUPS, "[]")), id);
        JSONObject bindings = new JSONObject(value(c, SCENES, "{}"));
        bindings.put(scene, "group/" + id);
        put(c, SCENES, bindings.toString());
        return new JSONObject().put("scene", scene).put("binding", "group/" + id);
      }
      case "unbindScene": {
        fields(input, "scene");
        String scene = string(input, "scene");
        // The built-in features always keep a binding; only added scenes can be removed.
        if (SCENE_IDS.contains(scene) || !scene.matches("[A-Za-z0-9_.-]{1,128}")) throw new IOException("INVALID_SCENE");
        JSONObject bindings = new JSONObject(value(c, SCENES, "{}"));
        if (!bindings.has(scene)) throw new IOException("SCENE_NOT_FOUND");
        bindings.remove(scene);
        put(c, SCENES, bindings.toString());
        return new JSONObject().put("scene", scene);
      }
      case "setApp": {
        fields(input, "app", "enabled");
        String app = string(input, "app");
        boolean enabled = bool(input, "enabled");
        JSONObject state = NativeClient.exchange(new JSONObject().put("op", "systemAi").put("action", "read"));
        if (!state.getJSONObject("apps").has(app)) throw new IOException("APP_NOT_FOUND");
        NativeClient.exchange(new JSONObject().put("op", "systemAi").put("action", "configure")
            .put("patch", new JSONObject().put("apps", new JSONObject().put(app, enabled))));
        return new JSONObject().put("app", app).put("enabled", enabled);
      }
      case "readConnection":
        fields(input);
        return ComposerOptions.readConnection(c);
      case "selectConnection": {
        fields(input, "mode", "model");
        String mode = string(input, "mode"), model = string(input, "model");
        if (!(mode.equals("openai") && model.isEmpty() || mode.equals("magpie") && !model.isEmpty()))
          throw new IOException("INVALID_CONNECTION");
        return ComposerOptions.switchConnection(c, mode, model);
      }
      case "readAccount": {
        fields(input);
        JSONObject source = NativeClient.rpcSync("account/read", new JSONObject().put("refreshToken", false));
        JSONObject account = source.optJSONObject("account");
        return new JSONObject().put("connected", account != null)
            .put("type", account == null ? "" : account.optString("type"))
            .put("planType", account == null ? "" : account.optString("planType"))
            .put("requiresOpenaiAuth", source.optBoolean("requiresOpenaiAuth"));
      }
      case "readLimits": {
        fields(input);
        JSONObject result = new JSONObject();
        for (String kind : new String[]{"limits", "usage"}) {
          try {
            JSONObject source = NativeClient.rpcSync(kind.equals("limits")
                ? "account/rateLimits/read" : "account/usage/read", new JSONObject());
            JSONArray values = new JSONArray();
            metrics(source, "", values);
            result.put(kind, values);
          } catch (Exception unavailable) {
            result.put(kind + "Error", "The account service did not provide " + kind + ". Refresh to retry.");
          }
        }
        return result;
      }
      case "readComposerScreenshots":
      case "setComposerScreenshots": {
        boolean write = operation.equals("setComposerScreenshots");
        if (write) fields(input, "enabled");
        else fields(input);
        synchronized (ComposerPreferences.class) {
          android.content.SharedPreferences prefs = c.getSharedPreferences("agent-settings", 0);
          if (write) {
            boolean enabled = bool(input, "enabled");
            boolean previous = prefs.getBoolean("includeScreenshot", false);
            if (!prefs.edit().putBoolean("includeScreenshot", enabled).commit()) {
              prefs.edit().putBoolean("includeScreenshot", previous).apply();
              throw new IOException("SETTINGS_WRITE_FAILED");
            }
          }
          return new JSONObject().put("enabled", prefs.getBoolean("includeScreenshot", false));
        }
      }
      case "setAssistScreenshots": {
        fields(input, "enabled");
        boolean enabled = bool(input, "enabled");
        if (!Settings.Secure.putInt(c.getContentResolver(), ASSIST_SCREENSHOTS, enabled ? 1 : 0))
          throw new IOException("SETTINGS_WRITE_FAILED");
        if (!enabled) AssistantInvocationCapture.clear();
        return new JSONObject().put("enabled", enabled);
      }
      case "readJev":
        fields(input);
        return jev(c, operation, false);
      case "setJev":
        fields(input, "enabled");
        return jev(c, operation, bool(input, "enabled"));
      case "testJev":
        fields(input);
        return jev(c, operation, false);
      case "openFeature": {
        fields(input, "feature");
        Intent intent;
        switch (string(input, "feature")) {
          case "audio": intent = AudioEngine.homeIntent(); break;
          case "ime": intent = new Intent().setClassName("org.agentos.inputmethod",
              "org.fcitx.fcitx5.android.input.ai.AiRepliesSettingsActivity"); break;
          case "memory": intent = new Intent().setClassName("org.agentos.memory",
              "org.agentos.memory.MemoryActivity").putExtra("page", "settings"); break;
          default: throw new IOException("INVALID_FEATURE");
        }
        try { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (android.content.ActivityNotFoundException unavailable) { throw new IOException("FEATURE_UNAVAILABLE"); }
        return new JSONObject();
      }
      default: throw new IOException("UNSUPPORTED_OPERATION");
    }
  }
  private static final Set<String> METRIC_FIELDS = Set.of("usedPercent", "windowDurationMins", "resetsAt",
      "totalTokens", "inputTokens", "cachedInputTokens", "outputTokens", "reasoningOutputTokens",
      "totalCost", "estimatedCostUsd", "costUsd", "balance", "hasCredits", "unlimited",
      "planType", "limitName", "model", "currency", "status", "count", "requestCount");
  private static final java.util.regex.Pattern PRIVATE_METRIC_FIELD =
      java.util.regex.Pattern.compile("(?i).*(secret|password|credential|authorization|email|url).*");
  private static void metrics(Object value, String path, JSONArray output) throws Exception {
    if (value instanceof JSONArray) {
      JSONArray list = (JSONArray) value;
      for (int i = 0; i < list.length(); i++) metrics(list.get(i), path + " " + (i + 1), output);
    } else if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      for (java.util.Iterator<String> it = object.keys(); it.hasNext();) {
        String key = it.next();
        if (PRIVATE_METRIC_FIELD.matcher(key).matches()) continue;
        Object child = object.get(key);
        String label = path.isEmpty() ? key : path + "." + key;
        if (child instanceof JSONObject || child instanceof JSONArray) metrics(child, label, output);
        else if (METRIC_FIELDS.contains(key) && child != JSONObject.NULL)
          output.put(new JSONObject().put("key", key).put("label", label).put("value", child));
      }
    }
  }
  private static JSONObject jev(Context c, String operation, boolean enabled) throws Exception {
    java.util.concurrent.CompletableFuture<JSONObject> result = new java.util.concurrent.CompletableFuture<>();
    try (AgentRepository repository = new AgentRepository(c)) {
      AgentRepository.Callback<JSONObject> callback = new AgentRepository.Callback<JSONObject>() {
        public void ready(JSONObject value) { result.complete(value); }
        public void failed(String message) { result.completeExceptionally(new IOException(message)); }
      };
      if (operation.equals("readJev")) repository.jevConfig(callback);
      else if (operation.equals("testJev")) repository.testJev(callback);
      else repository.saveJevConfig(SceneRoutes.get(c, "jev"), enabled, new AgentRepository.Callback<Boolean>() {
        public void ready(Boolean value) {
          try { result.complete(new JSONObject().put("enabled", value)); }
          catch (Exception error) { result.completeExceptionally(error); }
        }
        public void failed(String message) { result.completeExceptionally(new IOException(message)); }
      });
      try {
        JSONObject value = result.get(90, java.util.concurrent.TimeUnit.SECONDS);
        return operation.equals("testJev") ? new JSONObject().put("connected", true) : value;
      } catch (java.util.concurrent.ExecutionException error) {
        throw new IOException(error.getCause().getMessage());
      }
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
