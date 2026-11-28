package org.agentos.notebook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class NotebookArrivalReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ("org.agentos.environment.arrival.AVAILABLE".equals(intent.getAction())) {
            NotebookArrivalJob.enqueue(context);
        }
    }
}
