package com.android.server.wm;

import static android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;
import static android.view.Display.DEFAULT_DISPLAY;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;

import android.content.ComponentName;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.android.server.inputmethod.InputMethodManagerInternal;

import org.agentos.workspace.WorkspaceInputConnection;
import org.agentos.workspace.WorkspaceInputConnection.Gesture;
import org.agentos.workspace.WorkspaceInputConnection.Lease;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Chooses the input display for the foreground workspace mirror. */
final class WorkspaceInputController extends Binder {
    private static final String TAG = "TakuWorkspaceInput";
    private static final ComponentName MIRROR = new ComponentName(
            "org.agentos.assistant", "org.agentos.assistant.MirrorActivity");
    private static final String DISPLAY_PREFIX = "Taku-Independent-Screen-";
    private static final int RECHECK_MS = 1000;
    private static final int QUERY_TIMEOUT_MS = 1000;

    private final WindowManagerService mWm;
    // All routing state is guarded by the WindowManager lock.
    private final ArrayMap<WindowState, Mirror> mMirrors = new ArrayMap<>();
    private IBinder mLedger;
    private int mLedgerPid;
    private Mirror mCandidate;
    private InputConnection mInputConnection;
    private Check mPending;
    // The proof applies only to this candidate and is cleared whenever it changes.
    private boolean mCandidateHasUserControl;
    private DisplayContent mActiveDisplay;

    private record Mirror(WindowState window, Lease lease, DisplayContent display, boolean editing) {}

    /** One input connection for this visit to the mirror. Its Binder is never reused. */
    private final class InputConnection extends Binder {
        final Mirror mirror;
        final int userId, brokerPid;
        final ParcelFileDescriptor endpoint;
        private long downTime = -1;
        private int touchX, touchY;

        InputConnection(Mirror mirror, int userId, int brokerPid, ParcelFileDescriptor endpoint) {
            this.mirror = mirror;
            this.userId = userId;
            this.brokerPid = brokerPid;
            this.endpoint = endpoint;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != WorkspaceInputConnection.SEND_INPUT
                    && code != WorkspaceInputConnection.CLOSE_INPUT) {
                return super.onTransact(code, data, reply, flags);
            }
            if (Binder.getCallingUid() != Process.ROOT_UID || (code == WorkspaceInputConnection.SEND_INPUT
                    && Binder.getCallingPid() != brokerPid)) {
                throw new SecurityException("Invalid workspace input broker");
            }
            data.enforceInterface(WorkspaceInputConnection.INPUT);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (code == WorkspaceInputConnection.CLOSE_INPUT) {
                    data.enforceNoDataAvail();
                    synchronized (mWm.mGlobalLock) {
                        if (mInputConnection == this) revokeInputLocked();
                    }
                    return true;
                }
                if (reply == null) return false;
                int displayId = data.readInt();
                int requestedUser = data.readInt();
                Gesture gesture = Gesture.read(data);
                data.enforceNoDataAvail();
                boolean accepted = submitInput(this, displayId, requestedUser, gesture);
                reply.writeNoException();
                reply.writeBoolean(accepted);
                return true;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        boolean currentLocked(int displayId, int requestedUser) {
            return mInputConnection == this && mCandidate == mirror && foregroundMirrorLocked() == mirror
                    && mirror.display().getDisplayId() == displayId && userId == requestedUser
                    && mWm.mCurrentUserId == userId;
        }

        boolean touchLocked(int action, long now, int x, int y) {
            long started = action == MotionEvent.ACTION_DOWN ? now : downTime;
            boolean finished = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
            MotionEvent event = MotionEvent.obtain(started, now, action, x, y,
                    finished ? 0f : 1f, 1f, 0, 1f, 1f, 0, 0);
            try {
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                if (!injectLocked(event, mirror.lease().displayId())) return false;
                downTime = finished ? -1 : started;
                touchX = x;
                touchY = y;
                return true;
            } finally {
                event.recycle();
            }
        }

        void cancelTouchLocked() {
            if (downTime < 0) return;
            final DisplayContent display = mirror.display();
            if (!display.isRemovedOrInvalid() && !display.isRemoving()) {
                touchLocked(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis(), touchX, touchY);
            }
            downTime = -1;
        }
    }

    private final class Check extends Binder {
        final IBinder ledger = mLedger;
        final Mirror mirror = mCandidate;
        final CountDownLatch done = new CountDownLatch(1);
        final Runnable query = this::queryLedger;
        final Runnable timeout = () -> finish(false, true);
        volatile boolean accepted;

        private void queryLedger() {
            synchronized (mWm.mGlobalLock) {
                if (mPending != this) return;
                mWm.mH.postDelayed(timeout, QUERY_TIMEOUT_MS);
            }
            // One-way in each direction. The result is handled on WindowManager's handler.
            try {
                if (WorkspaceInputConnection.queryControl(ledger, mirror.lease(), this)) return;
            } catch (RemoteException | RuntimeException error) {
                Slog.w(TAG, "Unable to verify workspace control", error);
            }
            finish(false, true);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != WorkspaceInputConnection.CONTROL_RESULT) {
                return super.onTransact(code, data, reply, flags);
            }
            // One-way Binder calls have no caller PID. Only the ledger gets this callback.
            if (Binder.getCallingUid() != Process.ROOT_UID) {
                throw new SecurityException("Invalid workspace input ledger reply");
            }
            data.enforceInterface(WorkspaceInputConnection.CONTROLLER);
            boolean allowed = data.readBoolean();
            data.enforceNoDataAvail();
            mWm.mH.post(() -> finish(allowed, false));
            return true;
        }

        void finish(boolean allowed, boolean retry) {
            synchronized (mWm.mGlobalLock) {
                if (mPending != this) return;
                mWm.mH.removeCallbacks(timeout);
                mPending = null;
                accepted = allowed;
                mCandidateHasUserControl = allowed;
                try {
                    refreshFocusLocked();
                    // Session cleanup can revoke a lease independently of a GUI hand-back.
                    if ((mCandidateHasUserControl || retry) && mCandidate == mirror
                            && mPending == null) {
                        requestCheckLocked(RECHECK_MS);
                    }
                } finally {
                    done.countDown();
                }
            }
        }
    }

    WorkspaceInputController(WindowManagerService wm) {
        mWm = wm;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == WorkspaceInputConnection.OPEN_INPUT && reply != null) {
            data.enforceInterface(WorkspaceInputConnection.CONTROLLER);
            final IBinder window = data.readStrongBinder();
            final Lease lease = Lease.read(data);
            final int userId = data.readInt();
            final long revision = data.readLong();
            final ParcelFileDescriptor endpoint = data.readTypedObject(ParcelFileDescriptor.CREATOR);
            final int uid = Binder.getCallingUid();
            final int pid = Binder.getCallingPid();
            final long identity = Binder.clearCallingIdentity();
            boolean opened = false;
            try (endpoint) {
                data.enforceNoDataAvail();
                if (endpoint != null) opened = openInput(window, lease, userId, revision, uid, pid, endpoint);
            } catch (IOException error) {
                Slog.w(TAG, "Workspace input endpoint error", error);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            reply.writeNoException();
            reply.writeBoolean(opened);
            return true;
        }
        if (code < WorkspaceInputConnection.REGISTER_LEDGER
                || code > WorkspaceInputConnection.SET_MIRROR) {
            return super.onTransact(code, data, reply, flags);
        }
        if (reply == null) return false;
        data.enforceInterface(WorkspaceInputConnection.CONTROLLER);
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        if (uid != (code == WorkspaceInputConnection.SET_MIRROR
                ? Process.SYSTEM_UID : Process.ROOT_UID)) {
            throw new SecurityException("Invalid workspace input caller");
        }
        final IBinder token = data.readStrongBinder();
        final boolean withLease = code != WorkspaceInputConnection.REGISTER_LEDGER;
        final Lease lease = withLease && data.readBoolean() ? Lease.read(data) : null;
        final boolean value = withLease && data.readBoolean();
        data.enforceNoDataAvail();
        final long identity = Binder.clearCallingIdentity();
        try {
            final boolean accepted = switch (code) {
                case WorkspaceInputConnection.REGISTER_LEDGER -> registerLedger(token, pid);
                case WorkspaceInputConnection.CONTROL_CHANGED ->
                        controlChanged(token, lease, value, pid);
                case WorkspaceInputConnection.SET_MIRROR -> setMirror(token, lease, value, uid, pid);
                default -> false;
            };
            reply.writeNoException();
            reply.writeBoolean(accepted);
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean registerLedger(IBinder ledger, int pid) throws RemoteException {
        if (ledger == null) return false;
        synchronized (mWm.mGlobalLock) {
            if (mLedger != null) return mLedger == ledger && mLedgerPid == pid;
            ledger.linkToDeath(() -> onLedgerDied(ledger), 0);
            mLedger = ledger;
            mLedgerPid = pid;
            resetCandidateLocked();
            refreshFocusLocked();
            return true;
        }
    }

    private void onLedgerDied(IBinder ledger) {
        synchronized (mWm.mGlobalLock) {
            if (mLedger != ledger) return;
            mLedger = null;
            mLedgerPid = 0;
            resetCandidateLocked();
            refreshFocusLocked();
        }
    }

    private boolean setMirror(IBinder token, Lease lease, boolean editing, int uid, int pid) {
        synchronized (mWm.mGlobalLock) {
            final WindowState window = mWm.mWindowMap.get(token);
            if (window == null) return lease == null;
            if (window.mOwnerUid != uid || window.mSession.mPid != pid
                    || window.getDisplayId() != DEFAULT_DISPLAY || window.mActivityRecord == null
                    || !MIRROR.equals(window.mActivityRecord.mActivityComponent)) {
                throw new SecurityException("Invalid workspace mirror window");
            }
            if (lease == null) {
                mMirrors.remove(window);
            } else {
                final DisplayContent display = findDisplayLocked(lease);
                if (display == null) return false;
                final Mirror binding = new Mirror(window, lease, display, editing);
                if (binding.equals(mMirrors.get(window))) return true;
                mMirrors.put(window, binding);
            }
            refreshFocusLocked();
            return true;
        }
    }

    void mirrorRemovedLocked(WindowState window) {
        if (mMirrors.remove(window) == null) return;
        // The window is still being removed. Let the normal focus pass see the new tree.
        mWm.mFocusMayChange = true;
        mWm.requestTraversal();
    }

    private boolean controlChanged(IBinder ledger, Lease lease, boolean userControlled, int pid) {
        if (lease == null) return false;
        final Check check;
        synchronized (mWm.mGlobalLock) {
            if (mLedger != ledger || mLedgerPid != pid) return false;
            // Revoke before recalculating, and discard any reply from the old state.
            if (!userControlled && mCandidate != null && mCandidate.lease().equals(lease)) {
                revokeInputLocked();
                cancelQueryLocked();
                mCandidateHasUserControl = false;
            }
            refreshFocusLocked();
            if (!userControlled || mCandidate == null || !mCandidate.lease().equals(lease)) {
                return true;
            }
            cancelQueryLocked();
            // Complete foreground takeovers before replying to the Broker.
            check = requestCheckLocked(0);
        }
        try {
            if (check.done.await(QUERY_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS)) return check.accepted;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        check.finish(false, true);
        return check.accepted;
    }

    private boolean openInput(IBinder token, Lease lease, int userId, long revision, int uid,
            int pid, ParcelFileDescriptor endpoint) throws RemoteException, IOException {
        final InputConnection connection;
        final IBinder ledger;
        synchronized (mWm.mGlobalLock) {
            final Mirror mirror = foregroundMirrorLocked();
            if (mirror == null || mirror != mCandidate || !mirror.lease().equals(lease)
                    || mWm.mCurrentUserId != userId || revision < 0) {
                return false;
            }
            final WindowState window = mirror.window();
            if (uid != Process.SYSTEM_UID || window.mOwnerUid != uid
                    || window.mSession.mPid != pid || mWm.mWindowMap.get(token) != window) {
                return false;
            }
            connection = new InputConnection(mirror, userId, mLedgerPid,
                    ParcelFileDescriptor.dup(endpoint.getFileDescriptor()));
            revokeInputLocked();
            ledger = mLedger;
            mInputConnection = connection;
        }

        // The broker validates against its ledger and host outside the WindowManager lock.
        boolean opened = false;
        try {
            boolean peerOpened = WorkspaceInputConnection.openBrokerInput(ledger, connection, lease,
                    userId, revision, endpoint);
            synchronized (mWm.mGlobalLock) {
                opened = peerOpened && connection.currentLocked(lease.displayId(), userId);
                return opened;
            }
        } finally {
            if (!opened) {
                synchronized (mWm.mGlobalLock) {
                    if (mInputConnection == connection) revokeInputLocked();
                }
            }
        }
    }

    private boolean submitInput(InputConnection connection, int displayId, int userId, Gesture gesture) {
        final boolean swipe = "swipe".equals(gesture.type());
        if (swipe && (gesture.durationMs() < 100 || gesture.durationMs() > 1500)) return false;
        final long start;
        synchronized (mWm.mGlobalLock) {
            if (!connection.currentLocked(displayId, userId) || connection.downTime >= 0) return false;
            start = SystemClock.uptimeMillis();
            if ("key".equals(gesture.type())) return backLocked(displayId, start);
            if (!swipe && !"tap".equals(gesture.type())) return false;
            if (!connection.touchLocked(MotionEvent.ACTION_DOWN, start, gesture.x(), gesture.y())) {
                return false;
            }
            if (!swipe) {
                boolean accepted = connection.touchLocked(MotionEvent.ACTION_UP,
                        SystemClock.uptimeMillis(), gesture.x(), gesture.y());
                connection.cancelTouchLocked();
                return accepted;
            }
        }

        try {
            while (true) {
                SystemClock.sleep(16);
                synchronized (mWm.mGlobalLock) {
                    if (!connection.currentLocked(displayId, userId)) return false;
                    long now = SystemClock.uptimeMillis();
                    float fraction = (float) (now - start) / gesture.durationMs();
                    if (fraction >= 1f) {
                        return connection.touchLocked(MotionEvent.ACTION_UP, now, gesture.toX(), gesture.toY());
                    }
                    int x = Math.round(gesture.x() + (gesture.toX() - gesture.x()) * fraction);
                    int y = Math.round(gesture.y() + (gesture.toY() - gesture.y()) * fraction);
                    if (!connection.touchLocked(MotionEvent.ACTION_MOVE, now, x, y)) return false;
                }
            }
        } finally {
            synchronized (mWm.mGlobalLock) {
                connection.cancelTouchLocked();
            }
        }
    }

    private boolean backLocked(int displayId, long at) {
        KeyEvent down = new KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        return injectLocked(down, displayId)
                && injectLocked(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), displayId);
    }

    private boolean injectLocked(InputEvent event, int displayId) {
        event.setDisplayId(displayId);
        // Queue with the connection check still locked, without waiting for input dispatch.
        long identity = Binder.clearCallingIdentity();
        try {
            return mWm.mInputManager.injectInputEventToTarget(event, INJECT_INPUT_EVENT_MODE_ASYNC,
                    Process.INVALID_UID);
        } catch (RuntimeException failure) {
            Slog.w(TAG, "Unable to submit workspace input", failure);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    boolean keepsFocusForMirrorTouchLocked(WindowState window) {
        return mActiveDisplay != null && mCandidate != null && mCandidate.window() == window;
    }

    boolean canSelectFocusLocked(DisplayContent display) {
        return !isManagedDisplay(display) || display.canStealTopFocus();
    }

    boolean usesLocalImeLocked(DisplayContent display) {
        return mActiveDisplay == display;
    }

    int selectInputDisplayLocked(int defaultInputDisplay) {
        final Mirror next = defaultInputDisplay == DEFAULT_DISPLAY ? foregroundMirrorLocked() : null;
        if (next != mCandidate) {
            resetCandidateLocked();
            mCandidate = next;
            if (next != null) requestCheckLocked(0);
        }
        final DisplayContent target = mCandidateHasUserControl && next != null
                ? next.display() : null;
        final DisplayContent display = target != null && target.mCurrentFocus != null ? target : null;
        final int displayId = display == null ? defaultInputDisplay : display.getDisplayId();
        if (display != mActiveDisplay) {
            final DisplayContent previousDisplay = mWm.mRoot.getTopFocusedDisplayContent();
            final int previousId = mActiveDisplay != null ? mActiveDisplay.getDisplayId()
                    : previousDisplay != null ? previousDisplay.getDisplayId() : DEFAULT_DISPLAY;
            mActiveDisplay = display;
            mWm.mRoot.updateDisplayImePolicyCache();
            if (previousId != displayId) {
                final DisplayContent focusedDisplay = mWm.mRoot.getDisplayContent(displayId);
                final WindowState window = focusedDisplay == null ? null : focusedDisplay.mCurrentFocus;
                final IBinder token = window == null ? null : window.getWindowToken();
                mWm.mH.post(() -> InputMethodManagerInternal.get()
                        .onWorkspaceInputDisplayChanged(previousId, displayId, token));
            }
            Slog.i(TAG, "Input display changed from " + previousId + " to " + displayId);
        }
        return displayId;
    }

    private Mirror foregroundMirrorLocked() {
        if (mLedger == null || mMirrors.isEmpty() || mWm.isKeyguardLocked()) return null;
        final DisplayContent main = mWm.getDefaultDisplayContentLocked();
        if (main == null) return null;
        final WindowState window = main.mCurrentFocus;
        final Mirror mirror = mMirrors.get(window);
        if (mirror == null || mirror.editing() || !window.isVisibleRequested()
                || window.mShowUserId != mWm.mCurrentUserId
                || main.mTransitionController.hasTransientLaunch(main)
                || (window.getTask() != null
                        && main.mTransitionController.isTransientHide(window.getTask()))) {
            return null;
        }
        final DisplayContent display = mirror.display();
        return !display.isRemovedOrInvalid() && !display.isRemoving() ? mirror : null;
    }

    private DisplayContent findDisplayLocked(Lease lease) {
        final DisplayContent dc = mWm.mRoot.getDisplayContent(lease.displayId());
        if (dc == null || dc.getDisplayId() == DEFAULT_DISPLAY || dc.isRemovedOrInvalid()
                || dc.isRemoving() || !isManagedDisplay(dc)) {
            return null;
        }
        return (DISPLAY_PREFIX + lease.key()).equals(dc.getDisplayInfo().name) ? dc : null;
    }

    private static boolean isManagedDisplay(DisplayContent dc) {
        final var info = dc.getDisplayInfo();
        return info.type == Display.TYPE_VIRTUAL && info.ownerUid == Process.ROOT_UID
                && (info.flags & Display.FLAG_OWN_FOCUS) != 0
                && (info.flags & Display.FLAG_STEAL_TOP_FOCUS_DISABLED) != 0
                && info.name.startsWith(DISPLAY_PREFIX) && dc.isTrusted();
    }

    private Check requestCheckLocked(int delayMs) {
        final Check check = new Check();
        mPending = check;
        mWm.mH.postDelayed(check.query, delayMs);
        return check;
    }

    private void resetCandidateLocked() {
        revokeInputLocked();
        cancelQueryLocked();
        mCandidate = null;
        mCandidateHasUserControl = false;
    }

    private void revokeInputLocked() {
        final InputConnection connection = mInputConnection;
        mInputConnection = null;
        if (connection == null) return;
        connection.cancelTouchLocked();
        try { Os.shutdown(connection.endpoint.getFileDescriptor(), OsConstants.SHUT_RDWR); }
        catch (ErrnoException ignored) { }
        try { connection.endpoint.close(); } catch (IOException ignored) { }
    }

    private void cancelQueryLocked() {
        if (mPending == null) return;
        mWm.mH.removeCallbacks(mPending.query);
        mWm.mH.removeCallbacks(mPending.timeout);
        mPending.accepted = true; // Another event superseded this check.
        mPending.done.countDown();
        mPending = null;
    }

    void refreshFocusLocked() {
        if (mCandidate == null && mMirrors.isEmpty() && mActiveDisplay == null) return;
        mWm.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /* updateInputWindows */);
    }
}
