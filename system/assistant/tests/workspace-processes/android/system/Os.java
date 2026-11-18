package android.system;
public final class Os {
    public static void kill(int pid, int signal) throws ErrnoException {
        throw new AssertionError("Read-only proof must never signal a process");
    }
}
