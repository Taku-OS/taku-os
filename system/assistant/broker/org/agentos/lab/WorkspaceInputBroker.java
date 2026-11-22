package org.agentos.lab;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.io.IOException;
import org.agentos.workspace.WorkspaceInputConnection;
import org.agentos.workspace.WorkspaceInputConnection.Lease;

/** The input controller can check a lease without taking its control or input lock. */
final class WorkspaceInputBroker {
    private static final IBinder LEDGER = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != WorkspaceInputConnection.QUERY_CONTROL) {
                return super.onTransact(code, data, reply, flags);
            }
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Only the system can query workspace input");
            }
            data.enforceInterface(WorkspaceInputConnection.LEDGER);
            Lease lease = Lease.read(data);
            IBinder result = data.readStrongBinder();
            data.enforceNoDataAvail();
            boolean allowed = DedicatedScreensOnDevice.shared().hasUserInputControl(
                    lease.thread(), lease.key(), lease.generation(), lease.displayId());
            if (result != null) WorkspaceInputConnection.reportControl(result, allowed);
            return true;
        }
    };

    static void controlChanged(String thread, PhoneExecutionScreens.Screen screen,
            boolean userControlled) throws IOException {
        try {
            IBinder controller = WorkspaceInputConnection.controller();
            // Component-only installs can still exercise the original display-scoped gestures.
            if (controller == null) return;
            Lease lease = new Lease(thread, screen.screenKey, screen.generation, screen.displayId);
            if (!WorkspaceInputConnection.registerLedger(controller, LEDGER)
                    || !WorkspaceInputConnection.controlChanged(controller, LEDGER, lease,
                            userControlled)) {
                throw new IOException("WORKSPACE_INPUT_SWITCH_FAILED");
            }
        } catch (RemoteException | ReflectiveOperationException | RuntimeException error) {
            throw new IOException("WORKSPACE_INPUT_SWITCH_FAILED", error);
        }
    }

    private WorkspaceInputBroker() {}
}
