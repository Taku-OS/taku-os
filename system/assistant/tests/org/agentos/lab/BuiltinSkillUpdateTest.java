package org.agentos.lab;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

/** Real file replacement, local-edit protection and recovery across consecutive APK versions. */
public final class BuiltinSkillUpdateTest {
    private static final String V1 = "---\nname: taku-open-access\ndescription: Device access.\n---\nVersion one.\n";
    private static final String V2 = V1 + "Workspace configuration.\n";
    private static final String V3 = V2 + "Workspace release.\n";
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static final class Crash extends Error {}
    private static final class Rig implements AutoCloseable {
        final File root = Files.createTempDirectory("builtin-skill-test-").toRealPath().toFile();
        final File body = new File(root, "skill/SKILL.md");
        final File receipt = new File(root, "records/taku-open-access.json");
        Rig() throws Exception { body.getParentFile().mkdirs(); receipt.getParentFile().mkdirs(); }
        void install() throws Exception { TaskBroker.write(body, V1); sync(V1); }
        void sync(String version) throws Exception { BuiltinSkillUpdate.ensure(body, receipt, version); }
        JSONObject record() throws Exception { return new JSONObject(TaskBroker.read(receipt)); }
        void bodyIs(String expected, String message) throws Exception { check(TaskBroker.read(body).equals(expected), message); }
        void retained(String reason) throws Exception {
            check(record().getJSONObject("builtinUpdate").getString("reason").equals(reason), "retained: " + reason);
        }
        public void close() throws Exception { SkillUninstall.deleteTree(root.toPath()); }
    }
    public static void main(String[] args) throws Exception {
        try (Rig rig = new Rig()) {
            rig.install();
            check(rig.record().getString("bodySha256").equals(HarnessStore.hash(V1)), "bootstrap verifies exact official bytes");
            File other = new File(rig.body.getParentFile(), "user-notes.txt"); TaskBroker.write(other, "my notes");
            rig.sync(V2); rig.bodyIs(V2, "first upgrade");
            rig.sync(V3); rig.bodyIs(V3, "second upgrade");
            check(TaskBroker.read(other).equals("my notes"), "other skill files untouched");
            long modified = rig.receipt.lastModified();
            rig.sync(V3); check(rig.receipt.lastModified() == modified, "same version makes no receipt writes");
            check(!rig.record().has("pendingBuiltinUpdate"), "complete update clears pending record");
        }
        try (Rig rig = new Rig()) {
            rig.install(); TaskBroker.write(rig.body, V1 + "My local note.\n");
            rig.sync(V2); rig.retained("LOCAL_MODIFIED");
            rig.sync(V3); rig.bodyIs(V1 + "My local note.\n", "local edits survive consecutive upgrades");
            check(rig.record().getString("bodySha256").equals(HarnessStore.hash(V1)), "retention never blesses local text as official");
            check(rig.record().getJSONObject("builtinUpdate").getString("availableSha256").equals(HarnessStore.hash(V3)), "notice identifies current bundled version");
            long modified = rig.receipt.lastModified(); rig.sync(V3);
            check(rig.receipt.lastModified() == modified, "persistent notice does not append or rewrite on every scan");
            TaskBroker.write(rig.body, V1); rig.sync(V3); rig.bodyIs(V3, "restoring verified baseline permits update");
            check(!rig.record().has("builtinUpdate"), "resolved notice clears");
        }
        try (Rig rig = new Rig()) {
            TaskBroker.write(rig.body, V1); rig.sync(V2); rig.retained("UNVERIFIED_BASELINE");
            rig.bodyIs(V1, "missing receipt is not assumed pristine");
            check(!rig.record().has("bodySha256"), "unknown baseline has no invented official digest");
            TaskBroker.write(rig.receipt, "{broken"); rig.sync(V2);
            rig.retained("UNVERIFIED_BASELINE"); rig.bodyIs(V1, "damaged receipt preserves body");
        }
        for (String field : new String[]{"repo", "path", "name", "bodySha256"}) {
            try (Rig rig = new Rig()) {
                rig.install(); JSONObject record = rig.record().put(field, "untrusted");
                TaskBroker.write(rig.receipt, record.toString()); rig.sync(V2);
                rig.retained("UNVERIFIED_BASELINE"); rig.bodyIs(V1, "untrusted " + field + " preserves body");
            }
        }
        // Three writes: staged body, pending receipt, committed receipt. Interrupt both sides of each.
        for (int boundary = 0; boundary < 6; boundary++) {
            try (Rig rig = new Rig()) {
                rig.install(); final int stop = boundary; int[] writes = {0};
                try {
                    BuiltinSkillUpdate.ensure(rig.body, rig.receipt, V2, (file, text) -> {
                        int at = 2 * writes[0]++;
                        if (at == stop) throw new Crash();
                        TaskBroker.write(file, text);
                        if (at + 1 == stop) throw new Crash();
                    });
                    throw new AssertionError("fault was not injected");
                } catch (Crash expected) {}
                String interrupted = TaskBroker.read(rig.body);
                check(interrupted.equals(V1) || interrupted.equals(V2), "body is complete after interruption " + boundary);
                rig.sync(V2); rig.bodyIs(V2, "recovery at boundary " + boundary);
                rig.sync(V3); rig.bodyIs(V3, "next version after recovery " + boundary);
                check(!rig.record().has("pendingBuiltinUpdate"), "recovery commits " + boundary);
            }
        }
        for (boolean bodyAlreadyMoved : new boolean[]{false, true}) {
            try (Rig rig = new Rig()) {
                rig.install();
                JSONObject pending = new JSONObject().put("phase", "prepared")
                        .put("fromSha256", HarnessStore.hash(V1)).put("toSha256", HarnessStore.hash(V2));
                TaskBroker.write(rig.receipt, rig.record().put("pendingBuiltinUpdate", pending).toString());
                if (bodyAlreadyMoved) TaskBroker.write(rig.body, V2);
                rig.sync(V3); rig.bodyIs(V3, "new APK during interrupted update, moved=" + bodyAlreadyMoved);
            }
        }
        try (Rig rig = new Rig()) {
            rig.install(); int[] writes = {0};
            BuiltinSkillUpdate.ensure(rig.body, rig.receipt, V2, (file, text) -> {
                TaskBroker.write(file, text);
                if (++writes[0] == 2) TaskBroker.write(rig.body, V1 + "Concurrent edit.\n");
            });
            rig.bodyIs(V1 + "Concurrent edit.\n", "edit made during staging retained"); rig.retained("LOCAL_MODIFIED");
        }
        try (Rig rig = new Rig()) {
            rig.install();
            TaskBroker.write(rig.receipt, rig.record().put("pendingBuiltinUpdate", new JSONObject()
                    .put("phase", "prepared").put("fromSha256", HarnessStore.hash(V1))
                    .put("toSha256", HarnessStore.hash(V2))).toString());
            TaskBroker.write(rig.body, V1 + "Edit after crash.\n");
            rig.sync(V2); rig.bodyIs(V1 + "Edit after crash.\n", "recovery preserves post-crash edit");
            rig.retained("LOCAL_MODIFIED");
        }
        try (Rig rig = new Rig()) {
            rig.install(); TaskBroker.write(rig.receipt, rig.record().put("pendingBuiltinUpdate", new JSONObject()).toString());
            rig.sync(V2); rig.retained("INVALID_UPDATE_RECORD"); rig.bodyIs(V1, "malformed transaction does not replace body");
        }
        try (Rig rig = new Rig()) {
            rig.install(); check(rig.body.delete(), "remove fixture body"); rig.sync(V2);
            rig.retained("MISSING_FILE"); check(!rig.body.exists(), "existing broken directory not silently reinstalled");
        }
        try (Rig rig = new Rig()) {
            rig.install();
            try {
                BuiltinSkillUpdate.ensure(rig.body, rig.receipt, V2, (file, text) -> TaskBroker.write(file, "corrupt stage"));
                throw new AssertionError("damaged stage was activated");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().equals("BUILTIN_SKILL_STAGE_HASH_CHANGED"), "staged digest is verified");
            }
            rig.bodyIs(V1, "damaged stage preserves active document");
        }
        try (Rig rig = new Rig()) {
            rig.install(); File outside = new File(rig.root, "outside.md"); TaskBroker.write(outside, "outside");
            Files.createSymbolicLink(new File(rig.body.getPath() + ".builtin-next").toPath(), outside.toPath());
            rig.sync(V2); rig.retained("UNSAFE_SKILL_PATH");
            rig.bodyIs(V1, "staged symlink preserves active document");
            check(TaskBroker.read(outside).equals("outside"), "staged symlink target untouched");
        }
        try (Rig rig = new Rig()) {
            rig.install(); File outside = new File(rig.root, "outside.md"); TaskBroker.write(outside, "outside");
            check(rig.body.delete(), "remove link fixture"); Files.createSymbolicLink(rig.body.toPath(), outside.toPath());
            rig.sync(V2); rig.retained("UNSAFE_SKILL_PATH"); check(TaskBroker.read(outside).equals("outside"), "symlink target untouched");
        }
        System.out.println("BuiltinSkillUpdateTest: " + checks + " checks passed");
    }
}
