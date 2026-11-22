package org.agentos.workspace;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Binder protocol between WindowManager, the mirror window and TaskBroker. */
public final class WorkspaceInputConnection {
    public static final String CONTROLLER = "org.agentos.workspace.IWorkspaceInputController";
    public static final String LEDGER = "org.agentos.workspace.IWorkspaceInputLedger";
    public static final int REGISTER_LEDGER = IBinder.FIRST_CALL_TRANSACTION;
    public static final int CONTROL_CHANGED = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int SET_MIRROR = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int QUERY_CONTROL = IBinder.FIRST_CALL_TRANSACTION;
    public static final int CONTROL_RESULT = IBinder.FIRST_CALL_TRANSACTION + 1;

    public record Lease(String thread, String key, String generation, int displayId) {
        public static Lease read(Parcel data) {
            return new Lease(data.readString(), data.readString(), data.readString(), data.readInt());
        }

        public void write(Parcel data) {
            data.writeString(thread);
            data.writeString(key);
            data.writeString(generation);
            data.writeInt(displayId);
        }
    }

    public static boolean registerLedger(IBinder controller, IBinder ledger) throws RemoteException {
        return call(controller, REGISTER_LEDGER, ledger, null, false);
    }

    public static boolean controlChanged(IBinder controller, IBinder ledger, Lease lease,
            boolean userControlled) throws RemoteException {
        return call(controller, CONTROL_CHANGED, ledger, lease, userControlled);
    }

    public static boolean setMirror(IBinder controller, IBinder window, Lease lease,
            boolean editing) throws RemoteException {
        return call(controller, SET_MIRROR, window, lease, editing);
    }

    private static boolean call(IBinder controller, int code, IBinder token, Lease lease,
            boolean value) throws RemoteException {
        final Parcel request = Parcel.obtain();
        final Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken(CONTROLLER);
            request.writeStrongBinder(token);
            if (code != REGISTER_LEDGER) {
                request.writeBoolean(lease != null);
                if (lease != null) lease.write(request);
                request.writeBoolean(value);
            }
            if (!controller.transact(code, request, response, 0)) {
                return false;
            }
            response.readException();
            return response.readBoolean();
        } finally {
            response.recycle();
            request.recycle();
        }
    }

    public static boolean queryControl(IBinder ledger, Lease lease, IBinder result)
            throws RemoteException {
        final Parcel request = Parcel.obtain();
        try {
            request.writeInterfaceToken(LEDGER);
            lease.write(request);
            request.writeStrongBinder(result);
            return ledger.transact(QUERY_CONTROL, request, null, IBinder.FLAG_ONEWAY);
        } finally {
            request.recycle();
        }
    }

    public static void reportControl(IBinder result, boolean allowed) throws RemoteException {
        final Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CONTROLLER);
            data.writeBoolean(allowed);
            result.transact(CONTROL_RESULT, data, null, IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }

    /**
     * The extension is optional on component-only builds, which use public SDK stubs.
     * Keep the lookup out of the regular Android window-service interface.
     */
    public static IBinder controller() throws ReflectiveOperationException, RemoteException {
        IBinder windowManager = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "window");
        if (windowManager == null) return null;
        return (IBinder) IBinder.class.getMethod("getExtension").invoke(windowManager);
    }

    private WorkspaceInputConnection() {}
}
