package org.agentos.lab;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Real Notebook records; no independent bookmark store or model-authored reference metadata. */
final class NotebookBridge {
    static final String SCHEMA = "taku.notebook.references/1";
    private static final int MAX_REFERENCE_GRANTS = 3;
    private record ReferenceGrant(int user, String id) {}
    private static final Set<ReferenceGrant> granted = new HashSet<>();

    private static final class UserChanged extends IOException {
        UserChanged() { super("ANDROID_USER_CHANGED"); }
    }

    static void requireCurrentUser(int user) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (PhoneAppCatalog.currentUser() != user) throw new UserChanged();
    }

    static JSONObject provider(int user, String method, JSONObject args) throws Exception {
        requireCurrentUser(user);
        String output = TaskBroker.command("/system/bin/timeout", "10", "/system/bin/content", "call",
                "--user", Integer.toString(user), "--uri", "content://org.agentos.notebook",
                "--method", method, "--arg", args.toString()).trim();
        requireCurrentUser(user);
        String prefix = "Result: Bundle[{result=";
        if (!output.startsWith(prefix) || !output.endsWith("}]")) throw new IOException("NOTEBOOK_UNAVAILABLE");
        JSONObject response = new JSONObject(output.substring(prefix.length(), output.length() - 2));
        if (!response.optBoolean("ok")) throw new IOException(response.optString("error", "NOTEBOOK_UNAVAILABLE"));
        return response.getJSONObject("result");
    }

    static JSONObject recall(JSONObject args) throws Exception {
        for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) {
            if (!Set.of("query", "queryAliases", "category", "limit").contains(keys.next())) {
                throw new IOException("INVALID_ARGUMENT");
            }
        }
        JSONObject query = new JSONObject(args.toString());
        int limit = query.optInt("limit", 3);
        if (limit < 1 || limit > 50) throw new IOException("INVALID_PAGE");
        int user = PhoneAppCatalog.currentUser();
        boolean filtered = query.has("query") || query.has("queryAliases") || query.has("category");
        query.put("limit", limit);
        JSONObject result = filtered ? provider(user, "recall", query)
                : provider(user, "queryEntries", query.put("sort", "newest"));
        JSONArray source = result.getJSONArray("items"), items = new JSONArray();
        for (int i = 0; i < source.length() && items.length() < limit; i++) {
            JSONObject entry = source.getJSONObject(i);
            String id = entry.getString("id");
            if (i < MAX_REFERENCE_GRANTS) {
                try {
                    // The same provider re-reads this ID and grants only its immutable image to the UI.
                    entry = provider(user, "reference", new JSONObject().put("id", id));
                    synchronized (granted) { granted.add(new ReferenceGrant(user, id)); }
                } catch (UserChanged changed) {
                    throw changed;
                } catch (IOException | JSONException unavailable) {
                    // The previously fetched entry still describes the saved reference.
                }
            }
            items.put(entry);
        }
        JSONObject response = PhoneNativeTools.text(new JSONObject().put("schema", SCHEMA)
                .put("androidUserId", user).put("items", items).toString());
        if (items.length() > 0) {
            try {
                response.getJSONArray("contentItems").put(new JSONObject().put("type", "inputImage")
                        .put("imageUrl", imagePreview(user, items.getJSONObject(0))));
            } catch (UserChanged changed) {
                throw changed;
            } catch (IOException unavailable) {
                // The text receipt still contains the saved metadata.
            }
        }
        requireCurrentUser(user);
        return response;
    }

    private static String imagePreview(int user, JSONObject reference) throws Exception {
        String id = reference.getString("id");
        String uri = "content://org.agentos.notebook/images/" + id;
        if (!id.matches("[a-f0-9]{64}") || !uri.equals(reference.optString("imageUri"))) {
            throw new IOException("NOTEBOOK_IMAGE_REFERENCE_INVALID");
        }
        requireCurrentUser(user);
        var command = new ProcessBuilder("/system/bin/timeout", "8", "/system/bin/content", "read",
                "--user", Integer.toString(user), "--uri", uri)
                .redirectError(new java.io.File("/dev/null"));
        Process process = command.start();
        byte[] data;
        try (var input = process.getInputStream()) {
            data = input.readNBytes(6_000_001);
            if (data.length > 6_000_000 || !process.waitFor(9, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("NOTEBOOK_IMAGE_READ_FAILED");
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        requireCurrentUser(user);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        if (!checksum.equals(reference.optString("sha256"))) throw new IOException("NOTEBOOK_IMAGE_CHANGED");
        var options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        int edge = Math.max(options.outWidth, options.outHeight);
        if (options.outWidth < 1 || options.outHeight < 1 || edge > 8192) throw new IOException("NOTEBOOK_IMAGE_INVALID");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (edge / options.inSampleSize > 1500) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("NOTEBOOK_IMAGE_INVALID");
        try (var jpeg = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpeg) || jpeg.size() > 250_000) {
                throw new IOException("NOTEBOOK_IMAGE_PREVIEW_TOO_LARGE");
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
        } finally {
            bitmap.recycle();
        }
    }

    /** Re-establish image grants for native history on the background refresh worker. */
    static void refreshReferenceGrants(JSONArray turns, boolean newestFirst) {
        Integer user = null;
        int remaining = 12;
        int count = turns.length();
        for (int t = 0; t < count && remaining > 0; t++) {
            JSONObject turn = turns.optJSONObject(newestFirst ? t : count - 1 - t);
            JSONArray items = turn == null ? null : turn.optJSONArray("items");
            if (items == null) continue;
            for (int n = items.length() - 1; n >= 0 && remaining > 0; n--) {
                JSONObject item = items.optJSONObject(n);
                if (item == null || !"dynamicToolCall".equals(item.optString("type"))
                        || !"taku_phone".equals(item.optString("namespace"))
                        || !"notebook_recall".equals(item.optString("tool")) || !item.optBoolean("success")) continue;
                JSONArray content = item.optJSONArray("contentItems");
                if (content == null) continue;
                for (int c = 0; c < content.length() && remaining > 0; c++) try {
                    JSONObject block = content.getJSONObject(c);
                    if (!"inputText".equals(block.optString("type"))) continue;
                    JSONObject payload = new JSONObject(block.getString("text"));
                    if (!SCHEMA.equals(payload.optString("schema"))) continue;
                    JSONArray refs = payload.getJSONArray("items");
                    if (refs.length() == 0) continue;
                    if (user == null) user = PhoneAppCatalog.currentUser();
                    boolean hasOwner = payload.has("androidUserId");
                    if (hasOwner && payload.optInt("androidUserId", -1) != user) continue;
                    for (int r = 0; r < refs.length() && remaining > 0; r++) {
                        JSONObject saved = refs.getJSONObject(r);
                        String id = saved.getString("id");
                        ReferenceGrant grant = new ReferenceGrant(user, id);
                        synchronized (granted) { if (granted.contains(grant)) continue; }
                        remaining--;
                        JSONObject reference = new JSONObject().put("id", id);
                        // Match an older receipt's original bytes before renewing its grant.
                        if (!hasOwner) {
                            String hash = saved.optString("sha256");
                            if (!hash.matches("[a-f0-9]{64}")
                                    || !hash.equals(provider(user, "get", reference).optString("sha256"))) continue;
                        }
                        provider(user, "reference", reference);
                        synchronized (granted) { granted.add(grant); }
                    }
                } catch (UserChanged changed) {
                    return;
                } catch (Exception unavailable) { /* Deleted/unavailable originals remain unavailable in the UI. */ }
            }
        }
    }
}
