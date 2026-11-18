package org.agentos.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/** Bounded async image preview. A failed/host-only image always retains a readable link. */
final class NativeMarkdownImage extends LinearLayout {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "taku-markdown-image"); thread.setDaemon(true); return thread;
    });
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final ImageView image;
    private final TextView caption;
    private String target = "", label = "";
    private boolean loading;

    NativeMarkdownImage(Context context, DrawerTheme theme) {
        super(context);
        AgentSettingsUi ui = new AgentSettingsUi(context, theme);
        setOrientation(VERTICAL);
        setBackground(theme.rounded(context, theme.raised, 12, true));
        image = new ImageView(context);
        image.setAdjustViewBounds(true);
        image.setMaxHeight(ui.dp(320));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(GONE);
        addView(image, new LayoutParams(-1, -2));
        caption = ui.text("", 13, false);
        caption.setMinHeight(ui.dp(44));
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        addView(caption);
        setFocusable(true);
        setOnClickListener(view -> preview(view, target));
        setOnLongClickListener(view -> { NativeMarkdownLinks.copy(context, target); return true; });
    }

    void bind(String target, String label) {
        if (this.target.equals(target) && this.label.equals(label)) return;
        this.target = target; this.label = label;
        image.setImageDrawable(null); image.setVisibility(GONE);
        caption.setText(label + " · Loading");
        setContentDescription(label);
        loading = false;
        if (isAttachedToWindow()) load();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); load(); }

    private void load() {
        Bitmap cached = CACHE.get(target);
        if (cached != null) { display(cached); return; }
        if (loading || target.isEmpty()) return;
        loading = true;
        String request = target;
        WeakReference<NativeMarkdownImage> reference = new WeakReference<>(this);
        IO.execute(() -> {
            Bitmap result = null;
            try { result = decode(read(request)); if (result != null) CACHE.put(request, result); }
            catch (Exception ignored) { /* The caption is the visible fallback, with the original target. */ }
            NativeMarkdownImage view = reference.get();
            if (view == null) return;
            Bitmap bitmap = result;
            view.post(() -> {
                if (!view.target.equals(request)) return;
                view.loading = false;
                if (bitmap != null) view.display(bitmap);
                else view.caption.setText(view.label + " · Preview unavailable; tap to view address");
            });
        });
    }

    private void display(Bitmap bitmap) {
        image.setImageBitmap(bitmap); image.setVisibility(VISIBLE);
        caption.setText(label);
    }

    static Bitmap decode(byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Not a bitmap image");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static byte[] read(String target) throws IOException {
        if (target.startsWith("data:image/") && target.contains(";base64,")) {
            if (target.length() > MAX_BYTES) throw new IOException("Image too large");
            return Base64.decode(target.substring(target.indexOf(',') + 1), Base64.DEFAULT);
        }
        URL url = new URL(target);
        for (int redirect = 0; redirect < 5; redirect++) {
            if (!url.getProtocol().equals("https")) throw new IOException("Preview requires HTTPS");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect target");
                    url = new URL(url, location); continue;
                }
                if (code != 200 || connection.getContentLengthLong() > MAX_BYTES) throw new IOException("Preview unavailable");
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (out.size() + count > MAX_BYTES) throw new IOException("Image too large");
                        out.write(buffer, 0, count);
                    }
                    return out.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many redirects");
    }

    static void preview(View anchor, String target) {
        Bitmap bitmap = CACHE.get(target);
        if (bitmap == null) { NativeMarkdownLinks.open(anchor, target); return; }
        ImageView image = new ImageView(anchor.getContext());
        image.setAdjustViewBounds(true); image.setImageBitmap(bitmap);
        image.setContentDescription("Image preview");
        NativeDialogs.show(anchor, new AlertDialog.Builder(anchor.getContext()).setView(image)
                .setPositiveButton("Close", null).setNeutralButton("Copy address", (dialog, which) -> NativeMarkdownLinks.copy(anchor.getContext(), target)));
    }
}
