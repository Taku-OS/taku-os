package org.agentos.assistant;

import org.json.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable user intent and the durable one-click boundary. No UI or model result is a receipt. */
final class DiningPollSendState {
    private DiningPollSendState() {}

    static JSONObject registration(JSONObject p) throws Exception {
        String id = uuid(p, "intentId"), thread = uuid(p, "sourceThreadId");
        String group = text(p, "groupName", 160), question = text(p, "question", 120);
        JSONArray supplied = p.getJSONArray("options"), options = new JSONArray();
        Set<String> distinct = new HashSet<>();
        if (supplied.length() < 2 || supplied.length() > 12) throw failure("POLL_OPTIONS_INVALID");
        for (int i = 0; i < supplied.length(); i++) {
            JSONObject value = new JSONObject().put("name", supplied.get(i));
            String name = text(value, "name", 100);
            if (!distinct.add(name.toLowerCase(Locale.ROOT))) throw failure("POLL_OPTIONS_INVALID");
            options.put(name);
        }
        JSONObject reply = p.getJSONObject("sourceReply");
        if (reply.length() == 0 || reply.toString().length() > 262144)
            throw failure("POLL_SOURCE_REPLY_INVALID");
        JSONObject binding =
                new JSONObject()
                        .put("intentId", id)
                        .put("sourceThreadId", thread)
                        .put("sourceReply", new JSONObject(reply.toString()))
                        .put("groupName", group)
                        .put("question", question)
                        .put("options", options);
        return binding.put("contentHash", hash(canonical(binding)))
                .put("status", "registered")
                .put("sent", false);
    }

    private static final String QUESTION_CODE_VERSION = "question-code/1";

    /**
     * Only the newly created durable entry is upgraded; existing journals retain their protocol.
     */
    static JSONObject withQuestionCode(JSONObject fresh, JSONObject all) throws Exception {
        if (!"registered".equals(fresh.optString("status"))
                || fresh.has("binding")
                || fresh.has("sendAttemptedAt")
                || fresh.has("attemptAt")
                || fresh.has("anchor")
                || fresh.has("sentAt")
                || fresh.optBoolean("sent")) throw failure("POLL_IDENTITY_UPGRADE_NOT_ALLOWED");
        String id = uuid(fresh, "intentId"), code = questionCode(id);
        for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            JSONObject old = all.getJSONObject(keys.next());
            if (code.equals(old.optString("pollCode")) && !id.equals(old.optString("intentId")))
                throw failure("POLL_CODE_COLLISION");
        }
        JSONObject coded =
                new JSONObject(fresh.toString())
                        .put("identityVersion", QUESTION_CODE_VERSION)
                        .put("pollCode", code)
                        .put("wireQuestion", fresh.getString("question") + "〔Taku·" + code + "〕");
        wireQuestion(coded);
        return coded;
    }

    private static String questionCode(String id) {
        return id.replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    static boolean usesQuestionCode(JSONObject intent) throws Exception {
        if (!intent.has("identityVersion")
                && !intent.has("pollCode")
                && !intent.has("wireQuestion")) return false;
        wireQuestion(intent);
        return true;
    }

    /** Fail closed on partial/corrupted protocol fields, never fall back to a different poll. */
    static String wireQuestion(JSONObject intent) throws Exception {
        if (!intent.has("identityVersion")
                && !intent.has("pollCode")
                && !intent.has("wireQuestion")) return intent.getString("question");
        String code = questionCode(uuid(intent, "intentId"));
        String question = intent.getString("question") + "〔Taku·" + code + "〕";
        if (!QUESTION_CODE_VERSION.equals(intent.optString("identityVersion"))
                || !code.equals(intent.optString("pollCode"))
                || !question.equals(intent.optString("wireQuestion"))
                || question.length() > 255) throw failure("POLL_MESSAGE_CODE_INVALID");
        return question;
    }

    static void sameIntent(JSONObject previous, JSONObject registration) throws Exception {
        if (!previous.getString("contentHash").equals(registration.getString("contentHash")))
            throw failure("POLL_INTENT_CHANGED");
    }

    static boolean mayClick(JSONObject intent) {
        return "bound".equals(intent.optString("status"))
                && !intent.has("sendAttemptedAt")
                && !intent.has("attemptAt")
                && !intent.has("anchor")
                && !intent.has("sentAt")
                && !intent.optBoolean("sent");
    }

    static JSONObject beforeClick(JSONObject intent, long now) throws Exception {
        if (!mayClick(intent)) throw failure("POLL_SEND_NOT_REPLAYABLE");
        return new JSONObject(intent.toString())
                .put("status", "unknown")
                .put("sent", false)
                .put("sendAttemptedAt", now);
    }

    static JSONObject confirmed(JSONObject intent, String anchor, long now) throws Exception {
        if (!List.of("unknown", "sent").contains(intent.optString("status"))
                || anchor == null
                || anchor.isEmpty()) throw failure("POLL_SEND_RECEIPT_INVALID");
        if (intent.has("anchor") && !anchor.equals(intent.getString("anchor")))
            throw failure("POLL_MESSAGE_CHANGED");
        return new JSONObject(intent.toString())
                .put("status", "sent")
                .put("sent", true)
                .put("anchor", anchor)
                .put("sentAt", now);
    }

    static String uuid(JSONObject p, String field) throws Exception {
        String value = p.getString(field);
        if (!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw failure("INVALID_" + field.toUpperCase(Locale.ROOT));
        return value;
    }

    static String text(JSONObject p, String field, int max) throws Exception {
        Object raw = p.get(field);
        if (!(raw instanceof String value)
                || value.isBlank()
                || !value.equals(value.strip())
                || value.length() > max
                || value.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
            throw failure("POLL_INVALID_" + field.toUpperCase(Locale.ROOT));
        return value;
    }

    static String hash(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    static java.io.File cleanupTarget(JSONObject args) throws Exception {
        String token = uuid(args, "operationToken"),
                root = "/data/user/0/org.agentos.assistant/files/runtime/dining-poll-input",
                base = root + "/" + token + "/";
        java.io.File cancel = new java.io.File(args.getString("cancelPath")),
                cleanup = new java.io.File(args.getString("cleanupPath"));
        String canonicalRoot = new java.io.File(root).getCanonicalPath() + "/" + token + "/";
        if (!cancel.getAbsolutePath().equals(base + "cancel")
                || !cleanup.getAbsolutePath().equals(base + "cleanup.json")
                || !cancel.getCanonicalPath().equals(canonicalRoot + "cancel")
                || !cleanup.getCanonicalPath().equals(canonicalRoot + "cleanup.json"))
            throw failure("POLL_CLEANUP_PATH_INVALID");
        return cleanup;
    }

    static void sameExecutionScreen(JSONObject binding, JSONObject screen) throws Exception {
        for (String field :
                List.of(
                        "executionThreadId",
                        "screenKey",
                        "generation",
                        "taskId",
                        "userId",
                        "displayId"))
            if (!binding.get(field).equals(screen.get(field)))
                throw failure("POLL_EXECUTION_SCREEN_CHANGED");
    }

    /** A new foreground task may only reidentify an already confirmed, coded message. */
    static boolean codedReceiptConfirmed(JSONObject intent) {
        try {
            if (!"sent".equals(intent.optString("status"))
                    || !Boolean.TRUE.equals(intent.opt("sent"))
                    || !usesQuestionCode(intent)) return false;
            JSONObject receipt = intent.getJSONObject("receipt"),
                    identity = receipt.getJSONObject("messageIdentity");
            String expected = DiningPollSendEvidence.codedAnchor(intent, identity);
            return expected.equals(intent.optString("anchor"))
                    && expected.equals(receipt.optString("anchor"));
        } catch (Exception unavailable) {
            return false;
        }
    }

    static void sameExecutionScreen(JSONObject binding, JSONObject screen, JSONObject intent)
            throws Exception {
        if (!screen.has("readOnlyRebound")) {
            sameExecutionScreen(binding, screen);
            return;
        }
        if (!Boolean.TRUE.equals(screen.opt("readOnlyRebound"))
                || !codedReceiptConfirmed(intent)
                || !"observe".equals(screen.optString("action"))
                || !Boolean.TRUE.equals(screen.opt("readOnlyForeground"))
                || screen.getInt("displayId") != 0
                || binding.getInt("displayId") <= 0
                || screen.optInt("originalDisplayId", -1) != binding.getInt("displayId")
                || screen.optInt("originalTaskId", -1) != binding.getInt("taskId")
                || screen.getInt("taskId") < 0
                || screen.getInt("taskId") == binding.getInt("taskId"))
            throw failure("POLL_REBOUND_OBSERVE_SCOPE_INVALID");
        for (String field :
                List.of("executionThreadId", "turnId", "screenKey", "generation", "userId"))
            if (!binding.get(field).equals(screen.get(field)))
                throw failure("POLL_EXECUTION_SCREEN_CHANGED");
    }

    /** Inventory is captured from Android immediately before/after the passive UI read. */
    static void reboundTask(
            JSONObject screen, JSONArray tasks, int currentUser, boolean userForeground)
            throws Exception {
        if (!Boolean.TRUE.equals(screen.opt("readOnlyRebound"))
                || !Boolean.TRUE.equals(screen.opt("readOnlyForeground"))
                || !"observe".equals(screen.optString("action"))
                || screen.optInt("displayId", -1) != 0
                || screen.optInt("userId", -1) != currentUser
                || !userForeground) throw failure("POLL_REBOUND_OBSERVE_SCOPE_INVALID");
        JSONObject top = null, selected = null;
        int visibleWhatsApp = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            if (task.getInt("taskId") == screen.getInt("originalTaskId"))
                throw failure("POLL_ORIGINAL_TASK_STILL_PRESENT");
            if (task.getInt("userId") != currentUser
                    || task.getInt("displayId") != 0
                    || !task.optBoolean("visible")) continue;
            if (top == null) top = task;
            if ("com.whatsapp".equals(task.optString("basePackage"))
                    || "com.whatsapp".equals(task.optString("topPackage"))) visibleWhatsApp++;
            if (task.getInt("taskId") == screen.getInt("taskId")) {
                if (selected != null) throw failure("POLL_EXECUTION_TASK_CHANGED");
                selected = task;
            }
        }
        if (selected == null
                || selected != top
                || visibleWhatsApp != 1
                || !"com.whatsapp".equals(selected.optString("basePackage"))
                || !"com.whatsapp".equals(selected.optString("topPackage")))
            throw failure("POLL_EXECUTION_TASK_CHANGED");
    }

    /** This context can retain a prompt briefly; it never grants input or sending. */
    static JSONObject observationScreen(JSONObject intent, JSONObject proof) throws Exception {
        if (!codedReceiptConfirmed(intent)
                || !intent.getString("intentId").equals(proof.optString("intentId"))
                || !intent.getString("anchor").equals(proof.optString("anchor"))
                || !DiningPollSendEvidence.sameMessage(
                        intent.getJSONObject("receipt").getJSONObject("messageIdentity"),
                        proof.optJSONObject("messageIdentity")))
            throw failure("POLL_MESSAGE_CHANGED");
        int task = proof.getInt("observationTaskId"),
                display = proof.getInt("observationDisplayId"),
                user = proof.getInt("observationUserId");
        if (task < 0 || display < 0 || user < 0) throw failure("POLL_EXECUTION_SCREEN_INVALID");
        return new JSONObject()
                .put("taskId", task)
                .put("displayId", display)
                .put("userId", user)
                .put("anchor", intent.getString("anchor"));
    }

    static JSONObject recentObservationScreen(JSONObject watch, JSONObject intent, long now)
            throws Exception {
        long at = watch.optLong("lastObservedAt");
        JSONObject observed = watch.optJSONObject("lastObservationScreen");
        if (!codedReceiptConfirmed(intent)
                || at <= 0
                || now < at
                || now - at > 5000
                || observed == null
                || !intent.getString("intentId").equals(watch.optString("sendIntentId"))
                || !intent.getString("anchor").equals(watch.optString("anchor"))
                || !intent.getString("anchor").equals(observed.optString("anchor"))) return null;
        return new JSONObject(observed.toString());
    }

    static boolean keepBusyOffer(
            String code, long lastObservedAt, long now, boolean sameTaskVisible) {
        return List.of("POLL_EDITOR_BUSY", "DINING_POLL_OBSERVER_BUSY", "DINING_POLL_INPUT_BUSY")
                        .contains(code)
                && sameTaskVisible
                && lastObservedAt > 0
                && now >= lastObservedAt
                && now - lastObservedAt <= 5_000;
    }

    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject object) {
            List<String> keys = new ArrayList<>();
            for (Iterator<String> i = object.keys(); i.hasNext(); ) keys.add(i.next());
            Collections.sort(keys);
            StringJoiner result = new StringJoiner(",", "{", "}");
            for (String key : keys)
                result.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
            return result.toString();
        }
        if (value instanceof JSONArray array) {
            StringJoiner result = new StringJoiner(",", "[", "]");
            for (int i = 0; i < array.length(); i++) result.add(canonical(array.get(i)));
            return result.toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    static IOException failure(String code) {
        return new IOException(code);
    }
}
