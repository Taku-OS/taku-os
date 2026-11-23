// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private, immutable JPEG handoff. Tokens and expiry do not survive process death. */
final class ReferenceStore {
    static final String AUTHORITY = "org.agentos.shopping.reference";
    static final long TTL_MS = 120_000;
    static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4;
    private static final Map<String, Entry> entries = new LinkedHashMap<>();
    private static Handler cleanup;

    record Entry(File file, String host, String product, long expiresAt) {}
    private ReferenceStore() {}

    static synchronized String put(Context context, byte[] jpeg, LivePage page) throws IOException {
        if (page == null || jpeg == null || jpeg.length < 4 || jpeg.length > MAX_BYTES
                || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216
                || (jpeg[jpeg.length - 2] & 255) != 255 || (jpeg[jpeg.length - 1] & 255) != 217)
            throw new IOException("Invalid JPEG reference");
        Context app = context.getApplicationContext();
        purge(app);
        File directory = directory(app);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Reference cache unavailable");
        String token = UUID.randomUUID().toString();
        File file = new File(directory, token + ".jpg");
        File temporary = File.createTempFile("capture-", ".part", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(jpeg);
                output.getFD().sync();
            }
            if (!temporary.renameTo(file)) throw new IOException("Cannot freeze reference");
            while (entries.size() >= MAX_ENTRIES) {
                String first = entries.keySet().iterator().next();
                drop(app, first, entries.remove(first));
            }
            entries.put(token, new Entry(file, page.hostName(), page.productTitle(),
                SystemClock.elapsedRealtime() + TTL_MS));
            if (cleanup == null) cleanup = new Handler(Looper.getMainLooper());
            cleanup.postDelayed(() -> purge(app), TTL_MS + 1);
            return token;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    static synchronized Entry get(Context context, String token) {
        if (!validToken(token)) return null;
        purge(context.getApplicationContext());
        Entry entry = entries.get(token);
        if (entry == null) return null;
        if (!entry.file().isFile() || entry.file().length() == 0 || entry.file().length() > MAX_BYTES) {
            drop(context, token, entries.remove(token));
            return null;
        }
        return entry;
    }

    static Uri uri(String token) {
        if (!validToken(token)) throw new IllegalArgumentException("Invalid reference token");
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).build();
    }

    static synchronized void discard(Context context, String token) {
        if (validToken(token)) drop(context.getApplicationContext(), token, entries.remove(token));
    }

    static synchronized void purge(Context context) {
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> item = iterator.next();
            if (item.getValue().expiresAt() <= now) {
                iterator.remove();
                drop(context, item.getKey(), item.getValue());
            }
        }
        // A restarted process cannot redeem old tokens, so remove its orphaned cache files too.
        Set<String> kept = new HashSet<>();
        for (Entry entry : entries.values()) kept.add(entry.file().getName());
        File[] files = directory(context).listFiles();
        if (files != null) for (File file : files) if (!kept.contains(file.getName())) {
            String name = file.getName();
            if (name.endsWith(".jpg")) {
                String token = name.substring(0, name.length() - 4);
                if (validToken(token)) revoke(context, token);
            }
            file.delete();
        }
    }

    static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    private static File directory(Context context) { return new File(context.getCacheDir(), "live-references"); }
    private static void drop(Context context, String token, Entry entry) {
        revoke(context, token);
        if (entry != null) entry.file().delete(); // An already-open descriptor remains valid after unlink.
    }
    private static void revoke(Context context, String token) {
        try { context.revokeUriPermission(uri(token), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (RuntimeException unavailable) { }
    }
}
