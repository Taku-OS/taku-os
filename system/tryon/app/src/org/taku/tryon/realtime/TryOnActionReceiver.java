// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.content.*;
/** Notification PendingIntent only; no externally exported background camera start. */
public final class TryOnActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if (TryOnResultWindow.ACTION.equals(intent.getAction())) {
            TryOnResultWindow.act(context, intent.getStringExtra("resultToken"), intent.getStringExtra("resultAction"));
        } else if("stop".equals(intent.getAction()))TryOnController.stop(context);
    }
}
