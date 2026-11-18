package org.agentos.proactivity;

import android.app.KeyguardManager;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.agentos.proactivity.api.*;
import org.agentos.surfaces.*;
import org.agentos.surfaces.client.SurfaceClient;

/** Host-owned policy, context and display bridge. Plugins have neither its UID nor its signing key. */
final class HostRuntime implements SurfaceClient.Listener {
    private static HostRuntime instance;
    static HostRuntime get(Context context){if(instance==null)instance=new HostRuntime(context);return instance;}
    static void release(){if(instance!=null){instance.close();instance=null;}}
    final Context context;
    RegistryStore registry;
    String failure="";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SurfaceClient surfaces;
    private boolean connected,closed;
    private long sequence;
    private final Map<String,SettledObserver> observers=new HashMap<>();
    private final Map<String,Messenger> peers=new HashMap<>();
    private final Map<IBinder,IBinder.DeathRecipient> deaths=new HashMap<>();
    private final Map<String,Offer> offers=new HashMap<>();
    private final Map<Integer,ArrayDeque<Long>> requests=new HashMap<>();
    private record Offer(String key,long revision,SettledObserver.Candidate candidate,long createdAt,JSONObject receipt) {}
    private final BroadcastReceiver screen=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){for(String key:List.copyOf(observers.keySet()))invalidate(key);}
    };
    private HostRuntime(Context context) {
        this.context=context.getApplicationContext();surfaces=new SurfaceClient(this.context,this);
        try{registry=new RegistryStore(this.context);}catch(Exception e){failure=e.getMessage();}
        this.context.registerReceiver(screen,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);
    }
    private record Identity(String pkg,String signer,long codeVersion) {}
    private Identity identity(int uid) throws Exception {
        String[] packages=context.getPackageManager().getPackagesForUid(uid);
        if(packages==null || packages.length!=1)throw new SecurityException("One package per caller UID required");
        PackageInfo info=context.getPackageManager().getPackageInfo(packages[0],PackageManager.GET_SIGNING_CERTIFICATES);
        android.content.pm.Signature[] certs=info.signingInfo.getApkContentsSigners();
        if(certs.length!=1)throw new SecurityException("One signing certificate required");
        String signer=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray()));
        return new Identity(packages[0],signer,info.getLongVersionCode());
    }
    void receive(Message message) {
        if(message.what!=ProactivityWire.REQUEST || message.replyTo==null || message.sendingUid<0)return;
        Messenger reply=message.replyTo;String requestId="",op="";
        try {
            long now=SystemClock.elapsedRealtime();
            for(var bucket:requests.values())while(!bucket.isEmpty() && now-bucket.peekFirst()>60000)bucket.removeFirst();
            requests.entrySet().removeIf(e->e.getValue().isEmpty());
            if(!requests.containsKey(message.sendingUid) && requests.size()>=128)throw new IllegalStateException("rate_limited");
            ArrayDeque<Long> bucket=requests.computeIfAbsent(message.sendingUid,k->new ArrayDeque<>());
            if(bucket.size()>=240)throw new IllegalStateException("rate_limited");bucket.addLast(now);
            if(registry==null)throw new IllegalStateException("Registry unavailable: "+failure);
            String payload=message.getData().getString("payload","");
            if(payload.getBytes(StandardCharsets.UTF_8).length>ProactivityWire.MAX_BYTES)throw new IllegalArgumentException("Request too large");
            JSONObject request=ProactivityWire.parse(payload);requestId=request.getString("requestId");
            if(!requestId.matches("[a-f0-9-]{36}") || request.length()!=4)throw new IllegalArgumentException("Invalid request envelope");
            if(ProactivityWire.integer(request,"version")!=1)throw new IllegalArgumentException("unsupported_version");
            op=request.getString("op");JSONObject args=request.getJSONObject("args");Identity caller=identity(message.sendingUid);
            JSONObject result;
            switch(op) {
                case "capabilities":{
                    if(args.length()!=0)throw new IllegalArgumentException("Unexpected capability fields");
                    result=new JSONObject().put("version",1).put("observerKind","settled_event")
                        .put("maxObserversPerPackage",4).put("maxRecords",16).put("imageDecision",false).put("agentExecution",false);break;
                }
                case "register": {
                    if(args.length()!=1)throw new IllegalArgumentException("Unexpected registration fields");
                    ObserverSpec spec=ProactivityWire.spec(args.getJSONObject("spec"));String key=caller.pkg()+"/"+spec.id();
                    JSONObject before=null;try{before=registry.item(key);}catch(IllegalArgumentException absent){}
                    result=registry.register(caller.pkg(),caller.signer(),caller.codeVersion(),spec);
                    if(before==null || before.optLong("pendingRevision")!=result.optLong("pendingRevision"))invalidate(key);
                    peer(key,reply);break;
                }
                case "inspect": {
                    if(args.length()!=1)throw new IllegalArgumentException("Unexpected inspect fields");
                    result=owned(caller,args.getString("observerId"));break;
                }
                case "event":result=event(caller,args,reply);break;
                case "disconnect": {
                    if(args.length()!=0)throw new IllegalArgumentException("Unexpected disconnect fields");
                    for(String key:List.copyOf(peers.keySet()))if(key.startsWith(caller.pkg()+"/") && peers.get(key).getBinder().equals(reply.getBinder())){peers.remove(key);invalidate(key);}
                    unlinkUnused();
                    result=new JSONObject();break;
                }
                default:throw new IllegalArgumentException("unsupported_operation");
            }
            send(reply,new JSONObject().put("ok",true).put("type",op).put("requestId",requestId).put("result",result));
        } catch(Exception error) {
            try{send(reply,new JSONObject().put("ok",false).put("type",op).put("requestId",requestId)
                .put("code","rate_limited".equals(error.getMessage())?"rate_limited":error instanceof SecurityException?"not_authorized":"invalid_or_unavailable")
                .put("message",error.getMessage()==null?"Unavailable":error.getMessage()));}catch(Exception ignored){}
        }
    }
    private JSONObject owned(Identity caller,String id) throws Exception {
        ObserverSpec.identifier(id);JSONObject row=registry.item(caller.pkg()+"/"+id);
        if(!row.getString("signer").equals(caller.signer()))throw new SecurityException("signer_changed");return row;
    }
    private JSONObject event(Identity caller,JSONObject args,Messenger reply) throws Exception {
        if(args.length()!=4)throw new IllegalArgumentException("Unexpected event fields");
        String id=args.getString("observerId"),key=caller.pkg()+"/"+id;JSONObject row=owned(caller,id);
        if(!RegistryStore.active(row))throw new SecurityException("Observer needs user approval or is disabled");
        if(row.getJSONObject("registration").getLong("codeVersion")!=caller.codeVersion())throw new SecurityException("Installed plugin changed; register and review this version first");
        JSONObject revision=RegistryStore.revision(row,row.getLong("activeRevision"));
        String eventId=args.getString("eventId");ObserverSpec.identifier(eventId);
        int pageRevision=Math.toIntExact(ProactivityWire.integer(args,"pageRevision"));if(pageRevision<0)throw new IllegalArgumentException("Invalid page revision");
        ObserverSpec spec=RegistryStore.activeSpec(row);
        if(!spec.eventType().equals(args.getString("eventType")))throw new SecurityException("Undeclared event type");
        peer(key,reply);SettledObserver observer=observers.get(key);
        if(observer==null) {
            observer=new SettledObserver(spec);long last=row.getLong("lastOfferWall");
            if(last>0)observer.restoreCooldown(SystemClock.elapsedRealtime(),Math.max(0,System.currentTimeMillis()-last));
            observers.put(key,observer);
        }
        boolean added=observer.event(new SettledObserver.Event(eventId,spec.eventType(),++sequence,SystemClock.elapsedRealtime(),pageRevision));
        if(added){withdraw(key);schedule();}
        return new JSONObject().put("accepted",added).put("duplicate",!added).put("policyRevision",revision.getLong("revision"))
            .put("producerCodeVersion",caller.codeVersion());
    }
    private void peer(String key,Messenger reply) throws Exception {
        IBinder binder=reply.getBinder();
        if(!deaths.containsKey(binder)) {
            IBinder.DeathRecipient death=()->main.post(()->disconnect(binder));binder.linkToDeath(death,0);deaths.put(binder,death);
        }
        peers.put(key,reply);
        unlinkUnused();
    }
    private void unlinkUnused(){for(IBinder binder:List.copyOf(deaths.keySet()))if(peers.values().stream().noneMatch(p->p.getBinder().equals(binder))){IBinder.DeathRecipient death=deaths.remove(binder);binder.unlinkToDeath(death,0);}}
    private void disconnect(IBinder binder) {
        for(String key:List.copyOf(peers.keySet()))if(peers.get(key).getBinder().equals(binder)){peers.remove(key);invalidate(key);}
        IBinder.DeathRecipient death=deaths.remove(binder);if(death!=null)binder.unlinkToDeath(death,0);
    }
    void approve(String key,long revision)throws Exception{registry.approve(key,revision);invalidate(key);notifyState(key);}
    void disable(String key)throws Exception{registry.disable(key);invalidate(key);notifyState(key);}
    void rollback(String key,long revision)throws Exception{registry.rollback(key,revision);invalidate(key);notifyState(key);}
    void editPolicy(String key,long revision,ObserverSpec spec)throws Exception{registry.editPolicy(key,revision,spec);invalidate(key);notifyState(key);}
    void remove(String key)throws Exception{registry.remove(key);invalidate(key);peers.remove(key);unlinkUnused();}
    private void notifyState(String key)throws Exception {send(peers.get(key),new JSONObject().put("ok",true).put("type","policy_changed").put("result",registry.item(key)));}
    private void invalidate(String key){SettledObserver observer=observers.remove(key);if(observer!=null)observer.invalidate();withdraw(key);schedule();}
    private void withdraw(String key) {
        for(String id:List.copyOf(offers.keySet()))if(offers.get(id).key().equals(key)) {
            offers.remove(id);if(connected)try{surfaces.dismiss(id);}catch(Exception ignored){}
        }
    }
    private void schedule(){main.removeCallbacks(tick);if(closed)return;long due=observers.values().stream().mapToLong(SettledObserver::due).min().orElse(Long.MAX_VALUE);
        if(due!=Long.MAX_VALUE)main.postDelayed(tick,Math.max(1,due-SystemClock.elapsedRealtime()));}
    private boolean available(){return context.getSystemService(PowerManager.class).isInteractive()
        && !context.getSystemService(KeyguardManager.class).isDeviceLocked();}
    private final Runnable tick=this::tick;
    private void tick(){
        for(String key:List.copyOf(observers.keySet()))try {
            SettledObserver observer=observers.get(key);SettledObserver.Candidate candidate=observer.poll(SystemClock.elapsedRealtime());
            if(candidate==null)continue;
            JSONObject row=registry.item(key);if(!RegistryStore.active(row)||!available()) {registry.trace(key,"gated",new JSONObject());continue;}
            if(offers.size()>=8){registry.trace(key,"display_capacity",new JSONObject());continue;}
            JSONArray records=new JSONArray();for(SettledObserver.Event event:candidate.records())records.put(new JSONObject()
                .put("id",event.id()).put("type",event.type()).put("sequence",event.sequence()).put("observedAt",event.at()).put("pageRevision",event.pageRevision()));
            JSONObject receipt=new JSONObject().put("candidateId",candidate.id()).put("policyRevision",row.getLong("activeRevision"))
                .put("observerId",row.getString("id"))
                .put("records",records).put("sourcePackage",row.getString("package"))
                .put("producerCodeVersion",identity(context.getPackageManager().getApplicationInfo(row.getString("package"),0).uid).codeVersion())
                .put("imageDecision",false).put("agentExecution",false);
            offers.put(candidate.id(),new Offer(key,row.getLong("activeRevision"),candidate,SystemClock.elapsedRealtime(),receipt));
            surfaces.connect();if(connected)publish(candidate.id());
            main.postDelayed(()->expireOffer(candidate.id()),120000);
        }catch(Exception error){failure=error.getMessage();try{registry.trace(key,"unavailable",new JSONObject().put("message",failure));}catch(Exception ignored){}}
        schedule();
    }
    private void expireOffer(String id){Offer offer=offers.get(id);if(offer!=null){withdraw(offer.key());try{registry.trace(offer.key(),"expired",offer.receipt());}catch(Exception ignored){}}}
    private boolean valid(Offer offer)throws Exception {
        JSONObject row=registry.item(offer.key());SettledObserver observer=observers.get(offer.key());
        Identity producer=identity(context.getPackageManager().getApplicationInfo(row.getString("package"),0).uid);
        return producer.signer().equals(row.getString("signer")) && producer.codeVersion()==row.getJSONObject("registration").getLong("codeVersion")
            && RegistryStore.active(row) && row.getLong("activeRevision")==offer.revision() && observer!=null
            && observer.current(offer.candidate()) && SystemClock.elapsedRealtime()-offer.createdAt()<120000 && available();
    }
    private void publish(String id)throws Exception {
        Offer offer=offers.get(id);if(offer==null || !valid(offer)){if(offer!=null)withdraw(offer.key());return;}
        ObserverSpec spec=RegistryStore.activeSpec(registry.item(offer.key()));
        surfaces.publish(SurfaceSpec.hint(id,offer.revision(),spec.title(),spec.summary(),
            new SurfaceSpec.Action("accept","Viewed",true)).withTtlMs(120000));
    }
    @Override public void onConnected(){connected=true;for(String id:List.copyOf(offers.keySet()))try{publish(id);}catch(Exception e){failure=e.getMessage();}}
    @Override public void onEvent(String type,String id,long revision,String value) {
        if(type.equals("disconnected")){connected=false;offers.clear();return;}
        Offer offer=offers.get(id);if(offer==null)return;
        try {
            if(type.equals("published")) {
                if(!valid(offer)){withdraw(offer.key());return;}
                registry.offered(offer.key(),offer.receipt());observers.get(offer.key()).offered(SystemClock.elapsedRealtime());
                send(peers.get(offer.key()),new JSONObject().put("ok",true).put("type","published").put("result",offer.receipt()));
            } else if(List.of("dismissed","expired","error").contains(type)) {
                offers.remove(id);registry.trace(offer.key(),type,new JSONObject(offer.receipt().toString()).put("reason",value));
                send(peers.get(offer.key()),new JSONObject().put("ok",!type.equals("error")).put("type",type).put("result",offer.receipt()));
            }
        }catch(Exception e){failure=e.getMessage();withdraw(offer.key());}
    }
    @Override public void onAction(SurfaceClient.ActionEvent event) {
        Offer offer=offers.get(event.id());if(offer==null)return;
        try {
            if(!event.actionId().equals("accept") || event.revision()!=offer.revision() || !valid(offer)){withdraw(offer.key());return;}
            registry.trace(offer.key(),"accepted",offer.receipt());withdraw(offer.key());
            send(peers.get(offer.key()),new JSONObject().put("ok",true).put("type","accepted").put("result",offer.receipt()));
        }catch(Exception e){failure=e.getMessage();withdraw(offer.key());}
    }
    private static void send(Messenger reply,JSONObject payload) {
        try{payload.put("version",ProactivityWire.VERSION);}catch(JSONException invalid){return;}
        if(reply==null)return;Message message=Message.obtain(null,ProactivityWire.RESPONSE);Bundle data=new Bundle();data.putString("payload",payload.toString());message.setData(data);
        try{reply.send(message);}catch(RemoteException ignored){}
    }
    private void close(){closed=true;main.removeCallbacksAndMessages(null);for(String key:List.copyOf(observers.keySet()))invalidate(key);
        context.unregisterReceiver(screen);
        surfaces.close();for(var death:deaths.entrySet())death.getKey().unlinkToDeath(death.getValue(),0);deaths.clear();peers.clear();}
}
