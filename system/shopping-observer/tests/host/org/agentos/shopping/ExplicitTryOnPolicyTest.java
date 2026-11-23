// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

public final class ExplicitTryOnPolicyTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        ObservationPolicy p = new ObservationPolicy();
        LivePage room = LivePage.of("com.zhiliaoapp.musically", 5, "presenter", "");
        check(p.explicit(1000) == null, "disabled observer cannot capture through Agent API");
        p.configure(true, false, false, 1000); p.observe(room, 1000);
        var direct = p.explicit(1000);
        check(direct != null && p.isCurrent(direct, 1001), "explicit current LIVE command needs no automatic classification opt-in or product card");
        check(p.accept(direct, 1001) && !p.accept(direct, 1002), "same authenticated operation is accepted once");
        p.observe(LivePage.of(room.packageName(), 6, "presenter", ""), 1100);
        check(!p.isCurrent(direct, 1100), "even explicit command cannot cross a new window");
        var next = p.explicit(1100); check(next != null && p.accept(next, 1101), "new explicit command gets a distinct current-window attempt");
        p.configure(false, false, false, 1200); check(p.explicit(1200) == null, "disabling the same preferences invalidates Agent and UI paths");
        System.out.println(assertions + " explicit try-on policy assertions passed");
    }
}
