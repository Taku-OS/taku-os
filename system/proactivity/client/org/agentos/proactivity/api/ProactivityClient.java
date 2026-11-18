package org.agentos.proactivity.api;

import android.content.*;
import android.os.*;
import java.util.UUID;
import org.json.JSONObject;

/** Main-thread, optional Binder/Messenger client. No host implementation is bundled. */
public final class ProactivityClient implements AutoCloseable {
    public interface Listener {
        void onConnected();
        void onMessage(JSONObject receipt);
        default void onDisconnected() {}
    }
    private final Context context;
    private final Listener listener;
    private Messenger remote;
    private boolean bound;
    private int hostUid=-1;
    private final Messenger reply;
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder binder) {
            remote=new Messenger(binder);listener.onConnected();
        }
        public void onServiceDisconnected(ComponentName name) {remote=null;listener.onDisconnected();}
        public void onBindingDied(ComponentName name) {close();listener.onDisconnected();}
        public void onNullBinding(ComponentName name) {close();listener.onDisconnected();}
    };
    public ProactivityClient(Context context,Listener listener) {
        this.context=context.getApplicationContext();this.listener=listener;
        reply=new Messenger(new Handler(Looper.getMainLooper(),message->{
            if(message.what==ProactivityWire.RESPONSE && message.sendingUid==hostUid)try {
                JSONObject receipt=new JSONObject(message.getData().getString("payload",""));
                if(ProactivityWire.integer(receipt,"version")==ProactivityWire.VERSION)listener.onMessage(receipt);
            }catch(Exception ignored){}return true;
        }));
    }
    public void connect() throws Exception {
        main();if(bound)return;
        hostUid=context.getPackageManager().getApplicationInfo(ProactivityWire.PACKAGE,0).uid;
        bound=context.bindService(new Intent().setComponent(new ComponentName(ProactivityWire.PACKAGE,
            ProactivityWire.SERVICE)),connection,Context.BIND_AUTO_CREATE);
        if(!bound)throw new IllegalStateException("Proactivity host unavailable");
    }
    public String register(ObserverSpec spec) throws Exception {
        return request("register",new JSONObject().put("spec",ProactivityWire.spec(spec)));
    }
    public String emit(String observerId,String eventId,String eventType,int pageRevision) throws Exception {
        return request("event",new JSONObject().put("observerId",observerId).put("eventId",eventId)
            .put("eventType",eventType).put("pageRevision",pageRevision));
    }
    public String inspect(String id) throws Exception {return request("inspect",new JSONObject().put("observerId",id));}
    public String request(String operation,JSONObject arguments) throws Exception {
        main();if(remote==null)throw new IllegalStateException("Connect first");
        String id=UUID.randomUUID().toString();
        JSONObject request=new JSONObject().put("version",ProactivityWire.VERSION).put("requestId",id)
            .put("op",operation).put("args",arguments);
        byte[] bytes=request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>ProactivityWire.MAX_BYTES)throw new IllegalArgumentException("Request too large");
        Message message=Message.obtain(null,ProactivityWire.REQUEST);message.replyTo=reply;
        Bundle data=new Bundle();data.putString("payload",request.toString());message.setData(data);remote.send(message);return id;
    }
    @Override public void close() {
        main();if(remote!=null)try{request("disconnect",new JSONObject());}catch(Exception ignored){}
        if(bound)context.unbindService(connection);bound=false;remote=null;
    }
    private static void main() {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main thread required");
    }
}
