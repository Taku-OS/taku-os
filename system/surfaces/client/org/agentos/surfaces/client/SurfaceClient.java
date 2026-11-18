package org.agentos.surfaces.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.List;
import org.agentos.surfaces.SurfaceCodec;
import org.agentos.surfaces.SurfaceSpec;

/** Optional Android client. All methods and callbacks run on the main thread. */
public final class SurfaceClient implements AutoCloseable {
    public record ActionEvent(String id,long revision,String actionId,SurfaceSpec.ArtifactRef artifact) {}
    public interface Listener {
        void onConnected();
        void onEvent(String type, String id, long revision, String value);
        /** Emitted in addition to the generic event; handle an action in one callback only. */
        default void onAction(ActionEvent event) {}
    }
    private final Context context; private final Listener listener;
    private Messenger service; private boolean bound;
    private final Messenger events;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service=new Messenger(binder); listener.onConnected();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service=null; listener.onEvent("disconnected","",0,"");
        }
        @Override public void onBindingDied(ComponentName name) {
            close(); listener.onEvent("disconnected","",0,"binding_died");
        }
        @Override public void onNullBinding(ComponentName name) {
            close(); listener.onEvent("disconnected","",0,"null_binding");
        }
    };
    public SurfaceClient(Context context, Listener listener) {
        this.context=context.getApplicationContext(); this.listener=listener;
        events=new Messenger(new Handler(Looper.getMainLooper(), message -> {
            Bundle data=message.getData();
            listener.onEvent(data.getString("type","error"),data.getString("id",""),
                    data.getLong("revision"),data.getString("value",""));
            if ("action".equals(data.getString("type"))) {
                SurfaceSpec.ArtifactRef artifact=data.containsKey("artifactRef")?
                        new SurfaceSpec.ArtifactRef(data.getString("artifactRef"),data.getString("artifactMediaType"),data.getString("artifactTitle")):null;
                listener.onAction(new ActionEvent(data.getString("id"),data.getLong("revision"),data.getString("value"),artifact));
            }
            return true;
        }));
    }
    public void connect() {
        mainThread(); if (bound) return;
        Intent intent=new Intent().setComponent(new ComponentName("org.agentos.surfaces","org.agentos.surfaces.SurfaceService"));
        bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
        if (!bound) throw new IllegalStateException("System surfaces unavailable");
    }
    public void publish(String json) throws RemoteException { send(1,json); }
    public void publish(SurfaceSpec spec) throws RemoteException { publish(SurfaceCodec.encode(spec)); }
    public void showHint(String id,long revision,String title,String summary,SurfaceSpec.Action action) throws RemoteException {
        publish(SurfaceSpec.hint(id,revision,title,summary,action));
    }
    public void showChoice(String id,long revision,String title,String summary,List<SurfaceSpec.Action> options) throws RemoteException {
        publish(SurfaceSpec.choice(id,revision,title,summary,options));
    }
    public void showResult(String id,long revision,String title,String summary,SurfaceSpec.ArtifactRef artifact) throws RemoteException {
        publish(SurfaceSpec.result(id,revision,title,summary,artifact));
    }
    public void dismiss(String id) throws RemoteException { send(2,id); }
    private void send(int operation, String payload) throws RemoteException {
        mainThread(); if (service == null) throw new IllegalStateException("Connect before publishing");
        Message message=Message.obtain(null,operation); message.replyTo=events;
        Bundle data=new Bundle(); data.putString("payload",payload); message.setData(data); service.send(message);
    }
    @Override public void close() {
        mainThread();
        if (service != null) try { send(3,""); } catch (RemoteException ignored) {}
        if (bound) context.unbindService(connection);
        bound=false; service=null;
    }
    private static void mainThread() {
        if (Looper.myLooper()!=Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }
}
