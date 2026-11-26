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
                "dismiss", "open", "action", "tap", "hide", "someFutureOperation", null};
        String[] global = {"next"};
        String[] admin = {"setEnabled", "setAllowed", "setProtocol"};
        // Agents, ADB, SystemUI and platform-signed callers keep direct access to everything.
        for (String method : control) expect(null, method, true, false, false);
        for (String method : global) expect(null, method, true, false, false);
        for (String method : admin) expect(null, method, true, false, false);
        expect(null, "canShowIsland", true, false, false);
        // Ungranted third-party apps: capability probe and their own package only.
        expect(null, "capabilities", false, false, false);
        expect(null, "canShowIsland", false, false, true);
        expect(denied, "canShowIsland", false, false, false);
        expect(denied, "canShowIsland", false, true, false);
        for (String method : control) expect(denied, method, false, false, false);
        for (String method : global) expect(denied, method, false, false, false);
        // A taku.control grant opens read/operate, never settings or the publisher allow-list,
        // nor the operations that act on whichever activity the island presents.
        for (String method : control) expect(null, method, false, true, false);
        for (String method : global) expect(denied, method, false, true, true);
        for (String method : admin) expect(denied, method, false, true, true);
        // The App Permission Center is consulted only when its answer matters.
        for (String method : control) check(IslandAccessPolicy.needsGrant(method, false), "grant needed: " + method);
        for (String method : control) check(!IslandAccessPolicy.needsGrant(method, true), "trusted skips grant: " + method);
        for (String method : admin) check(!IslandAccessPolicy.needsGrant(method, false), "admin never granted: " + method);
        for (String method : global) check(!IslandAccessPolicy.needsGrant(method, false), "global never granted: " + method);
        check(!IslandAccessPolicy.needsGrant("canShowIsland", false), "public skips grant");
        int self = 10123;
        for (int uid : new int[]{0, 1000, 2000, self}) check(IslandAccessPolicy.trustedUid(uid, self), "trusted uid " + uid);
        for (int uid : new int[]{1001, 1002, 9999, 10000, 10124, 1010123}) check(!IslandAccessPolicy.trustedUid(uid, self), "untrusted uid " + uid);

        // Two granted third-party apps, each with one activity; and a trusted caller.
        String missing = IslandAccessPolicy.NOT_FOUND;
        int appA = 10101, appB = 10102, systemUi = 10123;
        int scopeA = IslandAccessPolicy.scope(false, appA), scopeB = IslandAccessPolicy.scope(false, appB);
        int everything = IslandAccessPolicy.scope(true, 2000);
        check(everything == IslandAccessPolicy.ALL_ACTIVITIES, "trusted callers are unscoped");
        check(scopeA == appA && scopeB == appB, "third-party scope is the calling uid");
        // Reads (state, list): each app sees its own activity only.
        check(IslandAccessPolicy.owns(scopeA, appA) && IslandAccessPolicy.owns(scopeB, appB), "own activity listed");
        check(!IslandAccessPolicy.owns(scopeA, appB) && !IslandAccessPolicy.owns(scopeB, appA), "other app's activity hidden");
        check(!IslandAccessPolicy.owns(scopeA, systemUi) && !IslandAccessPolicy.owns(scopeA, 1000), "system activities hidden");
        // The same package in another Android user is a different UID.
        check(!IslandAccessPolicy.owns(scopeA, 1000000 + appA), "other user's activity hidden");
        check(IslandAccessPolicy.owns(everything, appA) && IslandAccessPolicy.owns(everything, appB)
                && IslandAccessPolicy.owns(everything, systemUi), "trusted callers see every activity");
        // Targeted operations, including action (PendingIntent and RemoteInput reply).
        String[] targeted = {"get", "select", "expand", "tap", "dismiss", "hide", "open", "action"};
        for (String method : targeted) {
            check(IslandAccessPolicy.targeted(method), "targeted: " + method);
            check(IslandAccessPolicy.tier(method) == IslandAccessPolicy.Tier.CONTROL, "granted tier: " + method);
        }
        for (String method : new String[]{"state", "list", "settings", "capabilities", "canShowIsland",
                "collapse", "next", "setEnabled", "someFutureOperation", null})
            check(!IslandAccessPolicy.targeted(method), "not targeted: " + method);
        check(IslandAccessPolicy.target(scopeA, appA) == null, "A operates and replies on its own activity");
        check(IslandAccessPolicy.target(scopeB, appB) == null, "B operates and replies on its own activity");
        check(missing.equals(IslandAccessPolicy.target(scopeA, appB)), "A cannot operate or reply on B's activity");
        check(missing.equals(IslandAccessPolicy.target(scopeB, appA)), "B cannot operate or reply on A's activity");
        check(missing.equals(IslandAccessPolicy.target(scopeA, systemUi)), "A cannot operate a system activity");
        // A foreign key answers exactly like one that does not exist.
        check(missing.equals(IslandAccessPolicy.target(scopeA, null)), "missing key for a scoped caller");
        check(missing.equals(IslandAccessPolicy.target(everything, null)), "missing key for a trusted caller");
        check(IslandAccessPolicy.target(everything, appA) == null && IslandAccessPolicy.target(everything, appB) == null,
                "trusted callers operate and reply across apps");

        // collapse names no activity: a granted app folds the open card only when it is its own.
        check(IslandAccessPolicy.collapse(scopeA, appA) == null, "A collapses its own expanded card");
        check(IslandAccessPolicy.collapse(scopeB, appB) == null, "B collapses its own expanded card");
        check(denied.equals(IslandAccessPolicy.collapse(scopeA, appB)), "A cannot collapse B's card");
        check(denied.equals(IslandAccessPolicy.collapse(scopeB, appA)), "B cannot collapse A's card");
        check(denied.equals(IslandAccessPolicy.collapse(scopeA, systemUi)), "A cannot collapse a system card");
        check(denied.equals(IslandAccessPolicy.collapse(scopeA, 1000000 + appA)), "A cannot collapse another user's card");
        check(denied.equals(IslandAccessPolicy.collapse(scopeA, null)), "no open card is refused like a foreign one");
        check(IslandAccessPolicy.collapse(everything, appA) == null && IslandAccessPolicy.collapse(everything, systemUi) == null
                && IslandAccessPolicy.collapse(everything, null) == null, "trusted callers collapse any card");
        // Without a grant collapse is refused before ownership is ever looked at.
        expect(denied, "collapse", false, false, false);
        check(IslandAccessPolicy.needsGrant("collapse", false), "collapse needs the grant");
        System.out.println("PASS: " + checks + " island access policy checks");
    }
}
