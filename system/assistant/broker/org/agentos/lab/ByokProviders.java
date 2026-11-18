package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;

final class ByokProviders {
    private final File directory, magpie;
    private final String management;
    private final boolean notifyAudio;
    ByokProviders() { this(new File("/data/local/agentos/ai/credentials"),
            new File("/data/local/agentos/magpie"), "http://127.0.0.1:3430", true); }
    ByokProviders(File directory, File magpie, String management) {
        this(directory,magpie,management,false);
    }
    private ByokProviders(File directory, File magpie, String management, boolean notifyAudio) {
        this.directory = directory; this.magpie = magpie; this.management = management;this.notifyAudio=notifyAudio;
    }

    synchronized JSONObject control(JSONObject request) throws Exception {
        String action = request.optString("action", "read");
        if (action.equals("read")) return status();
        if (action.equals("falSettings")) return falControl(new JSONObject().put("action", "read"));
        if (action.equals("selectFalModel")) return falControl(new JSONObject().put("action", action).put("model", request.opt("model")));
        String provider = request.optString("provider");
        if (!provider.equals("fal") && !provider.equals("openrouter"))
            throw new IOException("Select fal or OpenRouter");
        if (!action.equals("save") && !action.equals("remove"))
            throw new IOException("Unknown BYOK operation");
        if (provider.equals("fal")) {
            JSONObject command = new JSONObject().put("action", action.equals("remove") ? "removeFalKey" : "saveFalKey");
            if (action.equals("save")) command.put("key", request.opt("key"));
            falControl(command);
        } else {
            String id = request.optString("id"), ref = request.optString("ref");
            if (action.equals("remove")) {
                findKey(id, ref);
                JSONArray keys = status().getJSONArray("keys"); int count = 0;
                for (int i = 0; i < keys.length(); i++) if ("openrouter".equals(keys.getJSONObject(i).optString("provider")) && id.equals(keys.getJSONObject(i).optString("id"))) count++;
                if (count == 1) admin("/api/provider/delete", new JSONObject().put("id", id));
                else {
                    JSONArray all = providers();
                    for (int i = 0; i < all.length(); i++) {
                        JSONObject p = all.getJSONObject(i);
                        if (!id.equals(p.optString("id")) || !ref.equals(fingerprint(p.optString("key")))) continue;
                        JSONArray others = p.optJSONArray("keys"); boolean active = false;
                        if (others != null) for (int n = 0; n < others.length(); n++) active |= !others.getJSONObject(n).optBoolean("off") && !others.getJSONObject(n).optString("key").isEmpty();
                        if (!active) throw new IOException("Enable another key in model management before removing the current key");
                    }
                    admin("/api/keys/remove", new JSONObject().put("id", id).put("ref", ref));
                }
                if (containsRef(id, ref)) throw new IOException("Key removal has not been confirmed. Refresh and retry");
            } else {
                String key = validate(request.optString("key"));
                if (id.isEmpty()) {
                    if (!containsKey(key)) admin("/api/provider/save", new JSONObject()
                            .put("new", true).put("id", "taku-byok-openrouter")
                            .put("name", "Taku OpenRouter").put("preset", "openrouter").put("key", key));
                } else {
                    findKey(id, ref);
                    admin("/api/keys/replace", new JSONObject().put("id", id).put("ref", ref).put("key", key));
                }
                if (!containsKey(key)) throw new IOException("Key storage has not been confirmed. Refresh and retry");
            }
        }
        SystemAi.catalogUntil = 0;
        return status();
    }

    synchronized JSONObject falControl(JSONObject request) throws Exception {
        String action = request.optString("action");
        switch (action) {
            case "read": return falSettings();
            case "saveFalKey": {
                Object value = request.opt("key");
                if (!(value instanceof String)) throw new AudioConfigFailure("INVALID_ARGUMENT", "Enter one complete fal API Key");
                String key;
                try { key = validate((String)value); }
                catch (IOException invalid) { throw new AudioConfigFailure("INVALID_ARGUMENT", "Enter one complete fal API Key"); }
                store("fal.json", new JSONObject().put("key", key));
                break;
            }
            case "removeFalKey":
                Files.deleteIfExists(new File(directory, "fal.json").toPath());
                break;
            case "selectFalModel": {
                Object value = request.opt("model");
                if (!(value instanceof String) || !isFalModel((String)value))
                    throw new AudioConfigFailure("INVALID_ARGUMENT", "Select an available fal voice model");
                if (!falSettings().getBoolean("configured"))
                    throw new AudioConfigFailure("PROVIDER_NOT_CONFIGURED", "Add or save your fal Key again in Taku Audio");
                store("fal-model.json", new JSONObject().put("model", value));
                break;
            }
            default: throw new AudioConfigFailure("UNSUPPORTED_OPERATION", "Unknown fal settings operation");
        }
        if (notifyAudio) AudioConfigBridge.falChanged();
        return falSettings();
    }

    private static boolean isFalModel(String model) {
        return model.equals("kotoba") || model.equals("grok") || model.equals("whisper");
    }

    private static String masked(String key) {
        return key.length() > 8 ? "•••• " + key.substring(key.length() - 4) : "••••";
    }

    JSONObject status() throws Exception {
        JSONArray items = new JSONArray();
        File fal = new File(directory, "fal.json");
        if (fal.isFile()) {
            String key;
            try { key = new JSONObject(new String(Files.readAllBytes(fal.toPath()), StandardCharsets.UTF_8)).getString("key"); }
            catch (Exception e) { throw new IOException("The fal key cannot be read. Save it again in Taku Audio"); }
            items.put(summary("fal", "fal", "fal", "fal", key));
        }
        JSONArray providers = providers();
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.getJSONObject(i);
            if (!"openrouter".equals(p.optString("preset"))) continue;
            String key = p.optString("key");
            if (!key.isEmpty()) items.put(summary("openrouter", p.getString("id"), fingerprint(key),
                    p.optString("name", "OpenRouter"), key));
            JSONArray keys = p.optJSONArray("keys");
            if (keys != null) for (int n = 0; n < keys.length(); n++) {
                JSONObject k = keys.getJSONObject(n); key = k.optString("key");
                if (!key.isEmpty()) items.put(summary("openrouter", p.getString("id"), fingerprint(key),
                        p.optString("name", "OpenRouter") + " · " + k.optString("name", "API Key"), key));
            }
        }
        return new JSONObject().put("keys", items).put("falRealtimeAvailable", false);
    }
    JSONObject falSettings() throws Exception {
        File keyFile = new File(directory, "fal.json"), modelFile = new File(directory, "fal-model.json");
        // Read by the settings page and by every Audio Engine config snapshot: an unreadable
        // file must degrade to "not configured" (with a reason), never fail those reads.
        boolean configured = false;
        String masked = null, error = null;
        if (keyFile.isFile()) {
            try {
                JSONObject stored = new JSONObject(new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8));
                Object value = stored.get("key");
                if (!(value instanceof String)) throw new IOException("Invalid fal Key format");
                String key = validate((String)value);
                configured = true;
                masked = masked(key);
            } catch (Exception unreadable) { error = "KEY_UNREADABLE"; }
        }
        String model = "kotoba";
        if (modelFile.isFile()) {
            try {
                String stored = new JSONObject(new String(Files.readAllBytes(modelFile.toPath()), StandardCharsets.UTF_8)).optString("model", "kotoba");
                if (isFalModel(stored)) model = stored;
                else if (error == null) error = "MODEL_UNREADABLE";
            } catch (Exception unreadable) { if (error == null) error = "MODEL_UNREADABLE"; }
        }
        JSONArray models = new JSONArray()
                .put(new JSONObject().put("id", "kotoba").put("name", "Kotoba STS"))
                .put(new JSONObject().put("id", "grok").put("name", "Grok Voice"))
                .put(new JSONObject().put("id", "whisper").put("name", "Whisper · Segmented captions"));
        return new JSONObject().put("configured", configured).put("masked", masked == null ? JSONObject.NULL : masked).put("model", model)
                .put("models", models).put("realtimeAvailable", false).put("error", error == null ? JSONObject.NULL : error);
    }
    /** Only the UID-verified engine configuration socket may return this object. */
    JSONObject falCredential() throws Exception {
        JSONObject config = SystemAi.config();
        if (!config.optBoolean("enabled") || !config.optBoolean("audioEnabled", true))
            throw new AudioConfigFailure("POLICY_REVOKED", "Voice features are paused");
        File file = new File(directory, "fal.json");
        if (!file.isFile()) throw new AudioConfigFailure("PROVIDER_NOT_CONFIGURED", "Add a fal Key in Taku Audio");
        String key;
        try { key = validate(new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getString("key")); }
        catch (Exception unreadable) { throw new AudioConfigFailure("PROVIDER_NOT_CONFIGURED", "The fal Key cannot be read. Save it again in Taku Audio"); }
        return new JSONObject().put("key", key);
    }
    private JSONObject summary(String provider, String id, String ref, String name, String key) throws Exception {
        return new JSONObject().put("provider", provider).put("id", id).put("ref", ref)
                .put("name", name).put("masked", masked(key));
    }
    static String validate(String value) throws IOException {
        String key = value.trim();
        if (key.isEmpty() || key.length() > 4096 || key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c) || c == ','))
            throw new IOException("Enter one complete API Key without spaces or line breaks");
        return key;
    }
    private void store(String name, JSONObject value) throws Exception {
        Files.createDirectories(directory.toPath()); android.system.Os.chmod(directory.getPath(), 0700);
        File temp = new File(directory, name + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                android.system.Os.chmod(temp.getPath(), 0600);
                out.write(value.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(temp.toPath(), new File(directory, name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private JSONArray providers() throws Exception {
        File file = new File(magpie, "config/magpie/providers.json");
        if (!file.isFile()) return new JSONArray();
        try { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getJSONArray("providers"); }
        catch (Exception e) { throw new IOException("Magpie service configuration cannot be read. Check the model service first"); }
    }
    private String findKey(String id, String ref) throws Exception {
        JSONArray all = providers();
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.getJSONObject(i);
            if (!id.equals(p.optString("id")) || !"openrouter".equals(p.optString("preset"))) continue;
            String key = p.optString("key");
            if (!key.isEmpty() && ref.equals(fingerprint(key))) return key;
            JSONArray keys = p.optJSONArray("keys");
            if (keys != null) for (int n = 0; n < keys.length(); n++) {
                key = keys.getJSONObject(n).optString("key");
                if (!key.isEmpty() && ref.equals(fingerprint(key))) return key;
            }
        }
        throw new IOException("This key has changed. Refresh and retry");
    }
    private boolean containsRef(String id, String ref) throws Exception {
        JSONArray items = status().getJSONArray("keys");
        for (int i = 0; i < items.length(); i++) {
            JSONObject k = items.getJSONObject(i);
            if (id.equals(k.getString("id")) && ref.equals(k.getString("ref"))) return true;
        }
        return false;
    }
    private boolean containsKey(String key) throws Exception {
        JSONArray items = status().getJSONArray("keys"); String ref = fingerprint(key);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.getString("provider").equals("openrouter") && ref.equals(item.getString("ref"))) return true;
        }
        return false;
    }
    private static String fingerprint(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder ref = new StringBuilder(); for (int i = 0; i < 5; i++) ref.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
        return ref.toString();
    }
    private void admin(String path, JSONObject body) throws Exception {
        HttpURLConnection c = null; int status = 0;
        try {
            String token = new String(Files.readAllBytes(new File(magpie, "web.key").toPath()), StandardCharsets.UTF_8).trim();
            c = (HttpURLConnection) new URL(management + path).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(1800); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Cookie", "magpie_web_" + new URL(management).getPort() + "=" + token);
            c.setRequestProperty("Origin", management); c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "taku-android-byok/1");
            c.setRequestMethod("POST"); c.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Magpie did not save the changes");
            // The management response can contain account details; never forward or log it.
            c.getInputStream().close();
        } catch (SocketTimeoutException e) {
            throw new IOException("Magpie response timed out. Refresh to confirm whether changes were saved");
        } catch (IOException e) {
            throw new IOException("Could not update the Magpie key (" + (status == 0 ? e.getClass().getSimpleName() : "HTTP " + status) + "). Check the model service, refresh, and retry");
        } finally { if (c != null) c.disconnect(); }
    }
}
