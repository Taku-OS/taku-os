package org.agentos.assistant;

import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.Toast;
import java.util.Locale;

/** Dispatch links deliberately; host workspace paths remain copyable on the Android client. */
final class NativeMarkdownLinks {
    static final class Link extends URLSpan {
        final boolean image;
        Link(String target, boolean image) { super(target); this.image = image; }
        @Override public void onClick(View view) {
            if (image) NativeMarkdownImage.preview(view, getURL());
            else open(view, getURL());
        }
    }

    static boolean external(String value) {
        String scheme = Uri.parse(value.trim()).getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")
                || scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("tel"));
    }

    static void open(View anchor, String target) {
        Context context = anchor.getContext();
        if (external(target)) {
            try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (ActivityNotFoundException error) { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show(); }
            return;
        }
        String value = target.trim().toLowerCase(Locale.ROOT);
        String message = value.startsWith("/") || value.startsWith("file:") || !value.contains(":")
                ? "Workspace path\n" + target : "Link address\n" + target;
        // A host path is not an Android file URI. Keep the exact target rather than launching a broken intent.
        NativeDialogs.show(anchor, new AlertDialog.Builder(context).setMessage(message).setPositiveButton("Copy address", (dialog, which) -> copy(context, target))
                .setNegativeButton("Close", null));
    }

    static void copy(Context context, String text) {
        ((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Taku", text));
    }
}
