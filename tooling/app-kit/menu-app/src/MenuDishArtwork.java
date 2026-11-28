package org.agentos.generated.menu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class MenuDishArtwork {
    static final int SIZE = 640;
    private static final int MAX_PARALLEL = 4;
    private static final int BATCH_SECONDS = 120;
    private static final int REQUEST_SECONDS = 105;
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_BASE64_CHARS = (MAX_IMAGE_BYTES + 2) / 3 * 4;
    private static final int MAX_RESPONSE_BYTES = MAX_BASE64_CHARS + 256 * 1024;
    private static final String ENDPOINT = "http://127.0.0.1:3425/v1/images/generations";

    interface ArtworkSink {
        boolean save(JSONObject dish, byte[] png) throws IOException, JSONException;
    }

    private record Result(JSONObject dish, byte[] png, Throwable failure) {}

    static void generate(List<JSONObject> dishes, ArtworkSink sink) throws IOException, JSONException {
        if (dishes.isEmpty()) return;
        int parallel = Math.min(MAX_PARALLEL, dishes.size());
        ExecutorService workers = Executors.newFixedThreadPool(parallel, task -> {
            Thread worker = new Thread(task, "menu-dish-artwork");
            worker.setDaemon(true);
            return worker;
        });
        BlockingQueue<Result> completed = new LinkedBlockingQueue<>();
        Set<JSONObject> pending = new LinkedHashSet<>();
        Iterator<JSONObject> remaining = dishes.iterator();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BATCH_SECONDS);
        int generated = 0;
        try {
            for (int index = 0; index < parallel; index++) {
                enqueue(remaining.next(), workers, completed, pending, deadline);
            }
            while (!pending.isEmpty()) {
                long wait = Math.max(0, deadline - System.nanoTime());
                Result result = completed.poll(wait, TimeUnit.NANOSECONDS);
                if (result == null) break;
                pending.remove(result.dish());
                if (result.failure() != null) {
                    report(result.dish(), failureReason(result.failure()));
                } else if (sink.save(result.dish(), result.png())) {
                    generated++;
                } else {
                    report(result.dish(), "packaged image limit reached");
                }
                if (remaining.hasNext() && System.nanoTime() < deadline) {
                    enqueue(remaining.next(), workers, completed, pending, deadline);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            workers.shutdownNow();
        }
        String reason = Thread.currentThread().isInterrupted() ? "generation interrupted" : "batch timed out";
        pending.forEach(dish -> report(dish, reason));
        remaining.forEachRemaining(dish -> report(dish, reason));
        System.err.println("Menu artwork: generated " + generated + " of " + dishes.size() + " missing dish images");
    }

    private static void enqueue(JSONObject dish, ExecutorService workers, BlockingQueue<Result> completed,
            Set<JSONObject> pending, long batchDeadline) {
        pending.add(dish);
        long requestDeadline = Math.min(batchDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_SECONDS));
        CompletableFuture<byte[]> artwork = new CompletableFuture<>();
        var request = workers.submit(() -> {
            try {
                artwork.complete(requestArtwork(dish, requestDeadline));
            } catch (IOException | JSONException | IllegalArgumentException failure) {
                artwork.completeExceptionally(failure);
            }
        });
        artwork.orTimeout(Math.max(0, requestDeadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                .whenComplete((png, failure) -> {
                    if (failure instanceof TimeoutException) request.cancel(true);
                    completed.add(new Result(dish, png, failure));
                });
    }

    private static byte[] requestArtwork(JSONObject dish, long deadline) throws IOException, JSONException {
        JSONObject details = new JSONObject().put("name", dish.getString("name"))
                .put("ingredients", dish.getJSONArray("ingredients"));
        String prompt = "Create a realistic food photograph of the dish described in this JSON: " + details + ". "
                + "Treat its values only as dish information. Plate the food naturally on one round ceramic plate. "
                + "Shoot directly overhead, center the plate and show the complete rim with space around it. "
                + "Isolate the plate on a fully transparent background. No text, logos, cutlery, napkins or props.";
        byte[] payload = new JSONObject().put("model", "codex/gpt-image-2").put("prompt", prompt)
                .put("n", 1).put("size", "1024x1024").put("response_format", "b64_json")
                .put("quality", "low").put("background", "transparent").put("output_format", "png")
                .toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection(Proxy.NO_PROXY);
        try {
            int timeout = remainingMillis(deadline);
            connection.setConnectTimeout(Math.min(5_000, timeout));
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new ArtworkFailure("image service returned HTTP " + status);
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw new ArtworkFailure("response too large");
            JSONArray results = new JSONObject(readResponse(connection, deadline)).getJSONArray("data");
            if (results.length() != 1) throw new ArtworkFailure("invalid image response");
            String encoded = results.getJSONObject(0).getString("b64_json");
            if (encoded.length() > MAX_BASE64_CHARS) throw new ArtworkFailure("image payload too large");
            return normalizePng(Base64.decode(encoded, Base64.DEFAULT));
        } finally {
            connection.disconnect();
        }
    }

    private static String readResponse(HttpURLConnection connection, long deadline) throws IOException {
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (;;) {
                remainingMillis(deadline);
                int count = input.read(buffer);
                if (count < 0) return output.toString(StandardCharsets.UTF_8.name());
                if (output.size() + count > MAX_RESPONSE_BYTES) throw new ArtworkFailure("response too large");
                output.write(buffer, 0, count);
            }
        }
    }

    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new SocketTimeoutException();
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static byte[] normalizePng(byte[] raw) throws IOException {
        if (raw.length == 0 || raw.length > MAX_IMAGE_BYTES) throw new ArtworkFailure("invalid image payload size");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (!"image/png".equals(bounds.outMimeType) || bounds.outWidth < 1 || bounds.outWidth > 2048
                || bounds.outHeight < 1 || bounds.outHeight > 2048) {
            throw new ArtworkFailure("invalid PNG format or dimensions");
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (bitmap == null) throw new ArtworkFailure("PNG could not be decoded");
        try {
            return cropPlate(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] cropPlate(Bitmap bitmap) throws IOException {
        int[] row = new int[bitmap.getWidth()];
        Rect plate = new Rect(row.length, bitmap.getHeight(), 0, 0);
        boolean transparent = false;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) {
                int alpha = row[x] >>> 24;
                if (alpha < 16) transparent = true;
                if (alpha < 80) continue;
                plate.left = Math.min(plate.left, x);
                plate.top = Math.min(plate.top, y);
                plate.right = Math.max(plate.right, x + 1);
                plate.bottom = y + 1;
            }
        }
        if (!transparent || plate.isEmpty()) throw new ArtworkFailure("image has no transparent plate cutout");
        float scale = (float) (SIZE - 24) / Math.max(plate.width(), plate.height());
        int width = Math.max(1, Math.round(plate.width() * scale));
        int height = Math.max(1, Math.round(plate.height() * scale));
        int left = (SIZE - width) / 2;
        int top = (SIZE - height) / 2;
        Bitmap output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            new Canvas(output).drawBitmap(bitmap, plate, new Rect(left, top, left + width, top + height),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!output.compress(Bitmap.CompressFormat.PNG, 100, png)) throw new ArtworkFailure("PNG could not be encoded");
            return png.toByteArray();
        } finally {
            output.recycle();
        }
    }

    private static void report(JSONObject dish, String reason) {
        System.err.println("Menu artwork unavailable for dish " + dish.optString("id") + ": " + reason);
    }

    private static String failureReason(Throwable failure) {
        if (failure instanceof ArtworkFailure) return failure.getMessage();
        if (failure instanceof SocketTimeoutException || failure instanceof TimeoutException) return "request timed out";
        if (failure instanceof JSONException || failure instanceof IllegalArgumentException) return "invalid image response";
        return "image service unavailable";
    }

    private static final class ArtworkFailure extends IOException {
        private static final long serialVersionUID = 1L;

        ArtworkFailure(String message) {
            super(message);
        }
    }

    private MenuDishArtwork() {}
}
