package org.agentos.lab;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class DisplaySlotTest {
    private static int assertions;
    private static final String GENERATION = "6c7f2455-6e82-4342-a4df-64d3f6f03e5a";
    private interface Checked { void run() throws Exception; }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Checked operation, String message) throws Exception {
        assertions++;
        try { operation.run(); } catch (Exception expected) { return; }
        throw new AssertionError(message);
    }
    private static JSONObject identity(DisplaySlot slot) throws Exception {
        return new JSONObject().put("screenKey", slot.key).put("mirrorSocket", slot.socket)
            .put("name", slot.name).put("stateDirectory", slot.directory.getPath())
            .put("displayId", 42).put("pid", 1234).put("uid", 0).put("generation", GENERATION);
    }
    public static void main(String[] args) throws Exception {
        DisplaySlot legacy = DisplaySlot.hostArguments(new String[0]);
        check(legacy.directory.getPath().equals("/data/local/agentos/virtual-display"), "legacy directory");
        check(legacy.socket.equals("taku-mirror"), "legacy socket");
        check(legacy.name.equals("Taku-Independent-Screen"), "legacy display name");
        check(DisplaySlot.named("default").directory.equals(legacy.directory), "default alias");
        check(legacy.permitsAbsentHostStop("stop", null), "default absent stop remains idempotent");
        check(!legacy.permitsAbsentHostStop("stop", GENERATION), "explicit generation must fail on absent host");
        check(!legacy.permitsAbsentHostStop("capture", null), "absent capture must fail");
        check(!DisplaySlot.named("maps").permitsAbsentHostStop("stop", null), "named absent stop must fail");
        Set<String> directories = new HashSet<>(), sockets = new HashSet<>(), names = new HashSet<>();
        for (String key : new String[]{"default", "xiaohongshu", "maps", "meituan", "a" + "1".repeat(31)}) {
            DisplaySlot slot = DisplaySlot.named(key);
            check(directories.add(slot.directory.getPath()), "unique state directory");
            check(sockets.add(slot.socket), "unique socket");
            check(names.add(slot.name), "unique display name");
            slot.validateIdentity(identity(slot), 1234, 0);
        }
        for (String bad : new String[]{null, "", "..", "../default", "/maps", "Maps", "0maps", "-maps", "map/x", "map x", "a_b", "a\n", "é", "a".repeat(33)})
            rejects(() -> DisplaySlot.named(bad), "illegal key accepted: " + bad);
        rejects(() -> DisplaySlot.hostArguments(new String[]{"maps", "meituan"}), "extra host argument accepted");
        rejects(() -> DisplaySlot.hostArguments(new String[]{""}), "explicit empty key selected default");
        DisplaySlot maps = DisplaySlot.named("maps"), restaurant = DisplaySlot.named("meituan");
        rejects(() -> maps.validateIdentity(identity(restaurant), 1234, 0), "cross-slot host accepted");
        rejects(() -> maps.validateIdentity(identity(maps), 4321, 0), "reused/stale pid accepted");
        rejects(() -> maps.validateIdentity(identity(maps), 1234, 2000), "peer uid mismatch accepted");
        rejects(() -> maps.validateIdentity(identity(maps).put("uid", 10001), 1234, 10001), "untrusted peer uid accepted");
        rejects(() -> maps.validateIdentity(identity(maps).put("displayId", 0), 1234, 0), "main display accepted");
        rejects(() -> maps.validateIdentity(identity(maps).put("stateDirectory", legacy.directory.getPath()), 1234, 0), "default path accepted for named slot");
        rejects(() -> maps.validateIdentity(identity(maps).put("mirrorSocket", restaurant.socket), 1234, 0), "foreign socket accepted");
        rejects(() -> maps.validateIdentity(identity(maps).put("name", restaurant.name), 1234, 0), "foreign display name accepted");
        for (String bad : new String[]{null, "", "42", GENERATION + "\n", "../../default"})
            rejects(() -> DisplaySlot.requireGeneration(bad), "invalid generation accepted");
        DisplaySlot.requireSameGeneration(GENERATION, GENERATION);
        rejects(() -> DisplaySlot.requireSameGeneration(GENERATION, "6c7f2455-6e82-4342-a4df-64d3f6f03e5b"),
            "restarted host with same slot and displayId accepted a stale generation");
        JSONObject missingGeneration = identity(maps); missingGeneration.remove("generation");
        rejects(() -> maps.validateIdentity(missingGeneration, 1234, 0), "missing generation accepted");
        System.out.println("DisplaySlotTest: " + assertions + " assertions passed");
    }
}
