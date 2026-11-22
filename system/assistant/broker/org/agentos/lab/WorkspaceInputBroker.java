package org.agentos.lab;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import org.agentos.workspace.WorkspaceInputConnection;
import org.agentos.workspace.WorkspaceInputConnection.Gesture;
import org.agentos.workspace.WorkspaceInputConnection.Lease;
import org.json.JSONObject;

/** The input controller can check a lease without taking its control or input lock. */
final class WorkspaceInputBroker {
    private static final IBinder LEDGER = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != WorkspaceInputConnection.QUERY_CONTROL
                    && code != WorkspaceInputConnection.OPEN_BROKER_INPUT) {
                return super.onTransact(code, data, reply, flags);
            }
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Only the system can query workspace input");
            }
            data.enforceInterface(WorkspaceInputConnection.LEDGER);
            if (code == WorkspaceInputConnection.OPEN_BROKER_INPUT) {
                if (reply == null) return false;
                IBinder target = data.readStrongBinder();
                Lease lease = Lease.read(data);
                int userId = data.readInt();
                long revision = data.readLong();
                ParcelFileDescriptor endpoint = data.readTypedObject(ParcelFileDescriptor.CREATOR);
                boolean opened = false;
                long identity = Binder.clearCallingIdentity();
                try {
                    data.enforceNoDataAvail();
                    if (endpoint != null && target != null && revision >= 0) {
                        InputSession session = new InputSession(target, lease, userId, revision);
                        Thread worker = new Thread(() -> session.serve(endpoint), "workspace-input");
                        worker.setDaemon(true);
                        worker.start();
                        opened = true;
                    }
                } catch (Exception unavailable) {
                    // A window switch or an unavailable host can supersede an open in progress.
                } finally {
                    if (!opened && endpoint != null) {
                        try { endpoint.close(); } catch (IOException ignored) { }
                    }
                    Binder.restoreCallingIdentity(identity);
                }
                reply.writeNoException();
                reply.writeBoolean(opened);
                return true;
            }
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

    private static final class InputSession {
        private final DedicatedScreens registry = DedicatedScreensOnDevice.shared();
        private final IBinder target;
        private final Lease lease;
        private final int userId, width, height;
        private final long revision;

        InputSession(IBinder target, Lease lease, int userId, long revision) throws Exception {
            this.target = target;
            this.lease = lease;
            this.userId = userId;
            this.revision = revision;
            JSONObject status = withControl(true, screen -> {
                WorkspaceControl.requireQuiescent(lease.thread());
                try (WorkspaceControl.Channel host = new WorkspaceControl.Channel(screen)) {
                    return host.initialStatus;
                }
            });
            width = status.getInt("width");
            height = status.getInt("height");
        }

        void serve(ParcelFileDescriptor endpoint) {
            try (endpoint) {
                FileDescriptor fd = endpoint.getFileDescriptor();
                DataInputStream input = new DataInputStream(new FileInputStream(fd));
                DataOutputStream reply = new DataOutputStream(new FileOutputStream(fd));
                try {
                    while (true) {
                        Gesture gesture = WorkspaceControl.gesture(new JSONObject(input.readUTF()), width, height);
                        withControl(false, screen -> {
                            WorkspaceControl.requireRuntimeIdle(lease.thread());
                            if (!WorkspaceInputConnection.submitInput(target,
                                    screen.displayId, screen.userId, gesture)) {
                                throw new IOException("WORKSPACE_INPUT_TARGET_CHANGED");
                            }
                            return null;
                        });
                        reply.writeBoolean(true);
                    }
                } catch (EOFException disconnected) {
                    // The mirror closed the stream.
                } catch (Exception unavailable) {
                    reply.writeBoolean(false);
                }
            } catch (IOException disconnected) {
                // The mirror can close while a reply is on its way.
            } finally {
                WorkspaceInputConnection.closeInput(target);
            }
        }

        private JSONObject withControl(boolean validateHost, PhoneExecutionScreens.Action action)
                throws Exception {
            PhoneExecutionScreens.Lease binding = registry.exact(lease.thread(), lease.key(), lease.generation());
            if (!binding.controlLock().tryLock()) throw new IOException("WORKSPACE_CONTROL_BUSY");
            try {
                return registry.userInput(lease.thread(), lease.key(), lease.generation(), revision,
                        validateHost, screen -> {
                            if (screen.displayId != lease.displayId() || screen.userId != userId) {
                                throw new IOException("WORKSPACE_INPUT_TARGET_CHANGED");
                            }
                            return action.run(screen);
                        });
            } finally {
                binding.controlLock().unlock();
            }
        }
    }

    private WorkspaceInputBroker() {}
}
