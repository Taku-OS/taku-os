package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** Updates the open-access instructions only when their last official digest still matches. */
final class BuiltinSkillUpdate {
    static final String NAME = "taku-open-access";
    interface Write { void save(File file, String text) throws Exception; }

    private BuiltinSkillUpdate() {}

    static void ensure(File body, File receipt, String bundled) throws Exception {
        ensure(body, receipt, bundled, TaskBroker::write);
    }

    // The writer also lets the filesystem test interrupt a real update at durable write boundaries.
    static synchronized void ensure(File body, File receipt, String bundled, Write writer)
            throws Exception {
        if (!receipt.getCanonicalPath().equals(receipt.getAbsolutePath()))
            throw new IOException("BUILTIN_SKILL_RECEIPT_PATH_CHANGED");
        JSONObject record;
        try {
            record = receipt.isFile() ? new JSONObject(TaskBroker.read(receipt)) : new JSONObject();
        } catch (JSONException damaged) {
            record = new JSONObject();
        }
        String target = HarnessStore.hash(bundled);
        if (!body.getCanonicalPath().equals(body.getAbsolutePath())) {
            notice(body, receipt, record, target, "", "UNSAFE_SKILL_PATH", writer);
            return;
        }
        if (!body.isFile()) {
            notice(body, receipt, record, target, "", "MISSING_FILE", writer);
            return;
        }
        String current = HarnessStore.hash(TaskBroker.read(body));
        File stage = new File(body.getPath() + ".builtin-next");
        if (!stage.getCanonicalPath().equals(stage.getAbsolutePath())) {
            notice(body, receipt, record, target, current, "UNSAFE_SKILL_PATH", writer);
            return;
        }
        boolean trusted = trusted(record, body);
        JSONObject pending = record.optJSONObject("pendingBuiltinUpdate");
        if (record.has("pendingBuiltinUpdate")) {
            if (!trusted || pending == null
                    || !record.optString("bodySha256").equals(pending.optString("fromSha256"))
                    || !pending.optString("toSha256").matches("[a-f0-9]{64}")
                    || !pending.optString("phase").equals("prepared")) {
                notice(body, receipt, record, target, current, "INVALID_UPDATE_RECORD", writer);
                return;
            }
            String next = pending.getString("toSha256");
            if (current.equals(next)) {
                // The body rename completed before the process died while committing its receipt.
                record = official(body, next);
                save(receipt, record, writer);
            } else if (!current.equals(pending.getString("fromSha256"))) {
                record.remove("pendingBuiltinUpdate");
                notice(body, receipt, record, target, current, "LOCAL_MODIFIED", writer);
                if (stage.exists() && !stage.delete()) throw new IOException("BUILTIN_SKILL_STAGE_NOT_REMOVED");
                return;
            } else {
                // No body was changed. Retry using the currently installed APK, even after a
                // second APK upgrade or loss of a disposable staged file.
                record.remove("pendingBuiltinUpdate");
                save(receipt, record, writer);
            }
        }
        if (current.equals(target) && (trusted(record, body) || !record.has("repo"))) {
            save(receipt, official(body, target), writer);
            if (stage.exists() && !stage.delete()) throw new IOException("BUILTIN_SKILL_STAGE_NOT_REMOVED");
            return;
        }
        if (!trusted(record, body)) {
            notice(body, receipt, record, target, current, "UNVERIFIED_BASELINE", writer);
            return;
        }
        if (!current.equals(record.getString("bodySha256"))) {
            notice(body, receipt, record, target, current, "LOCAL_MODIFIED", writer);
            return;
        }
        writer.save(stage, bundled);
        if (!target.equals(HarnessStore.hash(TaskBroker.read(stage))))
            throw new IOException("BUILTIN_SKILL_STAGE_HASH_CHANGED");
        record.put("pendingBuiltinUpdate", new JSONObject().put("phase", "prepared")
                .put("fromSha256", current).put("toSha256", target));
        save(receipt, record, writer);
        // An independent edit during staging must be retained too.
        if (!current.equals(HarnessStore.hash(TaskBroker.read(body)))) {
            record.remove("pendingBuiltinUpdate");
            notice(body, receipt, record, target, HarnessStore.hash(TaskBroker.read(body)),
                    "LOCAL_MODIFIED", writer);
            if (!stage.delete()) throw new IOException("BUILTIN_SKILL_STAGE_NOT_REMOVED");
            return;
        }
        if (!stage.renameTo(body)) throw new IOException("BUILTIN_SKILL_ACTIVATION_FAILED");
        save(receipt, official(body, target), writer);
    }

    private static boolean trusted(JSONObject record, File body) {
        return NAME.equals(record.optString("name"))
                && body.getPath().equals(record.optString("path"))
                && "assistant-apk".equals(record.optString("repo"))
                && record.optString("bodySha256").matches("[a-f0-9]{64}");
    }

    private static JSONObject official(File body, String digest) throws Exception {
        return new JSONObject().put("name", NAME).put("path", body.getPath())
                .put("repo", "assistant-apk").put("commit", "builtin-1")
                .put("bodySha256", digest);
    }

    private static void notice(File body, File receipt, JSONObject record, String target,
            String current, String reason, Write writer) throws Exception {
        if (!record.has("name")) record.put("name", NAME);
        if (!record.has("path")) record.put("path", body.getPath());
        // An unverified installation gets a notice, never an invented official body digest.
        record.put("builtinUpdate", new JSONObject().put("status", "retained")
                .put("reason", reason).put("availableSha256", target)
                .put("currentSha256", current).put("message", switch (reason) {
                    case "LOCAL_MODIFIED" -> "Local edits retained; bundled instructions were not applied.";
                    case "MISSING_FILE" -> "Existing skill has no instructions file; installation retained.";
                    case "UNSAFE_SKILL_PATH" -> "Skill path changed; installation retained.";
                    case "INVALID_UPDATE_RECORD" -> "Update record could not be verified; instructions retained.";
                    default -> "Previous official digest is unavailable or untrusted; instructions retained.";
                }));
        save(receipt, record, writer);
    }

    private static void save(File receipt, JSONObject record, Write writer) throws Exception {
        String text = HarnessStore.canonical(record);
        if (receipt.isFile() && text.equals(TaskBroker.read(receipt))) return;
        receipt.getParentFile().mkdirs();
        writer.save(receipt, text);
    }
}
