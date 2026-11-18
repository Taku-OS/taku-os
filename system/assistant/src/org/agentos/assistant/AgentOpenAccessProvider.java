package org.agentos.assistant;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Ordinary device UIDs use the same native commands as the GUI. */
public final class AgentOpenAccessProvider extends ContentProvider {
    private static final Set<String> RPC = new HashSet<>(Arrays.asList(
        "plugin/list", "plugin/installed", "plugin/read", "plugin/install", "plugin/uninstall",
        "config/value/write", "config/mcpServer/reload", "mcpServerStatus/list", "account/usage/read", "account/rateLimits/read"));
    private static final int INLINE_BYTES = 256 * 1024, MAX_RESULT_BYTES = 8 * 1024 * 1024, TOTAL_BYTES = 16 * 1024 * 1024;
    private final LinkedHashMap<String, Payload> payloads = new LinkedHashMap<>();
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "open-access-result-expiry"); thread.setDaemon(true); return thread;
    });
    private static final class Payload {
        final int uid;
        final byte[] bytes;
        final long expires = SystemClock.elapsedRealtime() + 30_000;
        boolean opened;
        Payload(int uid, byte[] bytes) { this.uid = uid; this.bytes = bytes; }
    }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        int caller = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        JSONObject envelope;
        try {
            if (arg != null && arg.length() > AgentGatewayProtocol.MAX_REQUEST) throw new IOException("INVALID_ARGUMENT");
            JSONObject input = arg == null || arg.isEmpty() ? new JSONObject() : new JSONObject(arg);
            JSONObject result;
            if (method.startsWith("panels/")) result = PanelRegistry.get(getContext()).call(method.substring(7), input);
            else if (method.startsWith("card-view/")) result = PanelRegistry.get(getContext()).cardView(method.substring(10), input);
            else if (method.equals("matter/read")) {
                PanelProtocol.parse(method, input);
                result = MatterRuntime.get(getContext()).read(input.getString("matterId"));
            } else if (method.equals("matter/dispatch")) {
                PanelProtocol.parse(method, input);
                result = MatterRuntime.get(getContext()).dispatch(input);
            } else if (method.equals("reply/read")) {
                envelope = AgentGatewayProtocol.replyRead(input, NativeClient::exchange);
                if (!envelope.optBoolean("ok")) {
                    String code = envelope.getJSONObject("error").optString("code", "SOURCE_UNAVAILABLE");
                    envelope = error(code);
                }
                return transport(out, envelope, caller);
            } else if (method.startsWith("skills/")) result = TaskRequests.control(method, input);
            else if (RPC.contains(method)) result = NativeClient.rpcSync(method, input);
            else if (method.equals("thread/usage")) result = NativeClient.state(input.getString("threadId"));
            else if (method.equals("sessions/removals")) result = NativeClient.exchange(input.put("op", "sessionRemovals"));
            else if (method.equals("workspaces/list") || method.equals("workspaces/configure") || method.equals("workspaces/release")) {
                if (caller != 0 && caller != android.os.Process.SYSTEM_UID) throw new SecurityException("WORKSPACE_PERMISSION_DENIED");
                result = NativeClient.exchange(input.put("op", "workspaces").put("action", method.substring("workspaces/".length())));
            } else throw new IOException("UNKNOWN_METHOD");
            envelope = new JSONObject().put("ok", true).put("result", result);
            return transport(out, envelope, caller);
        } catch (Exception failure) {
            String code = failure instanceof SQLiteException ? "STORAGE_ERROR" : failure.getMessage();
            if (failure instanceof org.json.JSONException) code = "INVALID_ARGUMENT";
            if (code == null || !code.matches("[A-Z][A-Z_]{2,80}")) code = "SOURCE_UNAVAILABLE";
            out.putString("result", error(code).toString());
            return out;
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    private static JSONObject error(String code) {
        try { return new JSONObject().put("ok", false).put("code", code).put("message", code); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private Bundle transport(Bundle out, JSONObject envelope, int uid) throws Exception {
        byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESULT_BYTES) throw new IOException("RESULT_TOO_LARGE");
        if (bytes.length <= INLINE_BYTES) { out.putString("result", envelope.toString()); return out; }
        String token = UUID.randomUUID().toString();
        synchronized (payloads) {
            expire();
            int total = bytes.length;
            for (Payload payload : payloads.values()) total += payload.bytes.length;
            if (payloads.size() >= 8 || total > TOTAL_BYTES) throw new IOException("RESULT_BUFFER_FULL");
            payloads.put(token, new Payload(uid, bytes));
        }
        expiry.schedule(() -> { synchronized (payloads) { expire(); } }, 30, TimeUnit.SECONDS);
        out.putString("result", new JSONObject().put("ok", true).put("resultUri", "content://org.agentos.assistant.open-access/result/" + token).toString());
        return out;
    }
    private void expire() {
        long now = SystemClock.elapsedRealtime();
        payloads.values().removeIf(payload -> now >= payload.expires);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2 || !"result".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException("RESULT_EXPIRED");
        Payload payload;
        synchronized (payloads) {
            expire();
            payload = payloads.get(uri.getLastPathSegment());
            if (payload == null || payload.opened || payload.uid != Binder.getCallingUid()) throw new FileNotFoundException("RESULT_EXPIRED");
            payload.opened = true;
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            var deadline = expiry.schedule(() -> { try { pipe[1].closeWithError("RESULT_EXPIRED"); } catch (IOException ignored) { } },
                Math.max(1, payload.expires - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            Thread writer = new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { output.write(payload.bytes); }
                catch (IOException ignored) { }
                finally { deadline.cancel(false); synchronized (payloads) { payloads.remove(uri.getLastPathSegment()); } }
            }, "open-access-result");
            writer.setDaemon(true); writer.start();
            return pipe[0];
        } catch (IOException failure) { throw new FileNotFoundException("RESULT_EXPIRED"); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException("Use content call"); }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
