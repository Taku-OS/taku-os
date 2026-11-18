/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
import com.android.systemui.taku.island.IslandSourcePolicy;
import java.util.Objects;
public final class IslandSourcePolicyTest {
    private static int checks;
    private static void expect(String wanted, String category, boolean style, boolean system, boolean dialer,
            boolean ownCalls, boolean ongoing, boolean fullScreen, boolean countdown) {
        checks++;
        String result = IslandSourcePolicy.category(category, style, system, dialer, ownCalls, ongoing, fullScreen, countdown);
        if (!Objects.equals(wanted, result)) throw new AssertionError("case " + checks + ": " + result);
    }
    public static void main(String[] args) {
        expect(null, "call", false, false, false, false, true, true, false);
        expect(null, "call", true, false, false, false, true, true, false);
        expect("call", "call", true, false, true, false, true, true, false);
        expect("call", "call", true, false, false, true, true, false, false);
        expect("call", "call", true, true, false, false, true, false, false);
        expect("call", "call", false, false, true, false, true, false, false);
        expect(null, "call", false, false, true, false, false, false, false);
        expect("alarm", "alarm", false, false, false, false, false, true, false);
        expect("alarm", "alarm", false, false, false, false, true, false, false);
        expect("timer", "alarm", false, false, false, false, true, false, true);
        expect("alarm", "alarm", false, false, false, false, true, true, true);
        expect(null, "alarm", false, false, false, false, false, false, false);
        expect("timer", "stopwatch", false, false, false, false, true, false, false);
        expect(null, "stopwatch", false, false, false, false, false, false, false);
        expect(null, "assistant", false, false, false, false, true, false, false);
        System.out.println("PASS: " + checks + " system source policy checks");
    }
}
