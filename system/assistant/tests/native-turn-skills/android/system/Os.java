package android.system;

/** Host fixture directories belong to the current user; Android ownership is outside this test. */
public final class Os {
    public static void chmod(String path, int mode) {}
    public static void chown(String path, int uid, int gid) {}
}
