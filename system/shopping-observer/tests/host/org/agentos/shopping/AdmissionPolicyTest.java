// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class AdmissionPolicyTest {
    private static int assertions;
    private static final String TOKEN = "A".repeat(43), REQUEST = "10ac9921-21fa-4c94-9cab-84863e282a24";
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static AdmissionPolicy.Entry entry(String token, long now) {
        return new AdmissionPolicy.Entry(token, REQUEST, "immutable-reference", "com.zhiliaoapp.musically", 7,
            "frozen-room-key", 10422, "sha256-of-installed-target", "upper_body", "Substitute the current top with a blue top.", now, now + 30000);
    }
    public static void main(String[] args) throws Exception {
        AdmissionPolicy policy = new AdmissionPolicy(); policy.issue(entry(TOKEN, 1000), 1000);
        check(policy.redeem(TOKEN, REQUEST, 2000, "sha256-of-installed-target", 1100) == null, "shell cannot redeem native-only handoff");
        check(policy.redeem(TOKEN, REQUEST, 10422, "replacement-signer", 1100) == null, "a replacement target signer cannot redeem");
        check(policy.redeem(TOKEN, "another-request", 10422, "sha256-of-installed-target", 1100) == null, "request binding is mandatory");
        check(policy.redeem("guess", REQUEST, 10422, "sha256-of-installed-target", 1100) == null, "invalid token format fails closed");
        var result = policy.redeem(TOKEN, REQUEST, 10422, "sha256-of-installed-target", 30999);
        check(result != null && result.sourceWindow() == 7 && result.roomKey().equals("frozen-room-key")
            && result.reference().equals("immutable-reference") && result.expiresAt() == 31000, "one successful redeem retains exact immutable bindings and original deadline");
        check(policy.redeem(TOKEN, REQUEST, 10422, "sha256-of-installed-target", 30999) == null, "successful redeem consumes once");
        policy.issue(entry(TOKEN, 40000), 40000);
        check(policy.redeem(TOKEN, REQUEST, 10422, "sha256-of-installed-target", 70000) == null, "exact TTL boundary expires");
        check(policy.redeem(TOKEN, REQUEST, 10422, "sha256-of-installed-target", 41000) == null, "expired ticket never revives on clock rollback");
        policy.issue(entry(TOKEN, 80000), 80000); policy.discard(TOKEN);
        check(policy.redeem(TOKEN, REQUEST, 10422, "sha256-of-installed-target", 80100) == null, "failed native launch revokes admission");
        policy.issue(entry(TOKEN, 90000), 90000);
        boolean[] winners = new boolean[2];
        Thread a = new Thread(() -> winners[0] = policy.redeem(TOKEN, REQUEST, 10422, "sha256-of-installed-target", 90100) != null);
        Thread b = new Thread(() -> winners[1] = policy.redeem(TOKEN, REQUEST, 10422, "sha256-of-installed-target", 90100) != null);
        a.start(); b.start(); a.join(); b.join();
        check(winners[0] != winners[1], "concurrent native redeems have exactly one winner");
        for (int i = 0; i < 5; i++) policy.issue(entry(String.valueOf((char)('A'+i)).repeat(43), 100000), 100000);
        check(policy.redeem(TOKEN, REQUEST, 10422, "sha256-of-installed-target", 100100) == null, "admission cache is bounded");
        policy.clear(); check(policy.redeem("E".repeat(43), REQUEST, 10422, "sha256-of-installed-target", 100100) == null, "clear revokes all remaining admissions");
        System.out.println(assertions + " immutable admission assertions passed");
    }
}
