package org.agentos.surfaces;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bound OS display host. Content expires and is never persisted or executed. */
public final class SurfaceService extends Service {
    private final SurfaceStore store=new SurfaceStore();
    private final Map<SurfaceStore.Key,Messenger> callbacks=new HashMap<>();
    private final Map<IBinder,IBinder.DeathRecipient> connections=new HashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(),this::receive));
    private SurfaceRenderer window; private SurfaceVisibility visibility; private SurfaceStore.Key pendingPreferred;
    private final Runnable expiration=this::refresh;
    private final BroadcastReceiver screen=new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        PowerManager power=getSystemService(PowerManager.class);
        KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        visibility=new SurfaceVisibility(power::isInteractive,keyguard::isDeviceLocked);
        window=new SurfaceWindow(this,new SurfaceRenderer.Listener() {
            @Override public void onAction(SurfaceStore.Key key,long revision,String action) {
                expire();
                if (store.select(key,revision,action)) {
                    SurfaceStore.Entry entry=store.get(key);
                    send(callbacks.get(key),"action",key.id(),revision,action,entry.spec.artifact());
                }
                refresh();
            }
            @Override public void onDismiss(SurfaceStore.Key key) { remove(key,"dismissed"); refresh(); }
        });
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen,filter,Context.RECEIVER_NOT_EXPORTED);
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    private boolean receive(Message message) {
        if (message.replyTo==null || message.sendingUid<0) return true;
        Messenger reply=message.replyTo; String id=""; long revision=0;
        try {
            expire(); String payload=message.getData().getString("payload","");
            if (message.what==1) {
                SurfaceSpec spec=SurfaceCodec.parse(payload); id=spec.id(); revision=spec.revision();
                if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("System overlay permission unavailable");
                IBinder binder=reply.getBinder();
                if (!connections.containsKey(binder)) {
                    IBinder.DeathRecipient death=() -> handler.post(() -> removeConnection(binder));
                    binder.linkToDeath(death,0); connections.put(binder,death);
                }
                SurfaceStore.Entry entry=store.publish(message.sendingUid,spec,SystemClock.elapsedRealtime());
                callbacks.put(entry.key,reply); refresh(entry.key);
                if (store.get(entry.key)!=null) send(reply,"published",id,revision,"");
            } else if (message.what==2) {
                SurfaceSpec.identifier(payload); SurfaceStore.Key key=new SurfaceStore.Key(message.sendingUid,payload);
                SurfaceStore.Entry previous=store.get(key);
                if (previous!=null) remove(key,"withdrawn");
                send(reply,"removed",payload,previous==null?0:previous.spec.revision(),""); refresh();
            } else if (message.what==3) {
                removeConnection(reply.getBinder());
            } else throw new IllegalArgumentException("Unknown operation");
        } catch (Exception error) {
            send(reply,"error",id,revision,error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());
            refresh();
        }
        return true;
    }
    private void removeConnection(IBinder binder) {
        for (SurfaceStore.Entry entry:store.entries()) {
            Messenger callback=callbacks.get(entry.key);
            if (callback!=null && callback.getBinder().equals(binder)) {
                store.remove(entry.key); callbacks.remove(entry.key);
            }
        }
        IBinder.DeathRecipient death=connections.remove(binder);
        if (death!=null) binder.unlinkToDeath(death,0);
        refresh();
    }
    private void remove(SurfaceStore.Key key,String reason) {
        SurfaceStore.Entry entry=store.remove(key); Messenger reply=callbacks.remove(key);
        if (entry!=null) send(reply,reason,key.id(),entry.spec.revision(),"");
    }
    private void expire() {
        for (SurfaceStore.Entry entry:store.expire(SystemClock.elapsedRealtime()))
            send(callbacks.remove(entry.key),"expired",entry.key.id(),entry.spec.revision(),"");
    }
    private void refresh() {
        refresh(null);
    }
    private void refresh(SurfaceStore.Key preferred) {
        if (preferred!=null) pendingPreferred=preferred;
        expire(); handler.removeCallbacks(expiration);
        List<SurfaceStore.Entry> entries=store.entries();
        if (pendingPreferred!=null && store.get(pendingPreferred)==null) pendingPreferred=null;
        for (IBinder binder:List.copyOf(connections.keySet())) {
            boolean used=callbacks.values().stream().anyMatch(callback -> callback.getBinder().equals(binder));
            if (!used) {
                IBinder.DeathRecipient death=connections.remove(binder);
                binder.unlinkToDeath(death,0);
            }
        }
        if (!visibility.shouldShow(!entries.isEmpty())) window.hide();
        else try { window.render(entries,pendingPreferred); pendingPreferred=null; }
        catch (RuntimeException error) {
            window.hide();
            for (SurfaceStore.Entry entry:entries)
                send(callbacks.remove(entry.key),"error",entry.key.id(),entry.spec.revision(),"Display unavailable");
            store.clear();
        }
        long next=entries.stream().mapToLong(entry -> entry.expiresAt).min().orElse(0);
        if (next>0) handler.postDelayed(expiration,Math.max(1,next-SystemClock.elapsedRealtime()));
    }
    private static void send(Messenger callback,String type,String id,long revision,String value) {
        send(callback,type,id,revision,value,null);
    }
    private static void send(Messenger callback,String type,String id,long revision,String value,SurfaceSpec.ArtifactRef artifact) {
        if (callback==null) return;
        Message event=Message.obtain(null,100); Bundle data=new Bundle();
        data.putString("type",type); data.putString("id",id); data.putLong("revision",revision); data.putString("value",value);
        if (artifact!=null) {
            data.putString("artifactRef",artifact.ref()); data.putString("artifactMediaType",artifact.mediaType()); data.putString("artifactTitle",artifact.title());
        }
        event.setData(data); try { callback.send(event); } catch (RemoteException ignored) {}
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); refresh();
    }
    @Override public void onDestroy() {
        unregisterReceiver(screen); handler.removeCallbacksAndMessages(null); window.hide();
        for (Map.Entry<IBinder,IBinder.DeathRecipient> entry:connections.entrySet())
            entry.getKey().unlinkToDeath(entry.getValue(),0);
        connections.clear(); callbacks.clear(); store.clear(); super.onDestroy();
    }
}
