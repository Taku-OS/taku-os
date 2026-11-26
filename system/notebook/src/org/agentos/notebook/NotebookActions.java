package org.agentos.notebook;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;

final class NotebookActions {
    static void open(Context context, String action, SystemScreenshots.Capture capture, ScreenSource source,
                     String title) {
        Intent intent = switch (action) {
            case "send" -> share(capture, source, title);
            case "edit" -> edit(capture);
            case "preview" -> new Intent(context, NotebookActionActivity.class)
                    .setData(capture.uri()).putExtra("title", title).putExtra("source", source.label());
            default -> throw new IllegalArgumentException("Unknown Notebook action");
        };
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static Intent share(SystemScreenshots.Capture capture, ScreenSource source, String title) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(capture.mime() == null ? "image/png" : capture.mime())
                .putExtra(Intent.EXTRA_STREAM, capture.uri())
                .putExtra(Intent.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("Screenshot", capture.uri()));
        if (source.url() != null) send.putExtra(Intent.EXTRA_TEXT, source.url());
        return Intent.createChooser(send, "Send " + title + " to…");
    }

    private static Intent edit(SystemScreenshots.Capture capture) {
        Intent edit = new Intent(Intent.ACTION_EDIT)
                .setDataAndType(capture.uri(), capture.mime() == null ? "image/png" : capture.mime())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        edit.setClipData(ClipData.newRawUri("Screenshot", capture.uri()));
        return edit;
    }
}
