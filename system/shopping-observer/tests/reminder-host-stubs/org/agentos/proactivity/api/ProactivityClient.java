package org.agentos.proactivity.api;

import android.content.Context;
import org.json.JSONObject;

/** Test transport only: production Binder host ownership is outside these declaration checks. */
public final class ProactivityClient implements AutoCloseable {
    public interface Listener {
        void onConnected(); void onMessage(JSONObject message); default void onDisconnected() {}
    }
    public static ProactivityClient latest;
    public ObserverSpec registered;
    private final Listener listener;
    public ProactivityClient(Context context,Listener listener){this.listener=listener;latest=this;}
    public void connect(){listener.onConnected();}
    public String register(ObserverSpec spec){registered=spec;return "request";}
    public String emit(String id,String event,String type,int revision){return "event";}
    public void receive(JSONObject message){listener.onMessage(message);}
    @Override public void close() {}
}
