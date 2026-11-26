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
import org.json.JSONObject;

/** Real Notebook records; no independent bookmark store or model-authored reference metadata. */
final class NotebookBridge {
    static final String SCHEMA = "taku.notebook.references/1";
    private static final Set<String> granted = new HashSet<>();

    static JSONObject provider(String method, JSONObject args) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String output = TaskBroker.command("/system/bin/timeout", "10", "/system/bin/content", "call", "--user", "0",
                "--uri", "content://org.agentos.notebook", "--method", method, "--arg", args.toString()).trim();
        String prefix = "Result: Bundle[{result=";
        if (!output.startsWith(prefix) || !output.endsWith("}]")) throw new IOException("NOTEBOOK_UNAVAILABLE");
        JSONObject response = new JSONObject(output.substring(prefix.length(), output.length()-2));
        if (!response.optBoolean("ok")) throw new IOException(response.optString("error", "NOTEBOOK_UNAVAILABLE"));
        return response.getJSONObject("result");
    }

    static JSONObject recall(JSONObject args) throws Exception {
        for (java.util.Iterator<String> keys=args.keys();keys.hasNext();) if (!Set.of("query", "queryAliases", "category", "limit").contains(keys.next()))
            throw new IOException("INVALID_ARGUMENT");
        JSONObject query = new JSONObject(args.toString());
        int limit = query.optInt("limit", 3);
        if (limit < 1 || limit > 3) throw new IOException("INVALID_PAGE");
        boolean filtered = query.has("query") || query.has("queryAliases") || query.has("category");
        query.put("limit", limit);
        JSONObject result = filtered ? provider("recall", query)
                : provider("queryEntries", query.put("sort", "newest"));
        JSONArray source = result.getJSONArray("items"), items = new JSONArray();
        for (int i=0; i<source.length() && items.length()<limit; i++) {
            String id = source.getJSONObject(i).getString("id");
            // The same provider re-reads this ID and grants only its immutable image to the UI.
            JSONObject reference = provider("reference", new JSONObject().put("id", id));
            items.put(reference);
            synchronized (granted) { granted.add(id); }
        }
        JSONObject response = PhoneNativeTools.text(new JSONObject().put("schema", SCHEMA).put("items", items).toString());
        if (items.length() > 0) {
            try {
                response.getJSONArray("contentItems").put(new JSONObject().put("type", "inputImage")
                        .put("imageUrl", imagePreview(items.getJSONObject(0))));
            } catch (IOException unavailable) {
                // The exact saved reference and its URI remain available to the app and to the user.
            }
        }
        return response;
    }

    private static String imagePreview(JSONObject reference) throws Exception {
        String id = reference.getString("id");
        String uri = "content://org.agentos.notebook/images/" + id;
        if (!id.matches("[a-f0-9]{64}") || !uri.equals(reference.optString("imageUri"))) {
            throw new IOException("NOTEBOOK_IMAGE_REFERENCE_INVALID");
        }
        var command = new ProcessBuilder("/system/bin/timeout", "8", "/system/bin/content", "read", "--user", "0", "--uri", uri)
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

    static JSONObject open(JSONObject args) throws Exception {
        if (args.length()!=2 || !(args.opt("id") instanceof String) || !(args.opt("destination") instanceof String))
            throw new IOException("INVALID_ARGUMENT");
        String method = switch (args.getString("destination")) {
            case "detail" -> "open";
            case "source" -> "openSource";
            default -> throw new IOException("INVALID_ARGUMENT");
        };
        return PhoneNativeTools.text(provider(method,new JSONObject().put("id",args.getString("id"))).toString());
    }

    /** Re-establish image grants when an actual native history is reopened after a reboot. */
    static void refreshReferenceGrants(JSONObject thread) {
        JSONArray turns=thread.optJSONArray("turns"); if(turns==null)return;
        int remaining=12;
        for(int t=turns.length()-1;t>=0&&remaining>0;t--) {
            JSONObject turn=turns.optJSONObject(t);
            JSONArray items=turn==null?null:turn.optJSONArray("items");
            if(items==null)continue;
            for(int n=items.length()-1;n>=0&&remaining>0;n--) {
                JSONObject item=items.optJSONObject(n);
                if(item==null || !"dynamicToolCall".equals(item.optString("type"))
                        || !"taku_phone".equals(item.optString("namespace"))
                        || !"notebook_recall".equals(item.optString("tool")) || !item.optBoolean("success"))continue;
                JSONArray content=item.optJSONArray("contentItems");if(content==null)continue;
                for(int c=0;c<content.length()&&remaining>0;c++) try {
                    JSONObject block=content.getJSONObject(c);if(!"inputText".equals(block.optString("type")))continue;
                    JSONObject payload=new JSONObject(block.getString("text"));if(!SCHEMA.equals(payload.optString("schema")))continue;
                    JSONArray refs=payload.getJSONArray("items");
                    for(int r=0;r<refs.length()&&remaining>0;r++) {
                        String id=refs.getJSONObject(r).getString("id");
                        synchronized(granted){if(granted.contains(id))continue;}
                        remaining--;provider("reference",new JSONObject().put("id",id));
                        synchronized(granted){granted.add(id);}
                    }
                } catch(Exception unavailable) { /* Deleted/unavailable originals remain unavailable in the UI. */ }
            }
        }
    }
}
