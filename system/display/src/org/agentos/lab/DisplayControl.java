package org.agentos.lab;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.view.Display;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Live, peer-verified wrapper client. Never trusts persisted display.id alone. */
public final class DisplayControl {
    private static DisplayManager manager;
    private static final class HostUnavailable extends IOException {
        HostUnavailable(IOException cause) { super("No live host in this slot", cause); }
    }
    private static boolean absentSocket(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof android.system.ErrnoException) {
                int errno = ((android.system.ErrnoException) cause).errno;
                return errno == android.system.OsConstants.ENOENT || errno == android.system.OsConstants.ECONNREFUSED;
            }
        }
        // Some Android LocalSocket JNI versions throw plain IOException(strerror(errno)).
        return "No such file or directory".equals(error.getMessage()) || "Connection refused".equals(error.getMessage());
    }

    private static DisplayManager displayManager() throws Exception {
        if (manager == null) {
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper();
            Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        }
        return manager;
    }

    private static final class Connection implements AutoCloseable {
        final DisplaySlot slot;
        final LocalSocket socket = new LocalSocket();
        final JSONObject identity;
        Connection(DisplaySlot slot) throws Exception {
            this.slot = slot;
            try {
                slot.checkDirectory();
                try { socket.connect(new LocalSocketAddress(slot.socket, LocalSocketAddress.Namespace.ABSTRACT)); }
                catch (IOException error) { if (absentSocket(error)) throw new HostUnavailable(error); throw error; }
                // Android LocalSocket has no native fd until connect creates it.
                socket.setSoTimeout(7000);
                identity = request("status");
                Credentials peer = socket.getPeerCredentials();
                slot.validateIdentity(identity, peer.getPid(), peer.getUid());
                Display display = displayManager().getDisplay(identity.getInt("displayId"));
                if (display == null || !slot.name.equals(display.getName()))
                    throw new IOException("Host display is no longer registered in Android");
            } catch (Exception error) { socket.close(); throw error; }
        }
        JSONObject request(String command) throws Exception {
            OutputStream output = socket.getOutputStream();
            output.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int length = input.readInt();
            if (length < 1 || length > 1024 * 1024) throw new IOException("Invalid host response length");
            byte[] bytes = new byte[length]; input.readFully(bytes);
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (value.startsWith("error:")) throw new IOException(value);
            return new JSONObject(value);
        }
        void requireGeneration(String generation) throws Exception {
            DisplaySlot.requireSameGeneration(generation, identity.getString("generation"));
        }
        public void close() throws IOException { socket.close(); }
    }

    private static JSONObject liveStatus(DisplaySlot slot) throws Exception {
        try (Connection connection = new Connection(slot)) { return connection.identity; }
    }
    private static void list() throws Exception {
        JSONArray screens = new JSONArray();
        try { screens.put(liveStatus(DisplaySlot.defaultSlot())); } catch (Exception ignored) {}
        File root = new File(DisplaySlot.ROOT, "screens");
        String[] names = root.list();
        if (names != null) {
            Arrays.sort(names);
            for (String name : names) {
                if (name.equals("default") || !DisplaySlot.KEY.matcher(name).matches()) continue;
                try { screens.put(liveStatus(DisplaySlot.named(name))); } catch (Exception ignored) {}
            }
        }
        System.out.println(new JSONObject().put("screens", screens));
    }
    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("list")) { list(); System.exit(0); return; }
            if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("Invalid command arguments");
            String command = args[0];
            if (!(command.equals("id") || command.equals("status") || command.equals("capture") || command.equals("stop") || command.equals("removal-status")))
                throw new IllegalArgumentException("Unsupported display command");
            if (args.length == 3 && !(command.equals("capture") || command.equals("stop") || command.equals("removal-status")))
                throw new IllegalArgumentException("Generation only applies to capture or stop");
            DisplaySlot slot = args.length >= 2 ? DisplaySlot.named(args[1]) : DisplaySlot.defaultSlot();
            String expected = args.length == 3 ? args[2] : null;
            if (expected != null) DisplaySlot.requireGeneration(expected);
            if (command.equals("removal-status") && expected == null) throw new IllegalArgumentException("Removal status requires a generation");
            try (Connection connection = new Connection(slot)) {
                if (expected != null && !command.equals("removal-status")) connection.requireGeneration(expected);
                String generation = connection.identity.getString("generation");
                if (command.equals("removal-status")) System.out.println(new JSONObject().put("state", expected.equals(generation) ? "current" : "replaced"));
                else if (command.equals("id")) System.out.println(connection.identity.getInt("displayId"));
                else if (command.equals("status")) System.out.println(connection.identity);
                else if (command.equals("capture")) {
                    JSONObject result = connection.request("capture " + generation);
                    slot.validateIdentity(result, connection.identity.getInt("pid"), connection.identity.getInt("uid"));
                    DisplaySlot.requireSameGeneration(generation, result.getString("generation"));
                    System.out.println(result);
                    System.out.println(new File(slot.directory, "screen.png").getPath());
                } else {
                    JSONObject result = connection.request("stop " + generation);
                    if (!generation.equals(result.getString("generation")) || !result.getBoolean("stopping"))
                        throw new IOException("Host did not acknowledge this generation's stop");
                    File process = new File("/proc/" + connection.identity.getInt("pid"));
                    long deadline = SystemClock.elapsedRealtime() + 6000;
                    while (process.exists()) {
                        if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Host stop timed out");
                        Thread.sleep(50);
                    }
                }
            } catch (HostUnavailable error) {
                if (command.equals("removal-status")) {
                    // A missing socket alone is not proof of physical shutdown.
                    for (Display display : displayManager().getDisplays())
                        if (slot.name.equals(display.getName())) throw new IOException("SCREEN_HOST_STOP_UNCONFIRMED", error);
                    System.out.println(new JSONObject().put("state", "gone"));
                    System.exit(0); return;
                }
                // Legacy cleanup may stop a default display that was never started.
                // A foreign/stale live identity, named slot, or explicit generation still fails closed.
                if (!slot.permitsAbsentHostStop(command, expected)) throw error;
            }
            // systemMain starts Binder threads: a completed CLI must explicitly exit.
            System.exit(0);
        } catch (Exception error) {
            System.err.println("phone-display: " + error.getMessage());
            System.exit(1);
        }
    }
}
