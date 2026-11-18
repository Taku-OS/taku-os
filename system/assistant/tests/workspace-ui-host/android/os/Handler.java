package android.os;

/** Polls are driven explicitly by the host test, not by a fake Android clock. */
public final class Handler {
    public Handler(Looper looper) {}
    public boolean postDelayed(Runnable callback, long delay) { return true; }
    public void removeCallbacks(Runnable callback) {}
}
