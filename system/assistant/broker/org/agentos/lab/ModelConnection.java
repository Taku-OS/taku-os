package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Device connection settings, separate from Codex's native protocol and conversation history. */
final class ModelConnection {
    static final File ROOT = new File("/data/local/agentos/magpie");
    static final File SETTINGS = new File(ROOT, "connection.json");
    static final String BASE = "http://127.0.0.1:3425/v1";
    private static String installedVersion;

    static String version() {
        if (installedVersion == null) try {
            String output = TaskBroker.command("/system_ext/bin/magpie", "--version").trim();
            installedVersion = output.matches("magpie [0-9.]+") ? output.substring(7) : "Unknown version";
        } catch (Exception unavailable) { return "Not installed"; }
        return installedVersion;
    }

    static JSONObject settings() throws Exception {
        JSONObject value = SETTINGS.isFile() ? new JSONObject(TaskBroker.read(SETTINGS))
                : new JSONObject().put("mode", "openai").put("model", "");
        if (!Arrays.asList("openai", "magpie").contains(value.optString("mode")))
            throw new IOException("Invalid model connection configuration. Select the connection again.");
        String old=value.optString("model","");
        if(value.getString("mode").equals("magpie")&&!old.isEmpty()) MagpieSettings.bindModel("assistant",old,false);
        if(value.has("model")){value.remove("model");write(value);}
        value.put("model",value.getString("mode").equals("magpie")?MagpieSettings.scene("assistant"):"");
        return value;
    }

    static boolean local() throws Exception { return settings().getString("mode").equals("magpie"); }

    static JSONArray catalog() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + "/codex/models")
                .openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(4000);
        connection.setRequestProperty("Authorization", "Bearer taku-android");
        connection.setRequestProperty("User-Agent", "taku-android/1");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Local Magpie is not ready");
            try (InputStream in = connection.getInputStream()) {
                JSONObject response = new JSONObject(NativeBridge.read(in, 1048576));
                JSONArray models = response.optJSONArray("models");
                if (models == null) throw new IOException("Invalid Magpie model catalog format");
                return models;
            }
        } finally { connection.disconnect(); }
    }

    static JSONArray publicModels(JSONArray catalog) throws Exception {
        JSONArray models = new JSONArray();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject model = catalog.getJSONObject(i);
            String id = model.optString("slug");
            if (!id.isEmpty()) models.put(new JSONObject().put("id", id)
                    .put("name", model.optString("display_name", id)));
        }
        return models;
    }

    static JSONObject read() throws Exception {
        JSONObject result = settings();
        try {
            JSONArray models = publicModels(catalog());
            result.put("running", true).put("models", models)
                    .put("message", models.length() == 0 ? "Service started. Add a model service first." : "Local gateway is ready");
        } catch (Exception unavailable) {
            result.put("running", false).put("models", new JSONArray())
                    .put("message", "Local Magpie is temporarily unavailable. Refresh later.");
        }
        return result.put("version", version());
    }

    static boolean busy() {
        if (CodexAppServer.currentRequest != null || !NativeBridge.calls.isEmpty()) return true;
        try (CleanupWork.Held bridgeHeld = NativeBridge.lock()) {
            for (JSONObject state : NativeBridge.state.values())
                if (!state.optString("activeTurnId").isEmpty()) return true;
        }
        File[] pending = TaskBroker.TASKS.listFiles();
        if (pending != null) for (File task : pending)
            if (new File(task, "ready").exists() && !new File(task, "done").exists()) return true;
        return false;
    }

    static JSONObject control(JSONObject params) throws Exception {
        switch (params.optString("action", "read")) {
            case "read": return read();
            case "manage":
                File key = new File(ROOT, "web.key");
                if (!key.isFile()) throw new IOException("Local Magpie has not started");
                String token = TaskBroker.read(key).trim();
                if (!token.matches("[a-f0-9]{64}")) throw new IOException("The Magpie management endpoint is not ready");
                // Only the UID-scoped memory socket returns this URL; never queue/project it.
                return new JSONObject().put("url", "http://127.0.0.1:3430/?k=" + token);
            case "select":
                if (busy()) throw new IOException("Tasks are still running or queued. Switch model connections after they finish.");
                String mode = params.getString("mode"), model = params.optString("model");
                if (!mode.equals("openai") && !mode.equals("magpie")) throw new IOException("Unknown model connection");
                if (mode.equals("magpie")) {
                    JSONArray models = publicModels(catalog());
                    boolean found = false;
                    for (int i = 0; i < models.length(); i++)
                        found |= model.equals(models.getJSONObject(i).getString("id"));
                    if (!found) throw new IOException("Add a model service first, then select a model or routing group from the catalog.");
                } else model = "";
                if(mode.equals("magpie")) model=MagpieSettings.bindModel("assistant",model,true);
                JSONObject next = new JSONObject().put("mode", mode).put("model", model);
                write(new JSONObject().put("mode",mode));
                if (CodexAppServer.shared != null) { CodexAppServer.shared.close(); CodexAppServer.shared = null; }
                AgentAccount.refreshRequested = true;
                return next;
            default: throw new IOException("Unknown model connection operation");
        }
    }

    static void write(JSONObject next)throws Exception{
        ROOT.mkdirs();android.system.Os.chmod(ROOT.getPath(),0700);File temporary=new File(ROOT,"connection.json.tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(next.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        android.system.Os.chmod(temporary.getPath(),0600);Files.move(temporary.toPath(),SETTINGS.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    static List<String> command() throws Exception {
        ArrayList<String> command = new ArrayList<>(Arrays.asList("/system_ext/bin/codex", "app-server"));
        // Codex counts the main session in: one main session and up to ten sub-agents, as many as
        // there can be dedicated screens.
        command.addAll(Arrays.asList("-c", "features.multi_agent_v2.max_concurrent_threads_per_session=11"));
        JSONObject settings = settings();
        if (settings.getString("mode").equals("magpie")) {
            if(settings.optString("model").isEmpty())throw new IOException("Configure a routing group for the main assistant scene");
            option(command, "model_provider", "magpie");
            option(command, "model", settings.getString("model"));
            option(command, "model_providers.magpie.name", "Magpie · Local");
            option(command, "model_providers.magpie.base_url", BASE);
            option(command, "model_providers.magpie.model_catalog_url", BASE + "/codex/models");
            option(command, "model_providers.magpie.wire_api", "responses");
            option(command, "model_providers.magpie.env_key", "TAKU_MAGPIE_KEY");
            command.addAll(Arrays.asList("-c", "model_providers.magpie.requires_openai_auth=false",
                    "-c", "model_providers.magpie.supports_websockets=false"));
        }
        return command;
    }

    private static void option(List<String> command, String key, String value) {
        command.add("-c"); command.add(key + "=" + JSONObject.quote(value).replace("\\/", "/"));
    }
}
