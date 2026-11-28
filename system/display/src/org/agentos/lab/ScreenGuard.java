package org.agentos.lab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.ToIntFunction;

/**
 * Android-free policy of an independent screen (host-tested): which display flags it is created
 * with, and when a capture must fail instead of handing the Agent a black image (BUG-0034).
 */
final class ScreenGuard {
    private ScreenGuard() {}

    /** Mirrors {@code android.view.Display.STATE_*} (public, stable API values). */
    static final int STATE_UNKNOWN = 0, STATE_OFF = 1, STATE_ON = 2, STATE_DOZE = 3,
            STATE_DOZE_SUSPEND = 4, STATE_VR = 5, STATE_ON_SUSPEND = 6;

    static final String SCREEN_OFF = "SCREEN_OFF", SCREEN_BLACK_FRAME = "SCREEN_BLACK_FRAME";

    /**
     * The screen's own power group (OWN_DISPLAY_GROUP) has its own user-activity timeout, and an
     * Agent's screen gets no touches while the model thinks: after the screen-off timeout the group
     * dozes, a non-default group cannot dream, so the display goes STATE_OFF and stays there, since
     * wake-ups (power key, PowerManager.wakeUp, an App launch) address the default group (BUG-0044).
     * The host therefore holds, for the screen's lifetime, a screen wake lock tied to its display
     * ({@code PowerManager.newWakeLock(flags, tag, displayId)}, hidden): SCREEN_BRIGHT_WAKE_LOCK keeps
     * the group awake and ACQUIRE_CAUSES_WAKEUP wakes it when acquired. Mirrors the public, stable
     * PowerManager constants.
     */
    static final int SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a, ACQUIRE_CAUSES_WAKEUP = 0x10000000;
    static final int SCREEN_WAKE_FLAGS = SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP;

    /**
     * A capture that finds the screen off first wakes it again (re-acquiring the wake lock) and keeps
     * waiting for this long from the request, retrying each tick; only then is it SCREEN_OFF. Well
     * inside the 5 s a capture caller waits.
     */
    static final long WAKE_GRACE_MS = 2_000, REWAKE_INTERVAL_MS = 1_000;

    /** Whether an off-screen capture [ageMs] old should wait for the screen to wake rather than fail. */
    static boolean awaitWake(long ageMs) {
        return ageMs >= 0 && ageMs < WAKE_GRACE_MS;
    }

    /**
     * The wake lock is held only while the screen is in use: a capture, a viewer fetching frames, the
     * host's own input or an Agent action (the broker sends {@code wake} before every open, tap,
     * swipe, touch and typing step) renews it, and this long without any of them releases it, so a screen
     * kept READY overnight lets the phone sleep (code review). Status polls and frames the screen
     * renders by itself (a playing video) do not count. A capture after the release wakes it again
     * (ACQUIRE_CAUSES_WAKEUP, then the WAKE_GRACE_MS wait).
     */
    static final long AWAKE_IDLE_MS = 10 * 60_000;

    /** Whether a wake lock last renewed at [lastUseMs] should be released at [nowMs]. */
    static boolean idleWakeExpired(long nowMs, long lastUseMs) {
        return nowMs - lastUseMs >= AWAKE_IDLE_MS;
    }

    interface StateReader { int read(); }
    interface Sleeper { void sleep(long ms) throws InterruptedException; }

    /**
     * Before an action (open, tap, swipe, touch, typing, a key) reaches the screen: the caller has
     * renewed the wake lock; when the idle release let the screen's group go to sleep, wake it again
     * (re-acquire, at most once per REWAKE_INTERVAL_MS) and wait up to WAKE_GRACE_MS for it to be
     * on. Android drops input on a screen that is off, so the action must not run before then.
     * Returns the state it found on; SCREEN_OFF when it stayed off (or cannot be woken).
     */
    static int awaitOn(StateReader state, boolean canWake, Runnable rewake, java.util.function.LongSupplier clock,
            Sleeper sleeper) throws IOException, InterruptedException {
        long start = clock.getAsLong();
        while (true) {
            int now = state.read();
            if (screenOn(now)) return now;
            if (!canWake || !awaitWake(clock.getAsLong() - start)) {
                throw new IOException(SCREEN_OFF + ": independent screen display state is " + stateName(now)
                        + "; the action was not performed");
            }
            rewake.run();
            sleeper.sleep(50);
        }
    }

    /** Whether to re-acquire the wake lock now, at most once per interval while a capture waits. */
    static boolean rewakeDue(long nowMs, long lastRewakeMs) {
        return lastRewakeMs <= 0 || nowMs - lastRewakeMs >= REWAKE_INTERVAL_MS;
    }

    /** Hidden DisplayManager flags a trusted independent screen cannot work without. */
    static final String[] REQUIRED_FLAGS = {
        "VIRTUAL_DISPLAY_FLAG_TRUSTED", "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED"};
    /**
     * Best-effort flags (0 when the platform lacks them). OWN_DISPLAY_GROUP gives the screen its own
     * display/power group, so the power key putting the default group to sleep does not also turn this
     * screen STATE_OFF and leave the Agent capturing black frames.
     */
    static final String[] OPTIONAL_FLAGS = {
        "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
        "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP"};

    /** {@code base} plus every required (missing: fails) and optional (missing: skipped) flag. */
    static int virtualDisplayFlags(int base, ToIntFunction<String> flag) throws IOException {
        int flags = base;
        for (String name : REQUIRED_FLAGS) {
            int value = flag.applyAsInt(name);
            if (value == 0) throw new IOException("Required display flag unavailable: " + name);
            flags |= value;
        }
        for (String name : OPTIONAL_FLAGS) flags |= flag.applyAsInt(name);
        return flags;
    }

    /**
     * The lock screen marks Keyguard as showing on every display, and a display where it shows (not
     * occluded by the top App) gets a Keyguard sleep token: its Apps are stopped and it renders no new
     * frame, whatever its power state. So after the power key and a secure lock the Agent's screen
     * stopped working (BUG-0034). VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED exempts the display; the
     * platform honours it only together with its own display group, so it is added only then. It
     * needs the system UID or ADD_ALWAYS_UNLOCKED_DISPLAY, and createVirtualDisplay throws when that
     * is missing: the host then creates the screen without it ({@code alwaysUnlocked:false}).
     */
    static final String ALWAYS_UNLOCKED_FLAG = "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED";

    static int alwaysUnlockedFlags(int flags, ToIntFunction<String> flag) {
        int ownGroup = flag.applyAsInt("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP");
        int unlocked = flag.applyAsInt(ALWAYS_UNLOCKED_FLAG);
        return ownGroup != 0 && unlocked != 0 && (flags & ownGroup) == ownGroup ? flags | unlocked : flags;
    }

    /** Off, dozing or suspended displays render nothing current. UNKNOWN is not treated as off. */
    static boolean screenOn(int state) {
        return state != STATE_OFF && state != STATE_DOZE && state != STATE_DOZE_SUSPEND && state != STATE_ON_SUSPEND;
    }

    static String stateName(int state) {
        switch (state) {
            case STATE_OFF: return "OFF";
            case STATE_ON: return "ON";
            case STATE_DOZE: return "DOZE";
            case STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case STATE_VR: return "VR";
            case STATE_ON_SUSPEND: return "ON_SUSPEND";
            default: return "UNKNOWN";
        }
    }

    static void requireScreenOn(int state) throws IOException {
        if (!screenOn(state))
            throw new IOException(SCREEN_OFF + ": independent screen display state is " + stateName(state)
                    + "; no screenshot was taken");
    }

    static void requireNotBlack(boolean black) throws IOException {
        if (black)
            throw new IOException(SCREEN_BLACK_FRAME + ": every pixel of the latest rendered frame is pure black (RGB 0)");
    }

    /**
     * True only when every pixel's R, G and B bytes are exactly 0 (alpha ignored) in an RGBA_8888
     * plane. Stops at the first non-zero channel, so real content costs almost nothing; one lit
     * pixel anywhere (a dark-mode App's text, an icon, #010101 grey) means "not black". Absolute
     * reads from the current position, which is left for the caller's own copy.
     */
    static boolean isBlackRgba(ByteBuffer buffer, int width, int height, int rowStride, int pixelStride) {
        if (width <= 0 || height <= 0 || pixelStride < 3 || rowStride < width * pixelStride) return false;
        long needed = (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride + 3;
        int base = buffer.position();
        if (buffer.limit() - base < needed) return false;
        for (int y = 0; y < height; y++) {
            int row = base + y * rowStride;
            for (int x = 0; x < width; x++) {
                int at = row + x * pixelStride;
                if (buffer.get(at) != 0 || buffer.get(at + 1) != 0 || buffer.get(at + 2) != 0) return false;
            }
        }
        return true;
    }
}
