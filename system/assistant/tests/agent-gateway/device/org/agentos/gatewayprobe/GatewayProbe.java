package org.agentos.gatewayprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only installed-APK IPC probe. Runs under the instrumented app's ordinary UID. */
public final class GatewayProbe extends Instrumentation {
    private static final String AUTHORITY = "org.agentos.assistant.agent-gateway";
    private Bundle arguments;
    private boolean pipeUsed;
    private static final Set<String> SECRET_KEYS = Set.of("accesstoken", "refreshtoken", "idtoken",
            "apikey", "authorization", "password", "authurl", "encryptedcontent");

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            int samples = Integer.parseInt(arguments.getString("previewSamples", "1"));
            if (samples < 1 || samples > 10) throw new IllegalArgumentException("previewSamples must be 1..10");
            boolean streamMode = "true".equals(arguments.getString("stream"));
            Context context = getTargetContext();
            int uid = Process.myUid();
            if (uid == 0 || uid == 1000 || uid == 2000 || uid != context.getApplicationInfo().uid)
                throw new AssertionError("Probe did not run as the ordinary target application UID");
            JSONObject result = new JSONObject().put("uid", uid).put("package", context.getPackageName())
                    .put("readOnly", true).put("modelTurnSubmitted", false);
            if ("true".equals(arguments.getString("expectDenied"))) {
                boolean denied = false;
                try { envelope(context, "listThreads", new JSONObject()); }
                catch (SecurityException expected) { denied = true; }
                if (!denied) throw new AssertionError("A different ordinary UID/package was accepted");
                result.put("differentPackageDenied", true);
                if (streamMode) deniedStream(context, result);
            } else if ("true".equals(arguments.getString("homeWorkspaces"))) {
                if (!context.getPackageName().equals("org.agentos.agent")) throw new AssertionError("Wrong target app");
                String thread = arguments.getString("threadId", "");
                result.put("homeScreens", workspaceRows(context, new JSONObject().put("scope", "home")));
                if (!thread.isEmpty())
                    result.put("sessionScreens", workspaceRows(context, new JSONObject().put("threadId", thread)));
            } else {
                if (!context.getPackageName().equals("org.agentos.agent")) throw new AssertionError("Wrong target app");
                JSONObject rejected = envelope(context, "rpc", new JSONObject());
                if (rejected.optBoolean("ok") || !rejected.optString("code").equals("UNSUPPORTED_ACTION"))
                    throw new AssertionError("Raw RPC was not rejected");
                JSONObject listing = call(context, "listThreads", new JSONObject());
                JSONArray threads = listing.getJSONArray("data");
                result.put("threadCount", threads.length()).put("rawRpcRejected", true);
                String thread = arguments.getString("threadId", "");
                if (thread.isEmpty()) {
                    if (threads.length() == 0) throw new AssertionError("No real conversation available; no fixture created");
                    thread = threads.getJSONObject(0).getString("id");
                }
                JSONObject snapshot = call(context, "readSession", new JSONObject().put("threadId", thread).put("refresh", false));
                JSONObject record = snapshot.getJSONObject("thread");
                if (!thread.equals(record.getString("id"))) throw new AssertionError("Thread identity mismatch");
                result.put("threadId", thread).put("turnCount", record.getJSONArray("turns").length())
                        .put("historyComplete", snapshot.optBoolean("historyComplete"))
                        .put("runtimeReady", snapshot.getJSONObject("native").optBoolean("ready"));
                JSONObject extra = envelope(context, "readSession", new JSONObject().put("threadId", thread).put("command", "forbidden"));
                if (extra.optBoolean("ok") || !extra.optString("code").equals("UNSUPPORTED_PARAMETER"))
                    throw new AssertionError("Unknown parameter was not rejected");
                result.put("unknownParameterRejected", true);
                boolean samplingPassed = streamMode ? probeStream(context, thread, result) : samplePreviews(context, thread, samples, result);
                result.put("largeResultPipeExercised", pipeUsed);
                if (!samplingPassed) {
                    output.putString("result", result.put("passed", false).toString());
                    finish(0, output);
                    return;
                }
            }
            output.putString("result", result.put("passed", true).toString());
            finish(-1, output);
        } catch (Throwable failed) {
            output.putString("error", failed.getClass().getSimpleName() + ": " + failed.getMessage());
            finish(0, output);
        }
    }

    private JSONArray workspaceRows(Context context, JSONObject params) throws Exception {
        JSONObject catalog = call(context, "getWorkspaces", params);
        if (!"taku.agent-workspaces/1".equals(catalog.getString("schema")))
            throw new AssertionError("Wrong workspace catalog schema");
        JSONArray screens = catalog.getJSONArray("screens"), rows = new JSONArray();
        for (int i = 0; i < screens.length(); i++) {
            JSONObject screen = screens.getJSONObject(i);
            JSONObject row = new JSONObject();
            for (String field : new String[]{"threadId", "screenKey", "generation", "status", "previewUri"})
                row.put(field, screen.getString(field));
            rows.put(row);
        }
        return rows;
    }

    private void deniedStream(Context context, JSONObject result) throws Exception {
        String thread = arguments.getString("threadId", "00000000-0000-4000-8000-000000000001");
        boolean subscribeDenied = false, openDenied = false;
        try { envelope(context, "subscribeSession", new JSONObject().put("threadId", thread)); }
        catch (SecurityException expected) { subscribeDenied = true; }
        Uri uri = Uri.parse(arguments.getString("streamUri", "content://" + AUTHORITY + "/events/00000000-0000-4000-8000-000000000002"));
        try (ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(uri, "r")) { }
        catch (SecurityException expected) { openDenied = true; }
        if (!subscribeDenied || !openDenied) throw new AssertionError("Different package reached session stream");
        result.put("differentPackageSubscriptionDenied", true).put("differentPackageStreamOpenDenied", true);
    }

    /** Optional mode: attaches to an existing thread; never creates a thread or submits a model turn. */
    private boolean probeStream(Context context, String thread, JSONObject result) throws Exception {
        int listenMs = Integer.parseInt(arguments.getString("streamListenMs", "0"));
        if (listenMs < 0 || listenMs > 60_000) throw new IllegalArgumentException("streamListenMs must be 0..60000");
        JSONObject status = call(context, "readSession", new JSONObject().put("threadId", thread).put("statusOnly", true));
        if (!status.optBoolean("statusOnly") || !thread.equals(status.getJSONObject("thread").optString("id"))
                || status.getJSONObject("thread").has("turns") || status.has("historyComplete") || !status.has("native"))
            throw new AssertionError("Status-only poll included body/history or omitted native status");
        result.put("statusOnlyWithoutBody", true);
        JSONObject lease = call(context, "subscribeSession", new JSONObject().put("threadId", thread));
        String id = lease.getString("subscriptionId");
        Uri uri = streamUri(lease.getString("streamUri"));
        String epoch; long seq;
        long started = SystemClock.elapsedRealtimeNanos();
        try (EventPipe pipe = new EventPipe(context, uri)) {
            JSONObject initial = pipe.next(10_000);
            validateEvent(initial, thread);
            if (!"session/snapshot".equals(initial.getString("method"))) throw new AssertionError("First event was not snapshot");
            JSONObject snapshot = initial.getJSONObject("params").getJSONObject("thread");
            if (!thread.equals(snapshot.getString("id"))) throw new AssertionError("Cross-thread stream snapshot");
            assertClean(snapshot);
            epoch = initial.getString("brokerEpoch"); seq = initial.getLong("seq");
            result.put("subscribeSnapshot", true).put("snapshotCleanFields", true).put("snapshotMs", elapsedMs(started))
                    .put("snapshotSeq", seq).put("streamTurnCount", snapshot.optJSONArray("turns") == null ? 0 : snapshot.getJSONArray("turns").length());
            boolean reused = false;
            try (ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(uri, "r")) { reused = true; }
            catch (FileNotFoundException expected) { }
            if (reused) throw new AssertionError("Stream token opened twice");
            result.put("singleUseToken", true);
            if (!call(context, "unsubscribeSession", new JSONObject().put("subscriptionId", id)).optBoolean("closed"))
                throw new AssertionError("Unsubscribe not acknowledged");
            drainClosed(pipe, 5_000);
            result.put("unsubscribeClosesPipe", true);
            boolean reopened = false;
            try (ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(uri, "r")) { reopened = true; }
            catch (FileNotFoundException expected) { }
            if (reopened) throw new AssertionError("Closed token reopened");
            result.put("closedTokenRejected", true);
        } finally { call(context, "unsubscribeSession", new JSONObject().put("subscriptionId", id)); }

        JSONObject resumedLease = call(context, "subscribeSession", new JSONObject().put("threadId", thread)
                .put("brokerEpoch", epoch).put("afterSeq", seq));
        String resumedId = resumedLease.getString("subscriptionId");
        try (EventPipe pipe = new EventPipe(context, streamUri(resumedLease.getString("streamUri")))) {
            JSONObject ready = pipe.next(10_000); validateEvent(ready, thread);
            if (!"session/ready".equals(ready.getString("method")) || !epoch.equals(ready.getString("brokerEpoch")) || ready.getLong("seq") != seq)
                throw new AssertionError("Same-epoch cursor did not resume");
            result.put("sameEpochReplayReady", true);
            JSONObject counts = new JSONObject();
            int events = 0, deltaCharacters = 0;
            long deadline = SystemClock.elapsedRealtime() + listenMs;
            while (SystemClock.elapsedRealtime() < deadline) {
                JSONObject event;
                try { event = pipe.next(Math.max(1, deadline - SystemClock.elapsedRealtime())); }
                catch (TimeoutException ended) { break; }
                if (event == null) throw new AssertionError("Stream ended during optional live observation");
                validateEvent(event, thread);
                String method = event.getString("method");
                if (method.equals("session/heartbeat")) continue;
                if (method.equals("session/resync")) throw new AssertionError("Unexpected live resync");
                if (!epoch.equals(event.getString("brokerEpoch"))) throw new AssertionError("Runtime changed during probe");
                long received = event.getLong("seq");
                if (method.equals("session/snapshot")) {
                    if (received < seq) throw new AssertionError("Stale stream snapshot");
                } else if (received != seq + 1) throw new AssertionError("Sequence gap in live stream");
                seq = received;
                counts.put(method, counts.optInt(method) + 1); events++;
                if (method.equals("item/agentMessage/delta")) deltaCharacters += event.getJSONObject("params").getString("delta").length();
            }
            result.put("liveListenMs", listenMs).put("liveEvents", events).put("liveEventCounts", counts)
                    .put("liveDeltaCharacters", deltaCharacters).put("lastSeq", seq).put("sequenceGaps", 0);
        } finally { call(context, "unsubscribeSession", new JSONObject().put("subscriptionId", resumedId)); }
        return true;
    }

    private static void validateEvent(JSONObject event, String thread) throws Exception {
        if (event == null || !"taku.session-events/1".equals(event.optString("schema"))
                || !thread.equals(event.optString("threadId")) || event.optLong("seq", -1) < 0
                || !event.optString("brokerEpoch").matches("[a-f0-9-]{36}") || event.optJSONObject("params") == null)
            throw new AssertionError("Invalid stream envelope");
        if (event.length() != 6) throw new AssertionError("Unexpected stream envelope fields");
        assertClean(event);
    }

    private static void assertClean(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (SECRET_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "")))
                    throw new AssertionError("Unfiltered secret field in stream");
                assertClean(object.get(key));
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) assertClean(array.get(i));
        }
    }

    private static Uri streamUri(String value) {
        Uri uri = Uri.parse(value);
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || !uri.getPath().startsWith("/events/"))
            throw new AssertionError("Invalid stream URI");
        return uri;
    }

    private static void drainClosed(EventPipe pipe, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline)
            if (pipe.next(Math.max(1, deadline - SystemClock.elapsedRealtime())) == null) return;
        throw new AssertionError("Unsubscribe did not close its pipe");
    }

    /** Nonblocking descriptor + bounded poll makes a missing heartbeat/EOF a test failure, not a hang. */
    private static final class EventPipe implements AutoCloseable {
        final ParcelFileDescriptor descriptor;
        final byte[] buffer = new byte[8192];
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        int offset, length;
        EventPipe(Context context, Uri uri) throws Exception {
            descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) throw new AssertionError("No session pipe");
            Os.fcntlInt(descriptor.getFileDescriptor(), OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
        }
        JSONObject next(long timeoutMs) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (true) {
                while (offset < length) {
                    int value = buffer[offset++] & 255;
                    if (value == '\n') {
                        JSONObject result = new JSONObject(line.toString("UTF-8")); line.reset(); return result;
                    }
                    line.write(value);
                    if (line.size() > 8 * 1024 * 1024) throw new AssertionError("Session line exceeds contract limit");
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new TimeoutException("Session stream timed out");
                StructPollfd poll = new StructPollfd(); poll.fd = descriptor.getFileDescriptor(); poll.events = (short) OsConstants.POLLIN;
                if (Os.poll(new StructPollfd[]{poll}, (int) Math.min(remaining, 1000)) == 0) continue;
                try { length = Os.read(descriptor.getFileDescriptor(), buffer, 0, buffer.length); offset = 0; }
                catch (ErrnoException unavailable) { if (unavailable.errno == OsConstants.EAGAIN) continue; throw unavailable; }
                if (length == 0) {
                    if (line.size() > 0) throw new AssertionError("Truncated session line");
                    return null;
                }
            }
        }
        @Override public void close() throws Exception { descriptor.close(); }
    }

    /** Re-read the exact thread catalog before every real JPEG; no fixtures or mutations. */
    private boolean samplePreviews(Context context, String thread, int samples, JSONObject result) throws Exception {
        JSONArray metrics = new JSONArray();
        int passed = 0, failures = 0;
        for (int index = 0; index < samples; index++) {
            long started = SystemClock.elapsedRealtimeNanos(), phaseStarted = started;
            String phase = "catalog";
            JSONObject metric = new JSONObject().put("sample", index + 1).put("passed", false);
            try {
                JSONObject catalog = call(context, "getWorkspaces", new JSONObject().put("threadId", thread));
                metric.put("catalogMs", elapsedMs(phaseStarted));
                JSONArray screens = catalog.getJSONArray("screens");
                metric.put("workspaceCount", screens.length());
                result.put("workspaceCount", screens.length());
                if (screens.length() == 0) {
                    // Preserve the existing capability-probe result, but never claim a frame was verified.
                    metric.put("noWorkspace", true);
                } else {
                    JSONObject screen = screens.getJSONObject(0);
                    if (!thread.equals(screen.getString("threadId"))) throw new AssertionError("Cross-thread screen");
                    phase = "preview"; phaseStarted = SystemClock.elapsedRealtimeNanos();
                    byte[] frame = read(context, Uri.parse(screen.getString("previewUri")), 4_000_000);
                    metric.put("previewMs", elapsedMs(phaseStarted));
                    phase = "jpeg";
                    if (frame.length < 3 || (frame[0] & 255) != 255 || (frame[1] & 255) != 216)
                        throw new AssertionError("Not a real JPEG frame");
                    android.graphics.BitmapFactory.Options dimensions = new android.graphics.BitmapFactory.Options();
                    dimensions.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.length, dimensions);
                    if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0) throw new AssertionError("Invalid JPEG dimensions");
                    metric.put("passed", true).put("previewBytes", frame.length)
                            .put("previewWidth", dimensions.outWidth).put("previewHeight", dimensions.outHeight);
                    result.put("previewBytes", frame.length).put("previewSha256", sha256(frame))
                            .put("previewWidth", dimensions.outWidth).put("previewHeight", dimensions.outHeight);
                    passed++;
                }
            } catch (Exception | AssertionError failure) {
                failures++;
                if (phase.equals("catalog") || phase.equals("preview")) metric.put(phase + "Ms", elapsedMs(phaseStarted));
                // Only phase/class metrics: no URI, transcript, frame bytes or raw platform error text.
                metric.put("failedPhase", phase).put("errorClass", failure.getClass().getSimpleName());
            }
            metric.put("totalMs", elapsedMs(started));
            metrics.put(metric);
        }
        result.put("previewSamplesRequested", samples).put("previewSamplesPassed", passed)
                .put("previewSamplesFailed", failures).put("previewSamples", metrics)
                .put("previewVerified", passed == samples);
        return failures == 0;
    }

    private static double elapsedMs(long started) {
        return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0;
    }

    private JSONObject envelope(Context context, String action, JSONObject params) throws Exception {
        Bundle response = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY), action, params.toString(), null);
        if (response == null) throw new AssertionError("No response");
        JSONObject result = new JSONObject(response.getString("result"));
        String source = result.optString("resultUri");
        if (!source.isEmpty()) {
            Uri uri = Uri.parse(source);
            if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || !uri.getPath().startsWith("/result/"))
                throw new AssertionError("Invalid result URI");
            result = new JSONObject(new String(read(context, uri, 8 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
            pipeUsed = true;
        }
        return result;
    }

    private JSONObject call(Context context, String action, JSONObject params) throws Exception {
        JSONObject response = envelope(context, action, params);
        if (!response.optBoolean("ok")) throw new AssertionError(action + ": " + response.optString("code"));
        return response.getJSONObject("result");
    }

    private byte[] read(Context context, Uri uri, int limit) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new AssertionError("Missing content stream");
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                result.write(buffer, 0, count);
                if (result.size() > limit) throw new AssertionError("Stream exceeds contract limit");
            }
            return result.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
