package android.util;

/** Host logging adapter; production recovery logic still runs unchanged. */
public final class Log {
    public static int w(String tag, String message, Throwable error) {
        System.err.println(tag + ": " + message + " (" + error.getClass().getSimpleName() + ")");
        return 0;
    }
}
