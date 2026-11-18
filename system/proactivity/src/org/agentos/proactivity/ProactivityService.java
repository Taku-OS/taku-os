package org.agentos.proactivity;

import android.app.Service;
import android.content.Intent;
import android.os.*;

/** Public, identity-checked bound API. It does not grant authority merely on bind. */
public final class ProactivityService extends Service {
    private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(),message->{
        HostRuntime.get(this).receive(message);return true;
    }));
    @Override public IBinder onBind(Intent intent){HostRuntime.get(this);return endpoint.getBinder();}
    @Override public void onDestroy(){HostRuntime.release();super.onDestroy();}
}
