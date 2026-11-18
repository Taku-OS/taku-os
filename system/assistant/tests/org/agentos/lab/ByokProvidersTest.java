package org.agentos.lab;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** On-device checks against the pinned Magpie. Synthetic keys; no generation requests. */
public final class ByokProvidersTest {
    static final File MAGPIE = new File("/data/local/agentos/magpie");
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    static JSONObject call(String action, String provider) throws Exception {
        return new JSONObject().put("action", action).put("provider", provider);
    }
    static JSONObject find(JSONObject state, String provider, String key) throws Exception {
        String ref;
        if (provider.equals("fal")) ref = "fal";
        else {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); for (int i = 0; i < 5; i++) b.append(String.format(Locale.ROOT, "%02x", digest[i]));
            ref = b.toString();
        }
        JSONArray keys = state.getJSONArray("keys");
        for (int i = 0; i < keys.length(); i++) if (keys.getJSONObject(i).getString("provider").equals(provider)
                && keys.getJSONObject(i).getString("ref").equals(ref)) return keys.getJSONObject(i);
        throw new AssertionError("Expected saved key summary");
    }
    static Map<String, String> storedProviders() throws Exception {
        File f = new File(MAGPIE, "config/magpie/providers.json"); Map<String, String> values = new HashMap<>();
        if (f.isFile()) {
            JSONArray a = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getJSONArray("providers");
            for (int i = 0; i < a.length(); i++) values.put(a.getJSONObject(i).getString("id"), a.getJSONObject(i).toString());
        }
        return values;
    }
    static void deleteTestProvider(String id) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:3430/api/provider/delete").openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(2000); c.setReadTimeout(20000);
        c.setRequestProperty("Cookie", "magpie_web_3430=" + new String(Files.readAllBytes(new File(MAGPIE, "web.key").toPath()), StandardCharsets.UTF_8).trim());
        c.setRequestProperty("Origin", "http://127.0.0.1:3430"); c.setRequestProperty("Content-Type", "application/json");
        c.setRequestMethod("POST"); c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) { out.write(new JSONObject().put("id", id).toString().getBytes(StandardCharsets.UTF_8)); }
        try { check(c.getResponseCode() == 200, "temporary provider cleanup"); c.getInputStream().close(); }
        finally { c.disconnect(); }
    }
    static JSONObject audioSettings(String action) throws Exception {
        return new JSONObject().put("op", "settings").put("action", action);
    }
    static byte[] bytes(File file) throws Exception {
        return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
    }
    static void rejectSettings(JSONObject request, String code) throws Exception {
        try { AudioConfigBridge.settings(request); throw new AssertionError("Invalid settings request accepted"); }
        catch (AudioConfigFailure expected) {
            check(expected.code.equals(code), "closed settings error code");
            check(!expected.getMessage().contains("fixture-secret"), "settings errors omit submitted values");
        }
    }
    static void settingsBoundary() throws Exception {
        File[] files = {new File(SystemAi.ROOT, "audio-settings.json"),
                new File("/data/local/agentos/ai/credentials/fal.json"),
                new File("/data/local/agentos/ai/credentials/fal-model.json")};
        Map<File, byte[]> before = new HashMap<>();
        for (File file : files) before.put(file, bytes(file));
        String policy = TaskBroker.command("/system/bin/settings", "get", "global", "taku_ai_audio_enabled");
        rejectSettings(new JSONObject().put("op", "settings"), "INVALID_ARGUMENT");
        rejectSettings(new JSONObject().put("op", "settings").put("action", 1), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("read").put("key", "fixture-secret"), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("read").put("op", "falCredential"), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("falCredential"), "UNSUPPORTED_OPERATION");
        rejectSettings(audioSettings("subscribe"), "UNSUPPORTED_OPERATION");
        rejectSettings(audioSettings("translate"), "UNSUPPORTED_OPERATION");
        rejectSettings(audioSettings("saveFalKey"), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("saveFalKey").put("key", 17), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("removeFalKey").put("provider", "fal"), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("selectFalModel").put("model", JSONObject.NULL), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("setAudioEnabled").put("enabled", "false"), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("setAudioEnabled").put("enabled", false).put("patch", new JSONObject()), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("configureReading").put("language", "en-US").put("voiceId", "en-US-local-0").put("speed", "1.0"), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("configureReading").put("language", "en-US").put("voiceId", "zh-CN-local-3").put("speed", 1.0), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("configureReading").put("language", "en-US").put("voiceId", "en-US-local-0").put("speed", 2.1), "INVALID_ARGUMENT");
        rejectSettings(audioSettings("resetReading").put("language", "not-a-language"), "INVALID_ARGUMENT");
        for (File file : files) check(Arrays.equals(before.get(file), bytes(file)), "invalid settings preserve root files");
        check(policy.equals(TaskBroker.command("/system/bin/settings", "get", "global", "taku_ai_audio_enabled")), "invalid settings preserve voice policy");
        System.out.println("PASS closed audio settings reject private operations and invalid input without writes");
    }
    static void localFalSettings() throws Exception {
        File folder = new File("/data/local/tmp/taku-fal-local-test-" + UUID.randomUUID());
        File credentials = new File(folder, "credentials"), magpie = new File(folder, "magpie");
        File projected = new File(magpie, "config/magpie/providers.json");
        Files.createDirectories(projected.getParentFile().toPath());
        Files.write(projected.toPath(), "unreadable-provider-config".getBytes(StandardCharsets.UTF_8));
        ByokProviders service = new ByokProviders(credentials, magpie, "http://127.0.0.1:1");
        String first = "fixture-fal-" + UUID.randomUUID(), replacement = "fixture-fal-" + UUID.randomUUID();
        try {
            JSONObject state = service.falControl(new JSONObject().put("action", "read"));
            check(!state.getBoolean("configured") && state.isNull("masked"), "empty fal state has no masked credential");
            state = service.falControl(new JSONObject().put("action", "saveFalKey").put("key", first));
            check(state.getBoolean("configured") && !state.toString().contains(first), "local fal save returns only a safe summary");
            check(state.getString("masked").endsWith(first.substring(first.length() - 4)), "local fal mask retains only the suffix");
            state = service.falControl(new JSONObject().put("action", "selectFalModel").put("model", "grok"));
            check(state.getString("model").equals("grok"), "fal model selection works without provider config");
            byte[] keyBefore = bytes(new File(credentials, "fal.json")), modelBefore = bytes(new File(credentials, "fal-model.json"));
            for (Object invalid : new Object[]{"", "two keys", "one\ntwo", "one,two", "x".repeat(4097), 42, JSONObject.NULL}) {
                try { service.falControl(new JSONObject().put("action", "saveFalKey").put("key", invalid)); throw new AssertionError("Invalid key accepted"); }
                catch (AudioConfigFailure expected) { check(expected.code.equals("INVALID_ARGUMENT"), "invalid fal key code"); }
                check(Arrays.equals(keyBefore, bytes(new File(credentials, "fal.json"))), "invalid key preserves saved credential");
            }
            try { service.falControl(new JSONObject().put("action", "selectFalModel").put("model", "unknown")); throw new AssertionError("Invalid model accepted"); }
            catch (AudioConfigFailure expected) { check(expected.code.equals("INVALID_ARGUMENT"), "invalid fal model code"); }
            check(Arrays.equals(modelBefore, bytes(new File(credentials, "fal-model.json"))), "invalid model preserves preference");
            service.falControl(new JSONObject().put("action", "saveFalKey").put("key", replacement));
            state = new ByokProviders(credentials, magpie, "http://127.0.0.1:1").falControl(new JSONObject().put("action", "read"));
            check(state.getString("model").equals("grok") && !state.toString().contains(replacement), "replacement retains model across reopening");
            state = service.falControl(new JSONObject().put("action", "removeFalKey"));
            check(!state.getBoolean("configured") && state.isNull("masked") && state.getString("model").equals("grok"), "removal retains model and clears mask");
            try { service.falControl(new JSONObject().put("action", "selectFalModel").put("model", "kotoba")); throw new AssertionError("Unconfigured model change accepted"); }
            catch (AudioConfigFailure expected) { check(expected.code.equals("PROVIDER_NOT_CONFIGURED"), "missing key blocks model change"); }
            state = service.falControl(new JSONObject().put("action", "saveFalKey").put("key", "short"));
            check(state.getString("masked").equals("••••") && !state.toString().contains("short"), "short keys are fully masked");
            Files.write(new File(credentials, "fal.json").toPath(), "{\"key\":42}".getBytes(StandardCharsets.UTF_8));
            state = service.falControl(new JSONObject().put("action", "read"));
            check(!state.getBoolean("configured") && state.isNull("masked") && state.getString("error").equals("KEY_UNREADABLE"), "unreadable key yields a safe error state");
            service.falControl(new JSONObject().put("action", "saveFalKey").put("key", replacement));
            Files.write(new File(credentials, "fal-model.json").toPath(), "{\"model\":\"unknown\"}".getBytes(StandardCharsets.UTF_8));
            state = service.falControl(new JSONObject().put("action", "read"));
            check(state.getBoolean("configured") && state.getString("model").equals("kotoba") && state.getString("error").equals("MODEL_UNREADABLE"), "unreadable model preserves configured key");
            check(new String(Files.readAllBytes(projected.toPath()), StandardCharsets.UTF_8).equals("unreadable-provider-config"), "local fal operations never replace provider config");
            check((android.system.Os.stat(credentials.getPath()).st_mode & 0777) == 0700, "local fal directory stays private");
            check((android.system.Os.stat(new File(credentials, "fal.json").getPath()).st_mode & 0777) == 0600, "local fal credential stays private");
            System.out.println("PASS local fal settings without OpenRouter preserve credentials, preferences, masking and validation");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(folder.toPath())) {
                for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
    static void dictationSettings() throws Exception {
        File settings = new File(SystemAi.ROOT, "audio-settings.json");
        byte[] original = bytes(settings);
        try {
            JSONObject base = audioSettings("configureDictation").put("scope", "assistant-entry")
                    .put("primaryProfileId", "zipformer").put("fallbackProfileId", JSONObject.NULL)
                    .put("language", "system");
            JSONObject[] invalid = {
                    audioSettings("configureDictation"),
                    audioSettings("configureDictation").put("scope", "assistant-entry"),
                    new JSONObject(base.toString()).put("scope", "other"),
                    new JSONObject(base.toString()).put("primaryProfileId", "unknown"),
                    new JSONObject(base.toString()).put("language", "ja"),
                    new JSONObject(base.toString()).put("fallbackProfileId", "zipformer"),
                    new JSONObject(base.toString()).put("fallbackProfileId", "unknown"),
                    new JSONObject(base.toString()).put("fallbackProfileId", 3),
                    new JSONObject(base.toString()).put("resolvedLanguage", "zh-CN"),
                    audioSettings("resetDictation"),
                    audioSettings("resetDictation").put("scope", "assistant-entry").put("language", "system"),
                    audioSettings("resetDictation").put("scope", "other"),
            };
            byte[] before = bytes(settings);
            for (JSONObject request : invalid) {
                rejectSettings(request, "INVALID_ARGUMENT");
                check(Arrays.equals(before, bytes(settings)), "invalid dictation command preserves root settings");
            }

            JSONObject reset = AudioConfigBridge.settings(audioSettings("resetDictation").put("scope", "assistant-entry"));
            check(!reset.getJSONObject("dictation").has("assistant-entry"), "reset removes only the dictation scope");
            long revision = reset.getLong("audioRevision");
            JSONObject repeated = AudioConfigBridge.settings(audioSettings("resetDictation").put("scope", "assistant-entry"));
            check(repeated.getLong("audioRevision") == revision, "reset on an absent scope does not advance revision");

            JSONObject saved = AudioConfigBridge.settings(
                    audioSettings("configureDictation").put("scope", "assistant-entry")
                            .put("primaryProfileId", "zipformer").put("fallbackProfileId", JSONObject.NULL)
                            .put("language", "system"));
            check(saved.getLong("audioRevision") == revision + 1, "configure advances revision once");
            JSONObject block = saved.getJSONObject("dictation").getJSONObject("assistant-entry");
            check(block.length() == 3 && block.getString("primaryProfileId").equals("zipformer")
                    && block.isNull("fallbackProfileId") && block.getString("language").equals("system"),
                    "saved dictation fields are exact");
            check(saved.getJSONObject("reading").toString().equals(repeated.getJSONObject("reading").toString()),
                    "dictation save preserves reading profiles");

            boolean falConfigured = NativeBridge.byok.falSettings().getBoolean("configured");
            JSONObject cloud = new JSONObject(base.toString()).put("primaryProfileId", "fal-whisper").put("language", "en-US");
            if (falConfigured) {
                JSONObject cloudSaved = AudioConfigBridge.settings(cloud);
                check(cloudSaved.getJSONObject("dictation").getJSONObject("assistant-entry")
                        .getString("primaryProfileId").equals("fal-whisper"), "readable fal credential admits cloud selection");
            } else rejectSettings(cloud, "PROVIDER_NOT_CONFIGURED");

            byte[] valid = bytes(settings);
            Files.write(settings.toPath(), "{".getBytes(StandardCharsets.UTF_8));
            rejectSettings(new JSONObject(base.toString()), "SETTINGS_UNREADABLE");
            check(Arrays.equals("{".getBytes(StandardCharsets.UTF_8), bytes(settings)), "unreadable file is never overwritten");
            Files.write(settings.toPath(), "{\"revision\":1}".getBytes(StandardCharsets.UTF_8));
            rejectSettings(new JSONObject(base.toString()), "SETTINGS_UNREADABLE");
            Files.write(settings.toPath(), valid);

            JSONObject broken = new JSONObject(new String(valid, StandardCharsets.UTF_8));
            broken.put("dictation", "broken");
            Files.write(settings.toPath(), broken.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject containerRepaired = AudioConfigBridge.settings(new JSONObject(base.toString()).put("language", "en-US"));
            check(containerRepaired.getJSONObject("dictation").getJSONObject("assistant-entry")
                    .getString("language").equals("en-US"), "configure replaces a malformed dictation container");
            check(containerRepaired.getJSONObject("reading").toString().equals(saved.getJSONObject("reading").toString()),
                    "container repair preserves reading profiles");

            JSONObject brokenScope = new JSONObject(new String(bytes(settings), StandardCharsets.UTF_8));
            brokenScope.getJSONObject("dictation").put("assistant-entry", "broken");
            Files.write(settings.toPath(), brokenScope.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject repaired = AudioConfigBridge.settings(new JSONObject(base.toString()).put("language", "zh-CN"));
            check(repaired.getJSONObject("dictation").getJSONObject("assistant-entry").getString("language").equals("zh-CN"),
                    "configure replaces a malformed scope block");
            check(repaired.getJSONObject("reading").toString().equals(saved.getJSONObject("reading").toString()),
                    "repair preserves reading profiles");
            System.out.println("PASS dictation settings validate atomically, preserve reading, and repair only the scope block");
        } finally {
            if (original == null) Files.deleteIfExists(settings.toPath());
            else Files.write(settings.toPath(), original);
            AudioConfigBridge.changed();
        }
    }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        try { run(); } catch (Exception e) { System.err.println(e.getMessage()); System.exit(1); }
    }
    static void run() throws Exception {
        settingsBoundary();
        localFalSettings();
        dictationSettings();
        File folder = new File("/data/local/tmp/taku-byok-test-" + UUID.randomUUID());
        ByokProviders service = new ByokProviders(folder, MAGPIE, "http://127.0.0.1:3430");
        Map<String, String> before = storedProviders(); String created = null;
        String fal1 = "fixture-fal-" + UUID.randomUUID(), fal2 = "fixture-fal-" + UUID.randomUUID();
        String or1 = "sk-or-v1-fixture-" + UUID.randomUUID(), or2 = "sk-or-v1-fixture-" + UUID.randomUUID();
        try {
            JSONObject initial = service.control(call("falSettings", ""));
            check(!initial.getBoolean("configured") && initial.getString("model").equals("kotoba"), "default disabled Kotoba selection");
            boolean denied = false;
            try { service.control(call("selectFalModel", "").put("model", "grok")); } catch (IOException e) { denied = true; }
            check(denied, "missing key blocks model changes in backend");
            JSONObject value = service.control(call("save", "fal").put("key", fal1));
            check(service.control(call("selectFalModel", "").put("model", "whisper")).getString("model").equals("whisper"), "Whisper caption model selectable with key");
            JSONObject selection = service.control(call("selectFalModel", "").put("model", "grok"));
            check(selection.getBoolean("configured") && selection.getString("model").equals("grok"), "model selection enabled with key");
            check(!selection.toString().contains(fal1), "model settings contain no credential");
            denied = false;
            try { service.control(call("selectFalModel", "").put("model", "unknown")); } catch (IOException e) { denied = true; }
            check(denied, "unknown model rejected");
            check(!value.toString().contains(fal1), "secret omitted from response");
            check(!value.getBoolean("falRealtimeAvailable"), "fal capability stays honest");
            check((android.system.Os.stat(folder.getPath()).st_mode & 0777) == 0700, "private directory");
            check((android.system.Os.stat(new File(folder, "fal.json").getPath()).st_mode & 0777) == 0600, "private file");
            ByokProviders reopened = new ByokProviders(folder, MAGPIE, "http://127.0.0.1:3430");
            find(reopened.control(call("read", "")), "fal", fal1);
            check(reopened.falSettings().getString("model").equals("grok"), "model survives reopening");
            service.control(call("save", "fal").put("key", fal2));
            check(new JSONObject(new String(Files.readAllBytes(new File(folder, "fal.json").toPath()), StandardCharsets.UTF_8)).getString("key").equals(fal2), "replacement persisted");
            for (String invalid : new String[]{"", "two keys", "one\ntwo", "one,two"}) {
                boolean rejected = false;
                try { service.control(call("save", "fal").put("key", invalid)); } catch (IOException e) { rejected = true; }
                check(rejected, "invalid key rejected");
            }
            boolean rejected = false;
            try { service.control(call("save", "unknown").put("key", "test")); } catch (IOException e) { rejected = true; }
            check(rejected, "unknown provider rejected");
            service.control(call("remove", "fal")); check(!new File(folder, "fal.json").exists(), "fal removed");
            check(!service.falSettings().getBoolean("configured") && service.falSettings().getString("model").equals("grok"), "key removal disables selection but preserves preference");
            service.control(call("save", "fal").put("key", fal2));
            check(service.falSettings().getString("model").equals("grok"), "key replacement preserves model");
            service.control(call("selectFalModel", "").put("model", "kotoba"));
            service.control(call("remove", "fal"));
            System.out.println("PASS fal model gating, validation, persistence and key replacement/removal");
            System.out.println("PASS fal persistence, replacement, removal, permissions, masking and validation");

            value = service.control(call("save", "openrouter").put("key", or1));
            JSONObject first = find(value, "openrouter", or1); created = first.getString("id");
            check(!before.containsKey(created), "new provider does not replace existing provider");
            check(!value.toString().contains(or1), "OpenRouter response masked");
            int count = storedProviders().size(); service.control(call("save", "openrouter").put("key", or1));
            check(storedProviders().size() == count, "same key does not create duplicate");
            JSONObject replacement = call("save", "openrouter").put("id", created).put("ref", first.getString("ref")).put("key", or2);
            value = service.control(replacement); JSONObject second = find(value, "openrouter", or2);
            check(!value.toString().contains(or2), "replacement response masked");
            rejected = false;
            try { service.control(call("remove", "openrouter").put("id", created).put("ref", first.getString("ref"))); }
            catch (IOException e) { rejected = true; }
            check(rejected, "stale reference cannot remove replacement");
            service.control(call("remove", "openrouter").put("id", created).put("ref", second.getString("ref")));
            boolean removed = false; try { find(service.control(call("read", "")), "openrouter", or2); } catch (AssertionError e) { removed = true; }
            check(removed, "OpenRouter removed from Magpie");
            System.out.println("PASS actual Magpie create, deduplicate, replace and remove");
        } finally {
            // Also recover a provider created before a failed response/readback.
            for (String id : storedProviders().keySet()) if (!before.containsKey(id) && id.startsWith("taku-byok-openrouter")) {
                if (id.equals(created) || storedProviders().get(id).contains(or1) || storedProviders().get(id).contains(or2)) deleteTestProvider(id);
            }
            Files.deleteIfExists(new File(folder, "fal.json").toPath()); Files.deleteIfExists(new File(folder, "fal.json.tmp").toPath()); Files.deleteIfExists(new File(folder, "fal-model.json").toPath()); Files.deleteIfExists(folder.toPath());
        }
        check(before.equals(storedProviders()), "existing provider settings preserved");
        System.out.println("PASS existing providers preserved; test keys cleaned up; no model generation");
    }
}
