package android.content;

/** Only the preferences seam is exercised; Android UI is not emulated. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public final String getString(int id, Object... values) { throw new AssertionError("Resources require Android"); }
}
