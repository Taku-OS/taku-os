package org.agentos.lab;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manages incremental registration and removal of the built-in "taku-credits" Magpie fallback
 * provider without overwriting existing BYOK providers, ChatGPT subscriptions, or scene bindings.
 */
final class TakuCreditsCatalog {
    static final String PROVIDER_ID = "taku-credits";
    static final String PROVIDER_NAME = "Taku Credits";
    static final String RELAY_CHAT_URL = "http://127.0.0.1:3426/openai/v1";
    static final String RELAY_ANTHROPIC_URL = "http://127.0.0.1:3426/claude";
    static final String RELAY_DECIDE_URL = "http://127.0.0.1:3426/decide";

    static final Set<String> LEGACY_RESPONSES_AND_CHAT_MODELS = unmodifiableSet(
            "gpt-5.5",
            "gpt-5.6-luna",
            "gpt-5.6-terra",
            "gpt-5.6-sol",
            "glm-5.2");

    static final Set<String> LEGACY_CHAT_ONLY_MODELS = unmodifiableSet(
            "glm-5.3",
            "deepseek/deepseek-v4-pro",
            "deepseek/deepseek-v4-flash",
            "moonshotai/kimi-k2.7-code");

    static final Set<String> LEGACY_CHAT_AND_MESSAGES_MODELS = unmodifiableSet(
            "claude-opus-4-8",
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-opus-4-5",
            "claude-sonnet-4-5",
            "claude-haiku-4-5-20251001");

    static final Set<String> ALL_LEGACY_ALLOWED_MODELS = buildAllAllowed();

    interface ProjectionSync {
        void sync(SystemAiSettings.Store store, File source, File secretFile) throws Exception;
    }

    private static volatile ProjectionSync defaultProjection = TakuCreditsCatalog::hostSafeProjection;

    private TakuCreditsCatalog() {}

    static void setDefaultProjection(ProjectionSync projection) {
        if (projection != null) {
            defaultProjection = projection;
        }
    }

    static boolean isLegacyAllowedModel(String modelId) {
        return modelId != null && ALL_LEGACY_ALLOWED_MODELS.contains(modelId);
    }

    static boolean supportsResponsesProtocol(String modelId) {
        return modelId != null && LEGACY_RESPONSES_AND_CHAT_MODELS.contains(modelId);
    }

    static boolean supportsMessagesProtocol(String modelId) {
        return modelId != null
                && ("glm-5.2".equals(modelId) || LEGACY_CHAT_AND_MESSAGES_MODELS.contains(modelId));
    }

    static synchronized void registerProvider(
            SystemAiSettings.Store store,
            File source,
            File secretFile,
            String relayToken) throws Exception {
        registerProvider(store, source, secretFile, relayToken, defaultProjection);
    }

    static synchronized void registerProvider(
            SystemAiSettings.Store store,
            File source,
            File secretFile,
            String relayToken,
            ProjectionSync projection) throws Exception {
        if (relayToken == null || relayToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Relay token must not be blank");
        }
        JSONObject config = readOrInitConfig(store);
        JSONArray providers = optArray(config, "providers");
        JSONObject models = optObject(config, "models");
        JSONObject secrets = readJsonFile(secretFile);

        // Preserve unrelated BYOK providers and write relay token only to taku-credentials.json.
        JSONArray updatedProviders = new JSONArray();
        for (int i = 0; i < providers.length(); i++) {
            JSONObject existing = providers.getJSONObject(i);
            if (!PROVIDER_ID.equals(existing.optString("id"))) {
                updatedProviders.put(existing);
            }
        }
        updatedProviders.put(buildPublicProviderMetadata());

        JSONArray modelArray = new JSONArray();
        for (String modelId : ALL_LEGACY_ALLOWED_MODELS) {
            modelArray.put(modelId);
        }
        models.put(PROVIDER_ID, modelArray);

        secrets.put(PROVIDER_ID, new JSONObject().put("key", relayToken.trim()));
        writePrivateJson(secretFile, secrets);

        config.put("providers", updatedProviders)
                .put("models", models)
                .put("updated_at", System.currentTimeMillis())
                .put("updated_by", "taku");
        store.put(SystemAiSettings.CONFIG, config.toString());
        projection.sync(store, source, secretFile);
    }

    static synchronized void unregisterProvider(
            SystemAiSettings.Store store,
            File source,
            File secretFile) throws Exception {
        unregisterProvider(store, source, secretFile, defaultProjection);
    }

    static synchronized void unregisterProvider(
            SystemAiSettings.Store store,
            File source,
            File secretFile,
            ProjectionSync projection) throws Exception {
        String raw = store.get(SystemAiSettings.CONFIG);
        if (raw == null || raw.trim().isEmpty()) {
            removeSecretEntry(secretFile);
            return;
        }
        JSONObject config = new JSONObject(raw);
        JSONArray providers = optArray(config, "providers");
        JSONArray updatedProviders = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < providers.length(); i++) {
            JSONObject existing = providers.getJSONObject(i);
            if (PROVIDER_ID.equals(existing.optString("id"))) {
                changed = true;
            } else {
                updatedProviders.put(existing);
            }
        }
        JSONObject models = optObject(config, "models");
        if (models.has(PROVIDER_ID)) {
            models.remove(PROVIDER_ID);
            changed = true;
        }
        boolean secretRemoved = removeSecretEntry(secretFile);
        if (changed || secretRemoved) {
            config.put("providers", updatedProviders)
                    .put("models", models)
                    .put("updated_at", System.currentTimeMillis())
                    .put("updated_by", "taku");
            store.put(SystemAiSettings.CONFIG, config.toString());
            projection.sync(store, source, secretFile);
        }
    }

    private static JSONObject buildPublicProviderMetadata() throws Exception {
        return new JSONObject()
                .put("id", PROVIDER_ID)
                .put("name", PROVIDER_NAME)
                .put("chat", RELAY_CHAT_URL)
                .put("anthropic", RELAY_ANTHROPIC_URL)
                .put("decide", RELAY_DECIDE_URL)
                .put("credentialRef", PROVIDER_ID);
    }

    private static JSONObject readOrInitConfig(SystemAiSettings.Store store) throws Exception {
        String raw = store.get(SystemAiSettings.CONFIG);
        if (raw != null && !raw.trim().isEmpty()) {
            return new JSONObject(raw);
        }
        return new JSONObject()
                .put("version", 1)
                .put("updated_at", System.currentTimeMillis())
                .put("updated_by", "taku")
                .put("providers", new JSONArray())
                .put("models", new JSONObject())
                .put("groups", new JSONArray())
                .put("scenes", new JSONObject());
    }

    private static boolean removeSecretEntry(File secretFile) throws Exception {
        if (!secretFile.exists()) return false;
        JSONObject secrets = readJsonFile(secretFile);
        if (!secrets.has(PROVIDER_ID)) return false;
        secrets.remove(PROVIDER_ID);
        writePrivateJson(secretFile, secrets);
        return true;
    }

    static void hostSafeProjection(SystemAiSettings.Store store, File source, File secretFile) throws Exception {
        String raw = store.get(SystemAiSettings.CONFIG);
        if (raw == null) return;
        JSONObject config = new JSONObject(raw);
        JSONArray providers = optArray(config, "providers");
        JSONObject models = optObject(config, "models");
        JSONArray groups = optArray(config, "groups");
        JSONObject secrets = readJsonFile(secretFile);
        JSONArray projectedProviders = new JSONArray();
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = new JSONObject(providers.getJSONObject(i).toString());
            JSONObject probe = new JSONObject();
            MagpieProviderSecrets.split(new JSONObject(p.toString()), probe);
            if (probe.length() > 0) {
                throw new IllegalStateException("Settings provider must not embed plaintext credentials");
            }
            String id = p.getString("id");
            String ref = p.optString("credentialRef", "");
            p.remove("credentialRef");
            p.remove("models");
            if (models.has(id)) {
                p.put("models", models.getJSONArray(id));
            }
            if (!ref.isEmpty()) {
                JSONObject s = secrets.optJSONObject(ref);
                if (s != null) {
                    MagpieProviderSecrets.restore(p, s);
                }
            }
            projectedProviders.put(p);
        }
        JSONObject out = new JSONObject()
                .put("providers", projectedProviders)
                .put("groups", groups);
        writePrivateJson(source, out);
    }

    private static JSONArray optArray(JSONObject obj, String key) {
        JSONArray value = obj.optJSONArray(key);
        return value != null ? value : new JSONArray();
    }

    private static JSONObject optObject(JSONObject obj, String key) {
        JSONObject value = obj.optJSONObject(key);
        return value != null ? value : new JSONObject();
    }

    private static JSONObject readJsonFile(File file) throws Exception {
        if (!file.isFile()) return new JSONObject();
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static void writePrivateJson(File target, JSONObject value) throws Exception {
        File dir = target.getParentFile();
        if (dir != null) {
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IllegalStateException("Cannot create Magpie directory");
            }
            dir.setReadable(false, false);
            dir.setWritable(false, false);
            dir.setExecutable(false, false);
            dir.setReadable(true, true);
            dir.setWritable(true, true);
            dir.setExecutable(true, true);
        }
        File tmp = new File(target.getPath() + ".taku.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        tmp.setReadable(false, false);
        tmp.setWritable(false, false);
        tmp.setExecutable(false, false);
        tmp.setReadable(true, true);
        tmp.setWritable(true, true);
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Set<String> unmodifiableSet(String... values) {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, values);
        return Collections.unmodifiableSet(set);
    }

    private static Set<String> buildAllAllowed() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(LEGACY_RESPONSES_AND_CHAT_MODELS);
        all.addAll(LEGACY_CHAT_ONLY_MODELS);
        all.addAll(LEGACY_CHAT_AND_MESSAGES_MODELS);
        return Collections.unmodifiableSet(all);
    }
}
