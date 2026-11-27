import com.android.systemui.taku.island.IslandLockScreenPolicy;
import com.android.systemui.taku.island.IslandStore;

public final class IslandLockScreenPolicyTest {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static int exposure(boolean enabled, int tier, boolean assistant, boolean authenticated,
            boolean secret, boolean allowed, boolean redacted) {
        return IslandLockScreenPolicy.exposure(enabled, tier, assistant, authenticated, secret, allowed, redacted);
    }

    public static void main(String[] args) {
        int activity = IslandStore.TIER_ACTIVITY;
        int full = IslandLockScreenPolicy.FULL, glyph = IslandLockScreenPolicy.GLYPH, hidden = IslandLockScreenPolicy.HIDDEN;

        // An ordinary activity is shown in full, locked or not.
        check(exposure(true, activity, false, false, false, true, false) == full, "locked activity is full");
        check(exposure(true, activity, false, true, false, true, false) == full, "authenticated activity is full");

        // The setting removes every activity, system tier included.
        for (int tier : new int[] {IslandStore.TIER_CALL, IslandStore.TIER_ALARM, IslandStore.TIER_RECORDING, activity}) {
            check(exposure(false, tier, false, true, false, true, false) == hidden, "setting off hides tier " + tier);
        }

        // Calls, alarms, recording and casting ignore notification privacy: stock shows them too.
        for (int tier : new int[] {IslandStore.TIER_CALL, IslandStore.TIER_ALARM, IslandStore.TIER_RECORDING}) {
            check(exposure(true, tier, false, false, true, false, true) == full, "system tier " + tier + " is full");
        }

        // Android's own lock-screen privacy applies to everything else, in both states.
        for (boolean authenticated : new boolean[] {false, true}) {
            check(exposure(true, activity, false, authenticated, true, true, false) == hidden, "secret is hidden");
            check(exposure(true, activity, false, authenticated, false, false, false) == hidden, "user hides lock-screen notifications");
            check(exposure(true, activity, false, authenticated, false, true, true) == glyph, "redacted is glyph only");
            check(exposure(true, activity, false, authenticated, true, true, true) == hidden, "secret wins over redacted");
        }

        // Taku's assistant needs authentication.
        check(exposure(true, activity, true, false, false, true, false) == hidden, "assistant hidden while locked");
        check(exposure(true, activity, true, true, false, true, false) == full, "assistant full once authenticated");
        check(exposure(true, activity, true, true, false, true, true) == glyph, "assistant still redacted");

        // Actions while locked.
        check(IslandLockScreenPolicy.actionWhileLocked(activity, true, null), "controller action runs");
        check(IslandLockScreenPolicy.actionWhileLocked(IslandStore.TIER_CALL, false, null), "call action runs");
        check(IslandLockScreenPolicy.actionWhileLocked(IslandStore.TIER_ALARM, false, null), "alarm action runs");
        check(IslandLockScreenPolicy.actionWhileLocked(IslandStore.TIER_RECORDING, false, null), "recording action runs");
        check(IslandLockScreenPolicy.actionWhileLocked(activity, false, IslandLockScreenPolicy.MEDIA), "media control runs");
        check(!IslandLockScreenPolicy.actionWhileLocked(activity, false, null), "publisher action needs authentication");
        check(!IslandLockScreenPolicy.actionWhileLocked(activity, false, "delivery"), "unknown category needs authentication");

        System.out.println("PASS: " + checks + " island lock screen policy checks");
    }
}
