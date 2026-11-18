package org.agentos.panelsprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Ordinary-UID caller for the public Panel interface. It holds no permission, owns no
 * test endpoint in the Assistant, and reports only bytes the provider returned. The host
 * pulls the full evidence file, so no envelope is truncated to fit an instrumentation result.
 */
public final class PanelsProbe extends Instrumentation {
    private static final String AUTHORITY = "org.agentos.assistant.open-access";
    private static final String PROVIDER = "content://" + AUTHORITY;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        arguments = args == null ? new Bundle() : args;
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            output.putString("result", runProbe().toString());
            finish(-1, output);
        } catch (Throwable failure) {
            output.putString("error", failure.getClass().getSimpleName() + ": " + message(failure));
            finish(0, output);
        }
    }

    private JSONObject runProbe() throws Exception {
        Context context = getTargetContext();
        int uid = ordinaryUid(context);
        String method = required("method");
        String arg = arguments.getString("arg", "{}");
        String name = arguments.getString("name", "call");
        int barrierMs = bounded("barrierMs", 0, 0, 60_000);
        int repeat = bounded("repeat", 1, 1, 200);
        int intervalMs = bounded("repeatIntervalMs", 0, 0, 10_000);
        int consumeDelayMs = bounded("consumeDelayMs", 0, 0, 60_000);
        if (!name.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Invalid evidence name");

        JSONObject document = new JSONObject()
                .put("schema", "taku.panels-probe/1")
                .put("name", name)
                .put("uid", uid)
                .put("package", context.getPackageName())
                .put("requestedPermissions", new JSONArray())
                .put("method", method)
                .put("arg", arg)
                .put("barrierMs", barrierMs)
                .put("repeat", repeat)
                .put("intervalMs", intervalMs)
                .put("consumeDelayMs", consumeDelayMs);

        Bundle status = new Bundle();
        status.putString("phase", "ready");
        status.putLong("readyAtEpochMs", System.currentTimeMillis());
        sendStatus(1, status);
        if (barrierMs > 0) SystemClock.sleep(barrierMs);

        JSONArray calls = new JSONArray();
        for (int index = 0; index < repeat; index++) {
            if (index > 0 && intervalMs > 0) SystemClock.sleep(intervalMs);
            calls.put(callOnce(context, method, arg, index, consumeDelayMs));
        }
        document.put("calls", calls);

        byte[] fileBytes = document.toString().getBytes(StandardCharsets.UTF_8);
        File directory = new File(context.getFilesDir(), "panels-device");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("Cannot create evidence directory");
        File target = new File(directory, name + ".json");
        File temporary = new File(directory, name + ".json.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(fileBytes);
            stream.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new AssertionError("Cannot publish evidence file");

        int transportErrors = 0, resultUriCalls = 0, payloadBytes = 0, secondReadRejected = 0;
        String payloadSha256 = "";
        for (int index = 0; index < calls.length(); index++) {
            JSONObject call = calls.getJSONObject(index);
            if (call.has("transportError")) transportErrors++;
            if (call.has("payloadBytes")) {
                resultUriCalls++;
                payloadBytes = Math.max(payloadBytes, call.getInt("payloadBytes"));
                payloadSha256 = call.getString("payloadSha256");
            }
            JSONObject second = call.optJSONObject("secondRead");
            if (second != null && !second.optBoolean("readSucceeded")) secondReadRejected++;
        }
        return new JSONObject()
                .put("name", name)
                .put("filePath", "files/panels-device/" + name + ".json")
                .put("fileBytes", fileBytes.length)
                .put("fileSha256", sha256(fileBytes))
                .put("uid", uid)
                .put("package", context.getPackageName())
                .put("calls", calls.length())
                .put("transportErrors", transportErrors)
                .put("resultUriCalls", resultUriCalls)
                .put("payloadBytes", payloadBytes)
                .put("payloadSha256", payloadSha256)
                .put("secondReadRejected", secondReadRejected);
    }

    private JSONObject callOnce(Context context, String method, String arg, int index, int consumeDelayMs)
            throws Exception {
        JSONObject call = new JSONObject().put("index", index);
        long started = System.currentTimeMillis();
        Bundle response;
        try {
            response = context.getContentResolver().call(Uri.parse(PROVIDER), method, arg, null);
        } catch (Throwable failure) {
            return call.put("transportError", failure.getClass().getSimpleName() + ": " + message(failure))
                    .put("startedAtEpochMs", started)
                    .put("finishedAtEpochMs", System.currentTimeMillis());
        }
        long answered = System.currentTimeMillis();
        call.put("startedAtEpochMs", started).put("finishedAtEpochMs", answered);
        String outer = response == null ? null : response.getString("result");
        if (outer == null) return call.put("transportError", "Provider returned no result string");
        byte[] outerBytes = outer.getBytes(StandardCharsets.UTF_8);
        call.put("outer", outer)
                .put("outerBytes", outerBytes.length)
                .put("outerSha256", sha256(outerBytes));
        JSONObject envelope = new JSONObject(outer);
        call.put("ok", envelope.optBoolean("ok")).put("code", envelope.optString("code", ""));
        String resultUri = envelope.optString("resultUri", "");
        if (resultUri.isEmpty() || envelope.has("result")) return call;

        Uri uri = Uri.parse(resultUri);
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
            throw new AssertionError("Unexpected result URI: " + resultUri);
        if (consumeDelayMs > 0) SystemClock.sleep(consumeDelayMs);
        byte[] payload;
        try {
            payload = readAll(context, uri);
        } catch (Throwable failure) {
            return call.put("resultUri", resultUri)
                    .put("delayedRead", new JSONObject()
                            .put("readSucceeded", false)
                            .put("errorClass", failure.getClass().getSimpleName())
                            .put("message", message(failure)));
        }
        String payloadText = new String(payload, StandardCharsets.UTF_8);
        new JSONObject(payloadText);
        call.put("resultUri", resultUri)
                .put("payloadText", payloadText)
                .put("payloadBytes", payload.length)
                .put("payloadSha256", sha256(payload))
                .put("secondRead", consumeDelayMs > 0 ? null : secondRead(context, uri));
        return call;
    }

    private static JSONObject secondRead(Context context, Uri uri) throws Exception {
        JSONObject outcome = new JSONObject();
        InputStream stream = null;
        try {
            stream = context.getContentResolver().openInputStream(uri);
            if (stream == null) return outcome.put("readSucceeded", false).put("errorClass", "NullStream");
            return outcome.put("readSucceeded", true).put("bytes", readStream(stream).length);
        } catch (Throwable failure) {
            return outcome.put("readSucceeded", false)
                    .put("errorClass", failure.getClass().getSimpleName())
                    .put("message", message(failure));
        } finally {
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
        }
    }

    private static byte[] readAll(Context context, Uri uri) throws Exception {
        InputStream stream = context.getContentResolver().openInputStream(uri);
        if (stream == null) throw new AssertionError("Provider result stream is missing");
        try {
            return readStream(stream);
        } finally {
            stream.close();
        }
    }

    private static byte[] readStream(InputStream stream) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            result.write(buffer, 0, count);
            if (result.size() > MAX_PAYLOAD_BYTES) throw new AssertionError("Result stream exceeds 16 MiB");
        }
        return result.toByteArray();
    }

    private static int ordinaryUid(Context context) throws Exception {
        int uid = Process.myUid();
        ApplicationInfo info = context.getApplicationInfo();
        if (uid != info.uid) throw new AssertionError("Probe UID " + uid + " differs from its package UID " + info.uid);
        if (uid < 10000) throw new AssertionError("Probe does not run as an ordinary application UID: " + uid);
        if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) throw new AssertionError("Probe package is a system application");
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        String[] requested = packageInfo.requestedPermissions;
        if (requested != null && requested.length > 0)
            throw new AssertionError("Probe declares permissions: " + Arrays.toString(requested));
        return uid;
    }

    private String required(String key) {
        String value = arguments.getString(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        if (value.length() > 64 * 1024) throw new IllegalArgumentException(key + " is too long");
        return value;
    }

    private int bounded(String key, int fallback, int minimum, int maximum) {
        String raw = arguments.getString(key);
        if (raw == null || raw.isEmpty()) return fallback;
        int value = Integer.parseInt(raw);
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(key + " must be " + minimum + ".." + maximum);
        return value;
    }

    private static String message(Throwable failure) {
        String text = failure.getMessage();
        if (text == null) text = "";
        return text.length() > 400 ? text.substring(0, 400) : text;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
