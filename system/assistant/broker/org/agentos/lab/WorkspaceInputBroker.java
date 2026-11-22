package org.agentos.lab;

import android.net.LocalSocket;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import org.agentos.workspace.WorkspaceInputConnection;
import org.agentos.workspace.WorkspaceInputConnection.Lease;
import org.json.JSONObject;

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

    static void serveInput(LocalSocket socket, InputStream incoming) throws IOException {
        DataInputStream input = new DataInputStream(incoming);
        DataOutputStream reply = new DataOutputStream(socket.getOutputStream());
        socket.setSoTimeout(8000);
        try (InputSession session = new InputSession(new JSONObject(input.readUTF()))) {
            session.open();
            reply.writeBoolean(true);
            StructPollfd ready = new StructPollfd();
            ready.fd = socket.getFileDescriptor();
            ready.events = (short) OsConstants.POLLIN;
            StructPollfd[] descriptors = {ready};
            while (true) {
                // Keep the verified host socket open across its 10-second idle timeout.
                if (Os.poll(descriptors, 4000) == 0) {
                    session.keepAlive();
                    continue;
                }
                session.send(new JSONObject(input.readUTF()));
                reply.writeBoolean(true);
            }
        } catch (EOFException disconnected) {
            // The mirror closed its half of the stream.
        } catch (Exception unavailable) {
            reply.writeBoolean(false);
        }
    }

    private static final class InputSession implements AutoCloseable {
        private final DedicatedScreens registry = DedicatedScreensOnDevice.shared();
        private final IBinder controller;
        private final String thread, key, generation;
        private final long revision, target;
        private WorkspaceControl.Channel host;

        InputSession(JSONObject request) throws Exception {
            controller = WorkspaceInputConnection.controller();
            thread = request.getString("threadId");
            key = request.getString("screenKey");
            generation = request.getString("generation");
            revision = request.getLong("controlRevision");
            target = request.getLong("inputTarget");
            if (controller == null || revision < 0 || target <= 0) {
                throw new IOException("WORKSPACE_INPUT_TARGET_CHANGED");
            }
        }

        void open() throws Exception {
            withControl(true, screen -> {
                WorkspaceControl.requireQuiescent(thread);
                host = new WorkspaceControl.Channel(screen);
                checkTarget(screen);
                return null;
            });
        }

        void send(JSONObject input) throws Exception {
            withControl(false, screen -> {
                WorkspaceControl.requireRuntimeIdle(thread);
                checkTarget(screen);
                WorkspaceControl.sendInput(host, input);
                return null;
            });
        }

        private void withControl(boolean validateHost, PhoneExecutionScreens.Action action)
                throws Exception {
            PhoneExecutionScreens.Lease binding = registry.exact(thread, key, generation);
            if (!binding.controlLock().tryLock()) throw new IOException("WORKSPACE_CONTROL_BUSY");
            try {
                registry.userInput(thread, key, generation, revision, validateHost, action);
            } finally {
                binding.controlLock().unlock();
            }
        }

        private void checkTarget(PhoneExecutionScreens.Screen screen) throws Exception {
            Lease lease = new Lease(thread, key, generation, screen.displayId);
            if (WorkspaceInputConnection.inputTarget(controller, LEDGER, lease, screen.userId,
                    target) != target) {
                throw new IOException("WORKSPACE_INPUT_TARGET_CHANGED");
            }
        }

        void keepAlive() throws Exception {
            checkTarget(host.screen);
            host.status();
        }

        @Override public void close() throws IOException {
            if (host != null) host.close();
        }
    }

    private WorkspaceInputBroker() {}
}
