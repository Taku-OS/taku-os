package org.agentos.notebook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Saving a screenshot has no destination activity and must leave the current app in place. */
public final class NotebookScreenshotSaveReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && "save".equals(intent.getAction())) {
            NotebookScreenshotService.saveFromNotification(intent.getIdentifier());
        }
    }
}
