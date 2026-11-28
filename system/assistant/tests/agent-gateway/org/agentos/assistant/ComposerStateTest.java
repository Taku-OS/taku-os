package org.agentos.assistant;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ComposerStateTest {
    private static final String THREAD = "00000000-0000-4000-8000-000000000001";
    private static final String TARGET = "00000000-0000-4000-8000-000000000002";
    private static final ComposerState STORE = ComposerState.INSTANCE;

    public static void main(String[] args) throws Exception {
        protocol();
        sharedRevision();
        importRecovery();
        moveAndDiskFailure();
        settlementPreservesLaterEdits();
        screenshotPreferenceIsFrozen();
        invocationCaptureIsReservedOnlyForSendableRequests();
        handoffPreservesDisplacedDraft();
        legacyHomeAndPendingDelivery();
        pluginProtocol();
        pluginLookup();
        pluginDraft();
        pluginSubmission();
        pluginLifecycle();
        System.out.println("PASS ComposerStateTest");
    }

    private static void protocol() throws Exception {
        check(AgentGatewayProtocol.request("composer/write", "{\"threadId\":\"\",\"revision\":\"\",\"text\":\"\"}").length() == 3,
                "the new slot accepts explicit empty thread, revision and text");
        check(AgentGatewayProtocol.request("composer/import", "{\"threadId\":\"\",\"text\":\" \"}").getString("text").equals(" "),
                "import preserves whitespace");
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/read", "{}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/read", "{\"threadId\":1}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/read", "{\"threadId\":\"../\"}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/read", "{\"threadId\":\"\",\"extra\":0}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/write", "{\"threadId\":\"\",\"revision\":null,\"text\":\"\"}"));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/move", new JSONObject()
                .put("threadId", THREAD).put("revision", "").put("targetThreadId", TARGET).toString()));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/move", new JSONObject()
                .put("threadId", "").put("revision", "").put("targetThreadId", "").toString()));
    }

    private static void sharedRevision() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("draft-" + THREAD, "old Android text").apply();
        JSONObject initial = ComposerState.read(prefs, THREAD);
        check(initial.getString("text").equals("old Android text") && initial.getString("revision").isEmpty(),
                "pre-revision Android text is readable in place");
        JSONObject changed = ComposerState.write(prefs, THREAD, "", "  open access\n");
        String revision = changed.getString("revision");
        check(!revision.isEmpty() && ComposerState.read(prefs, THREAD).getString("text").equals("  open access\n"),
                "a new read returns the exact shared text");
        check(ComposerState.write(prefs, THREAD, revision, "  open access\n").getString("revision").equals(revision),
                "an identical write retains the revision");
        String nativeRevision = ComposerState.write(prefs, THREAD, revision, "native edit").getString("revision");
        conflict(nativeRevision, () -> ComposerState.write(prefs, THREAD, revision, "stale update"));
        check(ComposerState.read(prefs, THREAD).getString("text").equals("native edit"), "stale write leaves native edit intact");
        check(ComposerState.read(prefs, TARGET).getString("revision").isEmpty(), "other thread remains untouched");
    }

    private static void importRecovery() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("draft-" + THREAD, "native draft")
                .putString("composer." + THREAD + ".attachments", "[{\"path\":\"/fixture/attachment\",\"name\":\"attachment\"}]").apply();
        JSONObject snapshot = STORE.importLegacy(prefs, THREAD, "legacy Expo draft");
        check(snapshot.getString("text").equals("native draft") && !snapshot.toString().contains("/fixture/attachment"),
                "import leaves active text intact and omits private paths");
        check(snapshot.getJSONArray("attachments").getJSONObject(0).getString("name").equals("attachment"),
                "native attachment remains in the editor");
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        JSONObject imported = STORE.importLegacy(restarted, THREAD, "legacy Expo draft");
        JSONObject entry = imported.getJSONArray("recovery").getJSONObject(0);
        check(entry.getString("status").equals("saved-draft"), "different legacy content is saved for recovery");
        String attachmentId = imported.getJSONArray("attachments").getJSONObject(0).getString("id");
        ComposerState.removeAttachment(restarted, THREAD, imported.getString("revision"), attachmentId);
        String empty = ComposerState.write(restarted, THREAD, ComposerState.read(restarted, THREAD).getString("revision"), "").getString("revision");
        JSONObject restored = ComposerState.restore(restarted, THREAD, empty, entry.getString("id"));
        check(restored.getString("text").equals("legacy Expo draft"), "recovered text is shared");
        check(restored.getJSONArray("recovery").length() == 0, "repeated import creates no duplicate recovery entry");
        JSONObject second = STORE.importLegacy(restarted, THREAD, "later legacy edit");
        JSONObject saved = second.getJSONArray("recovery").getJSONObject(0);
        check(second.getString("text").equals("legacy Expo draft") && saved.getString("text").equals("later legacy edit"),
                "a different legacy edit remains recoverable after an earlier import");
        STORE.importLegacy(restarted, THREAD, "legacy Expo draft");
        JSONArray replayed = STORE.importLegacy(new MemoryPreferences(restarted), THREAD, "later legacy edit").getJSONArray("recovery");
        check(replayed.length() == 1 && replayed.getJSONObject(0).getString("id").equals(saved.getString("id")),
                "replaying either import preserves a single recovery entry");
        STORE.importLegacy(restarted, TARGET, "");
        check(STORE.importLegacy(restarted, TARGET, "first nonempty").getString("text").equals("first nonempty"),
                "an empty import leaves the thread eligible for its legacy draft");
    }

    private static void moveAndDiskFailure() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        String sourceRevision = ComposerState.write(prefs, "", "", "new-slot draft").getString("revision");
        STORE.importLegacy(prefs, "", "saved new-slot draft");
        ComposerState.write(prefs, THREAD, "", "occupied");
        conflict(sourceRevision, () -> STORE.move(prefs, "", TARGET));
        rejects("DRAFT_TARGET_OCCUPIED", () -> STORE.move(prefs, sourceRevision, THREAD));
        prefs.failNextCommit = true;
        rejects("DRAFT_SAVE_FAILED", () -> STORE.move(prefs, sourceRevision, TARGET));
        check(ComposerState.read(prefs, "").getString("revision").equals(sourceRevision), "failed move retains its source revision");
        check(ComposerState.read(prefs, TARGET).getString("revision").isEmpty(), "failed move leaves the target untouched");
        JSONObject moved = STORE.move(prefs, sourceRevision, TARGET);
        check(moved.getString("threadId").equals(TARGET) && moved.getString("text").equals("new-slot draft"), "move returns target snapshot");
        JSONObject source = ComposerState.read(prefs, "");
        check(source.getString("text").isEmpty() && !source.getString("revision").equals(sourceRevision), "move clears and revises the new slot");
        check(moved.getJSONArray("recovery").length() == 1 && source.getJSONArray("recovery").length() == 0, "move transfers saved drafts");
    }

    private static void settlementPreservesLaterEdits() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        String file = "00000000-0000-4000-8000-000000000003";
        JSONObject attached = ComposerState.addAttachment(prefs, THREAD, "", new JSONObject()
                .put("path", "/fixture/" + file).put("name", "notes.txt").put("mime", "text/plain").put("size", 9));
        String revision = attached.getString("revision");
        JSONObject input = new JSONObject().put("threadId", THREAD).put("action", "sendMessage")
                .put("revision", revision).put("requestId", "00000000-0000-4000-8000-000000000004")
                .put("attachmentIds", new JSONArray().put(file));
        JSONObject frozen = STORE.reserveSubmission(prefs, input, (thread, target) -> kotlin.Unit.INSTANCE).getFirst();
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        ComposerState.write(restarted, THREAD, revision, "newer draft");
        check(!STORE.reserveSubmission(restarted, input, (thread, target) -> { throw new AssertionError("replay resolved live options"); }).getSecond(),
                "replay uses the frozen intent even after editing");
        JSONObject changed = new JSONObject(input.toString()).put("attachmentIds", new JSONArray());
        rejects("REQUEST_ID_REUSED", () -> STORE.reserveSubmission(restarted, changed, (thread, target) -> kotlin.Unit.INSTANCE));
        STORE.finishSubmission(restarted, frozen, true);
        JSONObject current = ComposerState.read(restarted, THREAD);
        check(current.getString("text").equals("newer draft") && current.getJSONArray("attachments").getJSONObject(0).getString("id").equals(file),
                "settlement clears neither newer text nor its attachments");
        check(current.getJSONArray("pendingReceipts").length() == 0, "settlement closes the old pending request");
    }

    private static void screenshotPreferenceIsFrozen() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putBoolean("includeScreenshot", true).commit();
        String revision = ComposerState.write(prefs, THREAD, "", "Use the previous screen").getString("revision");
        JSONObject input = new JSONObject().put("threadId", THREAD).put("action", "sendMessage")
                .put("revision", revision).put("requestId", "00000000-0000-4000-8000-000000000006")
                .put("attachmentIds", new JSONArray());
        JSONObject first = STORE.reserveSubmission(prefs, input, (thread, options) -> {
            check(options.getBoolean("includeScreenshot"), "the legacy screenshot opt-in reaches the capture reservation");
            options.put("invocationScreenshot", input.getString("requestId"));
            return kotlin.Unit.INSTANCE;
        }).getFirst();
        prefs.edit().putBoolean("includeScreenshot", false).commit();
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        JSONObject replay = STORE.reserveSubmission(restarted, input, (thread, options) -> {
            throw new AssertionError("replay recaptured the screen");
        }).getFirst();
        check(replay.getJSONObject("options").getBoolean("includeScreenshot") &&
                replay.getJSONObject("options").getString("invocationScreenshot").equals(input.getString("requestId")),
                "the pending request retains its opt-in and capture after preference changes and restart");
        STORE.finishSubmission(restarted, first, false);
        check(ComposerState.read(restarted, THREAD).getString("text").equals("Use the previous screen"),
                "a rejected screenshot send preserves the draft");
        JSONObject next = STORE.reserveSubmission(restarted, new JSONObject(input.toString())
                .put("requestId", "00000000-0000-4000-8000-000000000007"), (thread, options) -> kotlin.Unit.INSTANCE).getFirst();
        check(!next.getJSONObject("options").getBoolean("includeScreenshot"), "the next send uses the updated screenshot preference");
    }

    private static void invocationCaptureIsReservedOnlyForSendableRequests() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putBoolean("includeScreenshot", false).commit();
        boolean[] grant = {true};
        int[] consulted = {0};
        kotlin.jvm.functions.Function2<String, JSONObject, kotlin.Unit> reserveCapture = (thread, options) -> {
            consulted[0]++;
            if (grant[0]) {
                grant[0] = false;
                options.put("includeScreenshot", true)
                        .put("invocationScreenshot", "00000000-0000-4000-8000-000000000016");
            }
            return kotlin.Unit.INSTANCE;
        };
        String revision = ComposerState.write(prefs, THREAD, "", "What is on screen?").getString("revision");
        JSONObject input = new JSONObject().put("threadId", THREAD).put("action", "sendMessage")
                .put("revision", revision).put("requestId", "00000000-0000-4000-8000-000000000016")
                .put("attachmentIds", new JSONArray());
        JSONObject first = STORE.reserveSubmission(prefs, input, reserveCapture).getFirst();
        check(first.getJSONObject("options").getBoolean("includeScreenshot"), "the first message after a power-key invocation carries the screenshot");
        check(!prefs.getBoolean("includeScreenshot", true), "the one-shot grant never writes the global screenshot preference");
        check(!grant[0] && consulted[0] == 1, "the grant is consumed by the first message");
        check(!STORE.reserveSubmission(prefs, input, reserveCapture).getSecond() && consulted[0] == 1,
                "a replay uses the frozen capture without reserving a new one");
        STORE.finishSubmission(prefs, first, true);
        String next = ComposerState.write(prefs, THREAD, ComposerState.read(prefs, THREAD).getString("revision"), "And now?").getString("revision");
        JSONObject second = STORE.reserveSubmission(prefs, new JSONObject(input.toString()).put("revision", next)
                .put("requestId", "00000000-0000-4000-8000-000000000017"), reserveCapture).getFirst();
        check(consulted[0] == 2, "a fresh request resolves its own capture options");
        check(!second.getJSONObject("options").getBoolean("includeScreenshot"), "the second message falls back to the global preference");
        MemoryPreferences empty = new MemoryPreferences();
        String blank = ComposerState.write(empty, THREAD, "", "").getString("revision");
        boolean[] untouched = {true};
        rejects("INVALID_TEXT", () -> STORE.reserveSubmission(empty, new JSONObject(input.toString()).put("revision", blank)
                .put("requestId", "00000000-0000-4000-8000-000000000018"), (thread, options) -> { untouched[0] = false; return kotlin.Unit.INSTANCE; }));
        check(untouched[0], "an unsendable draft keeps the grant for the real first message");
    }

    private static void handoffPreservesDisplacedDraft() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        ComposerState.write(prefs, THREAD, "", "original");
        String handoff = "00000000-0000-4000-8000-000000000005";
        JSONObject handed = ComposerState.handoff(prefs, THREAD, handoff, "from Home");
        MemoryPreferences restarted = new MemoryPreferences(prefs);
        JSONObject changed = ComposerState.write(restarted, THREAD, handed.getString("revision"), "edited after handoff");
        JSONObject replayed = ComposerState.handoff(restarted, THREAD, handoff, "from Home");
        check(replayed.getString("text").equals("edited after handoff") && replayed.getJSONArray("recovery").length() == 1,
                "replaying a handoff preserves later editing and a single recovery entry");
        String recovery = replayed.getJSONArray("recovery").getJSONObject(0).getString("id");
        rejects("DRAFT_NOT_EMPTY", () -> ComposerState.restore(restarted, THREAD, changed.getString("revision"), recovery));
        String empty = ComposerState.write(restarted, THREAD, changed.getString("revision"), "").getString("revision");
        check(ComposerState.restore(restarted, THREAD, empty, recovery).getString("text").equals("original"),
                "the displaced draft restores after process restart");
    }

    private static void legacyHomeAndPendingDelivery() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("draft-" + THREAD, "Earlier draft with two files").commit();
        JSONArray files = new JSONArray().put(new JSONObject().put("path", "/fixture/first").put("name", "first"))
                .put(new JSONObject().put("path", "/fixture/second").put("name", "second"));
        prefs.edit().putString("composer." + THREAD + ".attachments", files.toString()).commit();
        String legacyKey = "composer." + THREAD + ".delivery";
        JSONObject old = new JSONObject().put("token", "old-delivery").put("status", "sending")
                .put("text", "Unconfirmed request").put("attachments", files);
        prefs.edit().putString(legacyKey, old.toString()).commit();
        prefs.edit().putString("home-pending-id", "legacy-home").putString("home-pending-thread", THREAD)
                .putString("home-pending-text", "Pending Home request").commit();
        check(ComposerState.read(prefs, TARGET).getString("text").isEmpty(), "opening a different thread leaves the handoff pending");
        prefs.failNextCommit = true;
        rejects("DRAFT_SAVE_FAILED", () -> ComposerState.read(prefs, THREAD));
        check(prefs.contains(legacyKey) && prefs.contains("home-pending-id"), "failed persistence keeps both source records");
        JSONObject state = ComposerState.read(new MemoryPreferences(prefs), THREAD);
        JSONArray recovered = state.getJSONArray("recovery");
        check(state.getString("text").equals("Pending Home request") && recovered.length() == 2,
                "upgrade keeps the pending Home input editable and both earlier records recoverable");
        check("delivery-unconfirmed".equals(recovered.getJSONObject(0).getString("status")) &&
                recovered.getJSONObject(0).getJSONArray("attachments").getJSONObject(1).getString("id").equals("second"),
                "an uncertain legacy send retains status and ordered attachments");
        check(state.getJSONArray("pendingReceipts").length() == 0, "legacy recovery never restores a submission to the send queue");
    }

    private static JSONObject plugin(String id, String name, String... tools) throws Exception {
        JSONArray names = new JSONArray();
        for (String tool : tools) names.put(tool);
        return new JSONObject().put("id", id).put("name", name).put("tools", names);
    }

    private static void pluginProtocol() throws Exception {
        String add = new JSONObject().put("threadId", THREAD).put("expectedRevision", "").put("pluginId", "org.example.figma").toString();
        check(AgentGatewayProtocol.request("composer/plugin/add", add).getString("pluginId").equals("org.example.figma"), "plugin add accepts a God Mode id");
        check(AgentGatewayProtocol.request("composer/plugin/remove", add).length() == 3, "plugin remove takes the same fields");
        for (String bad : new String[]{"", "has space", "../x", "a/b", "x".repeat(129)})
            rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/plugin/add", new JSONObject(add).put("pluginId", bad).toString()));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/plugin/add", new JSONObject(add).put("pluginId", 7).toString()));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/plugin/add", new JSONObject(add).put("extra", 1).toString()));
        JSONObject noRevision = new JSONObject(add);
        noRevision.remove("expectedRevision");
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/plugin/remove", noRevision.toString()));
        JSONObject submit = new JSONObject().put("threadId", THREAD).put("requestId", "00000000-0000-4000-8000-000000000010")
                .put("revision", "r").put("action", "sendMessage").put("attachmentIds", new JSONArray());
        check(!AgentGatewayProtocol.request("composer/submit", submit.toString()).has("pluginIds"), "pluginIds stays optional");
        check(AgentGatewayProtocol.request("composer/submit", new JSONObject(submit.toString())
                .put("pluginIds", new JSONArray().put("a.b").put("c:d")).toString()).getJSONArray("pluginIds").length() == 2, "submit accepts plugin ids");
        rejects("PLUGIN_LIMIT", () -> AgentGatewayProtocol.request("composer/submit", new JSONObject(submit.toString())
                .put("pluginIds", new JSONArray().put("a").put("b").put("c").put("d").put("e")).toString()));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/submit", new JSONObject(submit.toString())
                .put("pluginIds", new JSONArray().put("a").put("a")).toString()));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/submit", new JSONObject(submit.toString())
                .put("pluginIds", "a").toString()));
        rejects("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("composer/submit", new JSONObject(submit.toString())
                .put("pluginIds", new JSONArray().put("bad id")).toString()));
        check(AgentGatewayProtocol.request("composer/submit", new JSONObject(submit.toString())
                .put("invocationId", "00000000-0000-4000-8000-000000000011").toString()).has("invocationId"), "submit names its invocation");
        rejects("INVALID_invocationId", () -> AgentGatewayProtocol.request("composer/submit", new JSONObject(submit.toString())
                .put("invocationId", "not-a-uuid").toString()));
        rejects("INVALID_invocationId", () -> AgentGatewayProtocol.request("composer/submit", new JSONObject(submit.toString())
                .put("invocationId", 7).toString()));
    }

    private static void pluginLookup() throws Exception {
        JSONObject record = new JSONObject().put("id", "org.example.figma").put("enabled", true).put("state", "started")
                .put("manifest", new JSONObject().put("name", "Fig\nma"))
                .put("registrations", new JSONObject().put("tools", new JSONArray().put(new JSONObject().put("name", "draw"))
                        .put(new JSONObject().put("id", "export")).put(new JSONObject().put("name", "draw")).put(new JSONObject())));
        JSONObject item = ComposerPlugins.resolve(new JSONObject().put("ok", true).put("result", record), "org.example.figma");
        check(item.getString("name").equals("Fig ma") && item.getJSONArray("tools").toString().equals("[\"draw\",\"export\"]"),
                "lookup keeps the manifest name and registered tool names");
        record.remove("registrations");
        record.getJSONObject("manifest").remove("name");
        item = ComposerPlugins.resolve(new JSONObject().put("ok", true).put("result", record), "org.example.figma");
        check(item.getString("name").equals("org.example.figma") && item.getJSONArray("tools").length() == 0, "a stopped plugin falls back to its id and no tools");
        rejects("PLUGIN_UNAVAILABLE", () -> ComposerPlugins.resolve(new JSONObject().put("ok", true)
                .put("result", new JSONObject(record.toString()).put("enabled", false)), "org.example.figma"));
        rejects("PLUGIN_UNAVAILABLE", () -> ComposerPlugins.resolve(new JSONObject().put("ok", true).put("result", record), "org.example.other"));
        rejects("PLUGIN_UNAVAILABLE", () -> ComposerPlugins.resolve(new JSONObject().put("ok", false)
                .put("error", new JSONObject().put("code", "UNAVAILABLE")), "org.example.figma"));
        rejects("PLUGIN_UNAVAILABLE", () -> ComposerPlugins.resolve(new JSONObject().put("ok", false)
                .put("error", new JSONObject().put("code", "NOT_FOUND")), "org.example.figma"));
    }

    private static void pluginDraft() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        check(ComposerState.read(prefs, THREAD).getJSONArray("plugins").length() == 0, "a stored draft without plugins reports none");
        String revision = ComposerState.write(prefs, THREAD, "", "hello").getString("revision");
        conflict(revision, () -> ComposerState.addPlugin(prefs, THREAD, "stale", plugin("a.one", "One")));
        JSONObject added = ComposerState.addPlugin(prefs, THREAD, revision, plugin("a.one", "One", "t1"));
        check(!added.getString("revision").equals(revision) && added.getString("text").equals("hello"), "adding a plugin bumps the revision and keeps text");
        check(added.getJSONArray("plugins").toString().equals(new JSONArray().put(new JSONObject().put("id", "a.one").put("name", "One")).toString()),
                "the snapshot lists the plugin's id and name only");
        JSONObject again = ComposerState.addPlugin(prefs, THREAD, added.getString("revision"), plugin("a.one", "One", "t1"));
        check(again.getString("revision").equals(added.getString("revision")) && again.getJSONArray("plugins").length() == 1, "adding it again is idempotent");
        String current = again.getString("revision");
        for (String id : new String[]{"a.two", "a.three", "a.four"})
            current = ComposerState.addPlugin(prefs, THREAD, current, plugin(id, id)).getString("revision");
        String full = current;
        rejects("PLUGIN_LIMIT", () -> ComposerState.addPlugin(prefs, THREAD, full, plugin("a.five", "Five")));
        JSONArray order = ComposerState.read(prefs, THREAD).getJSONArray("plugins");
        check(order.length() == 4 && order.getJSONObject(0).getString("id").equals("a.one") && order.getJSONObject(3).getString("id").equals("a.four"),
                "plugins keep draft order");
        rejects("PLUGIN_UNAVAILABLE", () -> ComposerState.removePlugin(prefs, THREAD, full, "a.five"));
        conflict(full, () -> ComposerState.removePlugin(prefs, THREAD, "stale", "a.two"));
        JSONObject removed = ComposerState.removePlugin(prefs, THREAD, full, "a.two");
        check(!removed.getString("revision").equals(full) && removed.getJSONArray("plugins").length() == 3
                && !removed.getJSONArray("plugins").toString().contains("a.two"), "removing a plugin bumps the revision");
        prefs.failNextCommit = true;
        rejects("DRAFT_SAVE_FAILED", () -> ComposerState.removePlugin(prefs, THREAD, removed.getString("revision"), "a.one"));
        check(ComposerState.read(prefs, THREAD).getJSONArray("plugins").length() == 3, "a failed save keeps the plugins");
    }

    private static void pluginSubmission() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        String revision = ComposerState.addPlugin(prefs, THREAD, "", plugin("a.one", "One", "t1", "t2")).getString("revision");
        revision = ComposerState.addPlugin(prefs, THREAD, revision, plugin("a.two", "Two")).getString("revision");
        JSONObject input = new JSONObject().put("threadId", THREAD).put("action", "sendMessage").put("revision", revision)
                .put("requestId", "00000000-0000-4000-8000-000000000011").put("attachmentIds", new JSONArray())
                .put("pluginIds", new JSONArray().put("a.one"));
        rejects("INVALID_TEXT", () -> STORE.reserveSubmission(prefs, input, (thread, options) -> kotlin.Unit.INSTANCE));
        revision = ComposerState.write(prefs, THREAD, revision, "Draw a logo").getString("revision");
        input.put("revision", revision);
        JSONObject unknown = new JSONObject(input.toString()).put("pluginIds", new JSONArray().put("a.nine"));
        rejects("PLUGIN_UNAVAILABLE", () -> STORE.reserveSubmission(prefs, unknown, (thread, options) -> kotlin.Unit.INSTANCE));
        JSONObject stale = new JSONObject(input.toString()).put("revision", "stale");
        conflict(revision, () -> STORE.reserveSubmission(prefs, stale, (thread, options) -> kotlin.Unit.INSTANCE));
        JSONObject request = STORE.reserveSubmission(prefs, input, (thread, options) -> kotlin.Unit.INSTANCE).getFirst();
        check(request.getJSONObject("options").getJSONArray("plugins").toString().equals(new JSONArray().put(plugin("a.one", "One", "t1", "t2")).toString()),
                "the reserved request carries the selected plugin with its tools");
        JSONObject pending = ComposerState.read(prefs, THREAD).getJSONArray("pendingReceipts").getJSONObject(0);
        check(pending.getJSONArray("pluginIds").toString().equals("[\"a.one\"]"), "the pending receipt reports its plugin ids");
        JSONObject changed = new JSONObject(input.toString()).put("pluginIds", new JSONArray().put("a.two"));
        rejects("REQUEST_ID_REUSED", () -> STORE.reserveSubmission(prefs, changed, (thread, options) -> kotlin.Unit.INSTANCE));
        JSONArray content = org.agentos.lab.ComposerInput.text(request.getString("text"));
        org.agentos.lab.ComposerInput.appendPlugins(content, request.getJSONObject("options").getJSONArray("plugins"));
        org.agentos.lab.ComposerInput.appendPlugins(content, new JSONArray().put(plugin("a.two", "Two")));
        check(content.length() == 3 && content.getJSONObject(0).getString("text").equals("Draw a logo")
                && content.getJSONObject(1).getString("text").equals("The user attached the plugin \"One\" (id: a.one) to this message. Prefer this plugin's tools for this request: t1, t2.")
                && content.getJSONObject(2).getString("text").equals("The user attached the plugin \"Two\" (id: a.two) to this message."),
                "the turn input states each plugin after the user's text");
        STORE.finishSubmission(prefs, request, true);
        JSONObject after = ComposerState.read(prefs, THREAD);
        check(after.getString("text").isEmpty() && after.getJSONArray("plugins").length() == 1
                && after.getJSONArray("plugins").getJSONObject(0).getString("id").equals("a.two"), "an accepted send removes only the sent plugins");

        String legacy = ComposerState.write(prefs, TARGET, "", "no plugins field").getString("revision");
        JSONObject old = new JSONObject().put("threadId", TARGET).put("action", "sendMessage").put("revision", legacy)
                .put("requestId", "00000000-0000-4000-8000-000000000012").put("attachmentIds", new JSONArray());
        JSONObject oldRequest = STORE.reserveSubmission(prefs, old, (thread, options) -> kotlin.Unit.INSTANCE).getFirst();
        check(oldRequest.getJSONObject("options").getJSONArray("plugins").length() == 0, "a submit without pluginIds sends no plugins");
        STORE.finishSubmission(prefs, oldRequest, true);
        check(ComposerState.read(prefs, TARGET).getString("text").isEmpty(), "a submit without pluginIds still settles");
    }

    private static void pluginLifecycle() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        String revision = ComposerState.write(prefs, THREAD, "", "Use the plugin").getString("revision");
        revision = ComposerState.addPlugin(prefs, THREAD, revision, plugin("a.one", "One", "t1")).getString("revision");
        JSONObject input = new JSONObject().put("threadId", THREAD).put("action", "sendMessage").put("revision", revision)
                .put("requestId", "00000000-0000-4000-8000-000000000013").put("attachmentIds", new JSONArray())
                .put("pluginIds", new JSONArray().put("a.one"));
        STORE.reserveSubmission(prefs, input, (thread, options) -> kotlin.Unit.INSTANCE);
        JSONObject released = STORE.releaseSubmission(prefs, THREAD, input.getString("requestId"));
        check(released.getJSONArray("recovery").length() == 0 && released.getJSONArray("plugins").length() == 1,
                "releasing an unchanged draft keeps its plugin chip without a recovery copy");
        revision = ComposerState.write(prefs, THREAD, released.getString("revision"), "edited").getString("revision");
        JSONObject second = new JSONObject(input.toString()).put("revision", revision).put("requestId", "00000000-0000-4000-8000-000000000014");
        STORE.reserveSubmission(prefs, second, (thread, options) -> kotlin.Unit.INSTANCE);
        revision = ComposerState.removePlugin(prefs, THREAD, revision, "a.one").getString("revision");
        revision = ComposerState.write(prefs, THREAD, revision, "").getString("revision");
        JSONObject stashed = STORE.releaseSubmission(prefs, THREAD, second.getString("requestId"));
        JSONObject entry = stashed.getJSONArray("recovery").getJSONObject(0);
        check(entry.getJSONArray("plugins").getJSONObject(0).getString("name").equals("One"), "a released send keeps its plugin for recovery");
        JSONObject restored = ComposerState.restore(prefs, THREAD, revision, entry.getString("id"));
        check(restored.getString("text").equals("edited") && restored.getJSONArray("plugins").getJSONObject(0).getString("id").equals("a.one"),
                "restoring the saved draft brings its plugin chip back");

        JSONObject handed = ComposerState.handoff(prefs, THREAD, "00000000-0000-4000-8000-000000000015", "from Home");
        check(handed.getJSONArray("plugins").length() == 0 && handed.getJSONArray("recovery").getJSONObject(0).getJSONArray("plugins").length() == 1,
                "a handoff clears plugins and keeps the displaced draft's plugin recoverable");
        String empty = ComposerState.write(prefs, THREAD, handed.getString("revision"), "").getString("revision");
        String saved = handed.getJSONArray("recovery").getJSONObject(0).getString("id");
        JSONObject back = ComposerState.restore(prefs, THREAD, empty, saved);
        String chipOnly = ComposerState.write(prefs, THREAD, back.getString("revision"), "").getString("revision");
        rejects("DRAFT_NOT_EMPTY", () -> STORE.restoreTask(prefs, THREAD, chipOnly, new JSONObject().put("prompt", "x")));
        JSONObject task = STORE.restoreTask(new MemoryPreferences(), TARGET, "",
                new JSONObject().put("prompt", "from task").put("plugins", new JSONArray().put(plugin("a.one", "One"))));
        check(task.getJSONArray("plugins").length() == 1, "restoring a failed task restores its plugins");

        MemoryPreferences slot = new MemoryPreferences();
        String source = ComposerState.addPlugin(slot, "", "", plugin("a.one", "One")).getString("revision");
        JSONObject moved = STORE.move(slot, source, TARGET);
        check(moved.getJSONArray("plugins").length() == 1 && ComposerState.read(slot, "").getJSONArray("plugins").length() == 0,
                "moving the new-conversation draft carries its plugins");
        String occupied = ComposerState.addPlugin(slot, "", ComposerState.read(slot, "").getString("revision"), plugin("a.two", "Two")).getString("revision");
        rejects("DRAFT_TARGET_OCCUPIED", () -> STORE.move(slot, occupied, TARGET));
    }

    private interface Checked { void run() throws Exception; }
    private static void rejects(String code, Checked request) throws Exception {
        try { request.run(); }
        catch (IOException expected) { check(code.equals(expected.getMessage()), "expected " + code); return; }
        throw new AssertionError("expected " + code);
    }
    private static void conflict(String actual, Checked request) throws Exception {
        try { request.run(); }
        catch (ComposerState.RevisionConflict expected) {
            check(expected.getMessage().equals("DRAFT_REVISION_CONFLICT") && expected.getRevision().equals(actual), "conflict returns the current revision");
            return;
        }
        throw new AssertionError("expected a revision conflict");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
