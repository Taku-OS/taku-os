/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
import com.android.systemui.taku.island.IslandAccessPolicy;
import java.util.Objects;
public final class IslandAccessPolicyTest {
    private static int checks;
    private static void expect(String wanted, String method, boolean trusted, boolean granted, boolean own) {
        checks++;
        String result = IslandAccessPolicy.decide(method, trusted, granted, own);
        if (!Objects.equals(wanted, result)) throw new AssertionError(method + " case " + checks + ": " + result);
    }
    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }
    public static void main(String[] args) {
        String denied = IslandAccessPolicy.DENIED;
        String[] control = {"state", "list", "get", "settings", "select", "choose", "expand", "collapse",
                "dismiss", "open", "action", "next", "tap", "hide", "someFutureOperation", null};
        String[] admin = {"setEnabled", "setAllowed", "setProtocol"};
        // Agents, ADB, SystemUI and platform-signed callers keep direct access to everything.
        for (String method : control) expect(null, method, true, false, false);
        for (String method : admin) expect(null, method, true, false, false);
        expect(null, "canShowIsland", true, false, false);
        // Ungranted third-party apps: capability probe and their own package only.
        expect(null, "capabilities", false, false, false);
        expect(null, "canShowIsland", false, false, true);
        expect(denied, "canShowIsland", false, false, false);
        expect(denied, "canShowIsland", false, true, false);
        for (String method : control) expect(denied, method, false, false, false);
        // A taku.control grant opens read/operate, never settings or the publisher allow-list.
        for (String method : control) expect(null, method, false, true, false);
        for (String method : admin) expect(denied, method, false, true, true);
        // The App Permission Center is consulted only when its answer matters.
        for (String method : control) check(IslandAccessPolicy.needsGrant(method, false), "grant needed: " + method);
        for (String method : control) check(!IslandAccessPolicy.needsGrant(method, true), "trusted skips grant: " + method);
        for (String method : admin) check(!IslandAccessPolicy.needsGrant(method, false), "admin never granted: " + method);
        check(!IslandAccessPolicy.needsGrant("canShowIsland", false), "public skips grant");
        int self = 10123;
        for (int uid : new int[]{0, 1000, 2000, self}) check(IslandAccessPolicy.trustedUid(uid, self), "trusted uid " + uid);
        for (int uid : new int[]{1001, 1002, 9999, 10000, 10124, 1010123}) check(!IslandAccessPolicy.trustedUid(uid, self), "untrusted uid " + uid);
        System.out.println("PASS: " + checks + " island access policy checks");
    }
}
