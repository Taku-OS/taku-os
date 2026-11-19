package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;

/** Phone-local System One adapter. Magpie owns provider keys, models and routing. */
final class MagpieDecisions {
    private final String gateway, management;
    private final File root;
    private final Semaphore deciding = new Semaphore(1);
    MagpieDecisions() { this("http://127.0.0.1:3425", "http://127.0.0.1:3430",
            new File("/data/local/agentos/magpie")); }
    MagpieDecisions(String gateway, String management, File root) {
        this.gateway = gateway; this.management = management; this.root = root;
    }

    JSONObject control(JSONObject input) throws Exception {
        switch (input.optString("action", "models")) {
            case "models": return new JSONObject().put("models", models());
            case "importLegacy": return importLegacy(input.getString("key"));
            case "connect": return connect(input.getString("key"));
            case "decide":
                if (!input.optBoolean("authorized")) throw new SecurityException("Confirm this page analysis first.");
                if (!deciding.tryAcquire()) throw new IOException("The decision model is analyzing. Please retry later.");
                try {
                    String model = input.getString("model");
                    JSONObject selected = find(models(), model);
                    Object state = input.get("state");
                    if (hasImage(state) && !selected.optBoolean("imageInput"))
                        throw new IOException("The selected decision model does not declare image support. Use text from the current page or select a decision model that supports images.");
                    try {
                        return new JevDecisionClient(body -> request(gateway, "/v1/systemone", body, false))
                                .decide(model, state, input.getJSONObject("questions"), true);
                    } catch (IllegalStateException invalid) {
                        throw new IOException("The decision model returned an invalid result. Check the Magpie model and interface.");
                    }
                } finally { deciding.release(); }
            default: throw new IOException("Unknown decision model operation.");
        }
    }

    JSONArray models() throws Exception {
        JSONObject catalog = request(gateway, "/v1/models", null, false);
        JSONArray data = catalog.optJSONArray("data"), result = new JSONArray();
        if (data == null) throw new IOException("Invalid Magpie decision model catalog format.");
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !"decision".equals(item.optString("kind"))) continue;
            String id = item.optString("id");
            if (id.isBlank() || id.length() > 512) continue;
            JSONObject modalities = item.optJSONObject("modalities");
            JSONArray inputs = modalities == null ? null : modalities.optJSONArray("input");
            boolean images = false, text = inputs == null;
            if (inputs != null) for (int n = 0; n < inputs.length(); n++) {
                images |= "image".equals(inputs.optString(n));
                text |= "text".equals(inputs.optString(n));
            }
            result.put(new JSONObject().put("id", id)
                    .put("name", item.optString("magpie_label", item.optString("display_name", id)))
                    .put("textInput", text).put("imageInput", images).put("inputKnown", inputs != null));
        }
        // Magpie 0.1.2204 can omit modalities from its decision catalog,
        // even though its management view has the effective image setting.
        // Only enrich already-advertised decision models, never invent one.
        try {
            JSONArray providers = request(management, "/api/providers", null, true).getJSONArray("providers");
            for (int i = 0; i < result.length(); i++) {
                JSONObject model = result.getJSONObject(i);
                if (model.optBoolean("inputKnown")) continue;
                String id = model.getString("id"); int slash = id.indexOf('/');
                if (slash <= 0 || slash == id.length() - 1) continue;
                String providerId = id.substring(0, slash), modelId = id.substring(slash + 1);
                for (int n = 0; n < providers.length(); n++) {
                    JSONObject provider = providers.optJSONObject(n);
                    if (provider == null || !providerId.equals(provider.optString("id"))) continue;
                    JSONArray deciders = provider.optJSONArray("deciders"), listed = provider.optJSONArray("models");
                    boolean decision = false;
                    if (deciders != null) for (int d = 0; d < deciders.length(); d++) decision |= modelId.equals(deciders.optString(d));
                    if (decision && listed != null) for (int m = 0; m < listed.length(); m++) {
                        JSONObject entry = listed.optJSONObject(m);
                        if (entry != null && modelId.equals(entry.optString("id")) && entry.opt("images") instanceof Boolean) {
                            model.put("imageInput", entry.getBoolean("images")).put("inputKnown", true);
                            break;
                        }
                    }
                    break;
                }
            }
        } catch (Exception unavailable) { /* Unknown capabilities remain conservative. */ }
        // Some Magpie versions serve decision groups at /v1/systemone without
        // advertising them in /v1/models. Read the management view and only
        // advertise a group if every member is a known decision model. Image
        // support is conservative because Magpie may fall back to any member.
        try {
            JSONArray groups = request(management, "/api/groups", null, true).getJSONArray("groups");
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.getJSONObject(i);
                if (!group.optBoolean("decides") || !group.optBoolean("ready")) continue;
                String ref = group.optString("ref");
                JSONArray members = group.optJSONArray("members");
                if (!ref.startsWith("group/") || ref.length() > 512 || members == null || members.length() == 0) continue;
                boolean alreadyListed = false;
                for (int n = 0; n < result.length(); n++) alreadyListed |= ref.equals(result.getJSONObject(n).optString("id"));
                if (alreadyListed) continue;
                boolean present = true, known = true, images = true, text = true;
                for (int n = 0; n < members.length(); n++) {
                    try { JSONObject member = find(result, members.getString(n));
                        known &= member.optBoolean("inputKnown"); images &= member.optBoolean("imageInput"); text &= member.optBoolean("textInput");
                    } catch (IOException missing) { present = false; break; }
                }
                // Older decision catalogs omit modalities. Their advertised models
                // still support text; an unknown image capability must not make the
                // saved scene route disappear and prevent enabling Jev.
                if (present) result.put(new JSONObject().put("id", ref)
                        .put("name", group.optString("name", ref)).put("textInput", text).put("imageInput", images).put("inputKnown", known));
            }
        } catch (Exception unavailable) { /* Direct decision models remain usable if management is unavailable. */ }
        return result;
    }

    static JSONObject find(JSONArray models, String id) throws Exception {
        for (int i = 0; i < models.length(); i++)
            if (id.equals(models.getJSONObject(i).getString("id"))) return models.getJSONObject(i);
        throw new IOException("The decision model is no longer available. Refresh the Magpie model catalog and select it again.");
    }

    static boolean hasImage(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if ("image_url".equals(object.optString("type")) || "image".equals(object.optString("type"))) return true;
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();)
                if (hasImage(object.opt(keys.next()))) return true;
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (hasImage(array.opt(i))) return true;
        }
        return false;
    }

    /** The Magpie preset for a Jev key: OpenRouter or TypeSafe's own Jev API; `INVALID_KEY` otherwise. */
    static String jevPreset(String key) throws IOException {
        if (key.matches("sk-or-v1-[A-Za-z0-9_-]{20,}")) return "openrouter";
        if (key.matches("apikey_[A-Za-z0-9_-]{8,}")) return "typesafe";
        throw new IOException("INVALID_KEY");
    }

    /** The decision model Jev should use among ids starting with [prefix] ("" = any): jev-latest, then any Jev. */
    static String pickJev(JSONArray models, String prefix) throws JSONException {
        String any = "";
        for (int i = 0; i < models.length(); i++) {
            String id = models.getJSONObject(i).getString("id");
            if (!id.startsWith(prefix)) continue;
            String lower = id.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith("jev-latest")) return id;
            if (any.isEmpty() && lower.contains("jev")) any = id;
        }
        return any;
    }

    /**
     * Onboarding's Jev key: adds (or reuses) the matching Magpie provider through the canonical
     * Settings store, asks Magpie to fetch its model list, and waits for its Jev decision model.
     * Returns `{provider, preset, model}`; an empty model means the key was kept but no Jev model
     * is listed yet.
     */
    private synchronized JSONObject connect(String raw) throws Exception {
        String key = raw.trim(), preset = jevPreset(key), provider;
        if (root.equals(new File("/data/local/agentos/magpie"))) {
            try { provider = MagpieSettings.addDecisionProvider(preset, key); }
            catch (Exception e) { throw new IOException("KEY_NOT_SAVED"); }
            refreshProvider(provider);
        } else {
            // The isolated host fixture keeps the upstream management API path.
            provider = managedKey(key, preset);
            if (provider.isEmpty()) {
                request(management, "/api/provider/save", new JSONObject().put("new", true)
                        .put("id", "taku-jev-" + preset).put("name", "Taku Jev").put("preset", preset).put("key", key), true);
                provider = managedKey(key, preset);
            }
            if (provider.isEmpty()) throw new IOException("KEY_NOT_SAVED");
        }
        String model = "";
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            try { model = pickJev(models(), provider + "/"); } catch (Exception catalogNotReady) { /* retry */ }
            if (!model.isEmpty() || System.currentTimeMillis() > deadline) break;
            Thread.sleep(1_000);
        }
        return new JSONObject().put("provider", provider).put("preset", preset).put("model", model);
    }

    /** `magpie provider refresh <id>`, bounded; the catalog poll still decides the result. */
    private static void refreshProvider(String provider) {
        try {
            Process process = new ProcessBuilder("/system_ext/bin/magpie", "provider", "refresh", provider).redirectErrorStream(true).start();
            try (InputStream ignored = process.getInputStream()) {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (Exception unavailable) { /* Magpie also fetches lists on its own schedule. */ }
    }

    private synchronized JSONObject importLegacy(String key) throws Exception {
        if (!key.matches("sk-or-v1-[A-Za-z0-9_-]{20,}")) throw new IOException("Invalid legacy credential format. The original configuration was preserved.");
        // Production provider changes must enter the canonical Settings store.
        // The isolated host fixture retains the upstream management API path.
        if (root.equals(new File("/data/local/agentos/magpie")))
            return new JSONObject().put("provider", MagpieSettings.importLegacy(key)).put("imported", true);
        String found = managedKey(key);
        if (found.isEmpty()) {
            // Magpie's atomic provider writer adds a service without replacing an existing one.
            request(management, "/api/provider/save", new JSONObject().put("new", true)
                    .put("id", "taku-jev-import").put("name", "Taku Jev import")
                    .put("preset", "openrouter").put("key", key), true);
            found = managedKey(key);
        }
        if (found.isEmpty()) throw new IOException("Magpie credential storage has not been confirmed. The legacy configuration was preserved.");
        return new JSONObject().put("provider", found).put("imported", true);
    }

    private String managedKey(String key) throws Exception { return managedKey(key, "openrouter"); }

    private String managedKey(String key, String preset) throws Exception {
        File file = new File(root, "config/magpie/providers.json");
        if (!file.isFile()) return "";
        JSONObject stored;
        try { stored = new JSONObject(read(new FileInputStream(file), 4 * 1024 * 1024)); }
        catch (Exception invalid) { throw new IOException("Magpie service configuration cannot be read. Legacy credentials were not changed."); }
        JSONArray providers = stored.optJSONArray("providers");
        if (providers == null) throw new IOException("Magpie service configuration cannot be read. Legacy credentials were not changed.");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.getJSONObject(i);
            if (!preset.equals(p.optString("preset"))) continue;
            if (key.equals(p.optString("key"))) return p.getString("id");
            JSONArray keys = p.optJSONArray("keys");
            if (keys != null) for (int n = 0; n < keys.length(); n++)
                if (key.equals(keys.getJSONObject(n).optString("key"))) return p.getString("id");
        }
        return "";
    }

    private JSONObject request(String base, String path, JSONObject body, boolean admin) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(1500); connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "taku-android-decisions/1");
        if (admin) {
            String token = new String(Files.readAllBytes(new File(root, "web.key").toPath()), StandardCharsets.UTF_8).trim();
            if (!token.matches("[a-f0-9]{64}")) throw new IOException("The Magpie management endpoint is not ready.");
            connection.setRequestProperty("Cookie", "magpie_web_" + new URL(management).getPort() + "=" + token);
            connection.setRequestProperty("Origin", management);
        } else {
            connection.setRequestProperty("Authorization", "Bearer taku-android");
            connection.setRequestProperty("X-Magpie-Deciders", "1");
        }
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 1800000) throw new IOException("Page content is too large. Capture a smaller image.");
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream error = connection.getErrorStream(); if (error != null) error.close();
                throw new IOException("Local Magpie request failed (HTTP " + status + "). Check the service, model, and quota.");
            }
            // Provider saves return a management snapshot; verify the private store, never return its body.
            if (admin && !(body == null && (path.equals("/api/groups") || path.equals("/api/providers")))) { connection.getInputStream().close(); return new JSONObject(); }
            try { return new JSONObject(read(connection.getInputStream(), 4 * 1024 * 1024)); }
            catch (JSONException invalid) { throw new IOException("Magpie returned an invalid decision result format."); }
        } catch (SocketTimeoutException timeout) {
            throw new IOException("Local Magpie decision request timed out. Please retry later.");
        } finally { connection.disconnect(); }
    }

    private static String read(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = in.read(bytes)) != -1) {
                if (out.size() + count > limit) throw new IOException("Magpie response exceeds the limit.");
                out.write(bytes, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
}
