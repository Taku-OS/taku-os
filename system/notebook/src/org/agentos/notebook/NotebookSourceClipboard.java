package org.agentos.notebook;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

final class NotebookSourceClipboard {
    private NotebookSourceClipboard() {}

    static void copy(Context context, Uri source) {
        context.getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("Notebook source link", source.toString()));
    }
}
