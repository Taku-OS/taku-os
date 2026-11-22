// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.taku.tryon.common.IslandPublisher;
import org.taku.tryon.data.XmaxKeyStore;

/** One explicit, bounded attempt using this app's Camera2 preview. Never injects third-party cameras. */
public final class CallTryOnService extends Service {
    private static final StartAdmission ADMISSION = new StartAdmission();
    private static final long PRESENTATION_RESPONSE_MS=1_000;
    private static volatile CallTryOnService current;
    private static volatile Snapshot last = new Snapshot("stopped","Choose an image, then start a 60-second preview.","none",false,60,0,0,"closed");
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService frames=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final AtomicBoolean terminating=new AtomicBoolean();
    private volatile RealtimeCallTransport transport;
    private volatile LocalShoppingFrameBridge bridge;
    private volatile CallTryOnBridge.Frame identity;
    private FalRealtimeTokenClient token;
    private ShoppingPreviewWindow window;
    private RealtimeImage reference;
    private TryOnController.Request request;
    private CallTryOnDiagnosticRecorder journal;
    private IslandPublisher island;
    private long began,inputs,outputs,sequence;
    private volatile long lastSource;
    private final AttemptOwner<RealtimeCallTransport> owner=new AttemptOwner<>(RealtimeCallTransport::stop);
    private String state="starting",detail="Preparing reference image…",code="none";
    private long revision;
    private boolean resultShown;
    private boolean released,destroyed;
    private long admissionGeneration;
    public static final class Snapshot {
        public final String state,detail,code,presentation;
        public final boolean active;
        public final long remainingSeconds,sourceFrames,returnedFrames;
        Snapshot(String state,String detail,String code,boolean active,long remaining,long source,long returned,String presentation) {
            this.state=state;this.detail=detail;this.code=code;this.active=active;remainingSeconds=remaining;sourceFrames=source;returnedFrames=returned;this.presentation=presentation;
        }
    }
    static long reserve(){return ADMISSION.reserve();}
    static void releaseReservation(long generation){ADMISSION.release(generation);}
    public static boolean busy(){return ADMISSION.busy();}
    public static Snapshot snapshot(){return last;}
    static boolean control(String action) {
        if("stop".equals(action))ADMISSION.cancel();
        CallTryOnService value=current;if(value==null)return "stop".equals(action)&&ADMISSION.busy();
        if("stop".equals(action)){
            value.main.post(()->value.finish("stopped","Try-on stopped.","user_stop"));return true;
        }
        return value.requestPresentation(action);
    }
    private boolean requestPresentation(String action) {
        if(Looper.myLooper()==main.getLooper())return applyPresentation(action);
        long postedAt=System.nanoTime();
        FutureTask<Boolean> command=new FutureTask<>(()->System.nanoTime()-postedAt
                <TimeUnit.MILLISECONDS.toNanos(PRESENTATION_RESPONSE_MS)&&applyPresentation(action));
        if(!main.post(command))return false;
        try{return command.get(PRESENTATION_RESPONSE_MS,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(ExecutionException|TimeoutException unavailable){ }
        command.cancel(false);main.removeCallbacks(command);return false;
    }
    private boolean applyPresentation(String action) {
        // This guard and the window operation share the UI thread. A queued request
        // cannot acknowledge a host which closed, terminated or changed generation.
        if(current!=this||window==null||terminating.get()||destroyed)return false;
        try{
            switch(action){
                case "minimize":window.setCollapsed(true);break;
                case "expand":window.expand();break;
                case "fullscreen":window.setFullscreen(true);break;
                default:return false;
            }
            publish();return true;
        }catch(RuntimeException unavailable){return false;}
    }
    static boolean move(int x,int y) {
        CallTryOnService value=current;
        return value!=null&&value.requestMove(x,y);
    }
    private boolean requestMove(int x,int y) {
        if(Looper.myLooper()==main.getLooper())return applyMove(x,y);
        long postedAt=System.nanoTime();
        FutureTask<Boolean> command=new FutureTask<>(()->System.nanoTime()-postedAt
                <TimeUnit.MILLISECONDS.toNanos(PRESENTATION_RESPONSE_MS)&&applyMove(x,y));
        if(!main.post(command))return false;
        try{return command.get(PRESENTATION_RESPONSE_MS,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(ExecutionException|TimeoutException unavailable){ }
        command.cancel(false);main.removeCallbacks(command);return false;
    }
    private boolean applyMove(int x,int y) {
        if(current!=this||window==null||terminating.get()||destroyed)return false;
        try {
            if(!window.moveTo(x,y))return false;
            publish();return true;
        } catch(RuntimeException unavailable){return false;}
    }
    /** Read the live GUI geometry on main; never infer it from a cached presentation label. */
    static Bundle position() {
        CallTryOnService value=current;
        return value==null?new Bundle():value.requestPosition();
    }
    private Bundle requestPosition() {
        if(Looper.myLooper()==main.getLooper())return applyPosition();
        long postedAt=System.nanoTime();
        FutureTask<Bundle> command=new FutureTask<>(()->System.nanoTime()-postedAt
                <TimeUnit.MILLISECONDS.toNanos(PRESENTATION_RESPONSE_MS)?applyPosition():new Bundle());
        if(!main.post(command))return new Bundle();
        try{return command.get(PRESENTATION_RESPONSE_MS,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(ExecutionException|TimeoutException unavailable){ }
        command.cancel(false);main.removeCallbacks(command);return new Bundle();
    }
    private Bundle applyPosition() {
        if(current!=this||window==null||terminating.get()||destroyed)return new Bundle();
        try{return window.position();}
        catch(RuntimeException unavailable){return new Bundle();}
    }
    @Override public void onCreate() {
        super.onCreate(); current=this;admissionGeneration=ADMISSION.current();
        island=new IslandPublisher(this,"tryon-progress","AI try-on");
        registerReceiver(screen,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent==null){finish("stopped","Try-on stopped.","service_destroyed");return START_NOT_STICKY;}
        if(request!=null)return START_NOT_STICKY;
        if(!ADMISSION.accepted(admissionGeneration)||intent.getLongExtra("attemptGeneration",0)!=admissionGeneration){finish("stopped","Try-on cancelled before camera start.","user_stop");return START_NOT_STICKY;}
        try {
            request=TryOnController.Request.from(this,intent.getExtras());
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("tryon-camera","Try-on camera",NotificationManager.IMPORTANCE_LOW));
            Notification notification=new Notification.Builder(this,"tryon-camera").setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("AI try-on").setContentText("Preparing a camera preview").setOngoing(true)
                    .setContentIntent(open("open")).build();
            startForeground(401,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            journal=new CallTryOnDiagnosticRecorder(new CallTryOnDiagnosticStore(this),"shopping","preview",SystemClock::elapsedRealtime,System::currentTimeMillis);
            journal.provider(request.configuration.providerId(),request.configuration.environment);journal.segment(1);
            publish();
            AttemptOwner.Task preparation=owner.queue();
            network.execute(()->{
                ReferenceRead read=new ReferenceRead();
                Runnable timeout=()->{read.close();finish("failed","Reference image preparation timed out.","invalid_reference");};
                try {
                    if(!owner.begin(preparation)||!owner.cancellation(read::close))return;
                    main.postDelayed(timeout,30000);
                    RealtimeImage image=read.read(this,request.reference);
                    main.post(()->{if(terminating.get()){image.close();return;}reference=image;showCamera();});
                } catch(Exception invalid){main.post(()->finish("failed","Could not read the reference image. Select the image again.","invalid_reference"));}
                finally {main.removeCallbacks(timeout);read.close();owner.done(preparation);main.post(this::releaseIfClosed);}
            });
        } catch(RuntimeException denied){finish("failed","Could not start the camera preview. Open Try-on and check camera permission.","foreground_service_denied");}
        return START_NOT_STICKY;
    }
    private void showCamera() {
        TryOnResultWindow.dismissCurrent();
        state="waiting_camera";detail="Preparing camera…";began=SystemClock.elapsedRealtime();owner.cameraOpening();
        try {
            window=new ShoppingPreviewWindow(this,new ShoppingPreviewWindow.Actions(){
                public void cameraReady(){if(!terminating.get()&&bridge==null)startVideo();}
                public void resultReady(){if(!terminating.get()){resultShown=true;state="running";detail="Live try-on";publish();}}
                public void failed(String message){finish("failed","Camera preview is unavailable. Close other camera apps and try again.","camera_stopped");}
                public void close(){finish("stopped","Try-on stopped.","user_stop");}
            },true);
            if(TryOnController.CHARACTER.equals(request.mode))window.setCharacterModel(request.configuration.cosplayModelLabel());
            window.show();publish();
            main.postDelayed(()->{if(!terminating.get()&&bridge==null)finish("failed","Camera did not become ready in time.","camera_wait_timeout");},25000);
        } catch(RuntimeException unavailable){finish("failed","Could not open the preview window. Check display-over-other-apps permission.","overlay_permission_required");}
    }
    private void startVideo() {
        try {bridge=new LocalShoppingFrameBridge(window);bridge.start(60000);}
        catch(CallTryOnBridge.Failure failure){finish("failed","Camera frame pipeline could not start.",failure.code);return;}
        began=SystemClock.elapsedRealtime();lastSource=began;
        state="authorizing";detail="Preparing cloud video…";publish();
        journal.stage(1,"source_ready",0);
        final long run=++revision;
        AttemptOwner.Task preparation=owner.queue();
        network.execute(()->{
            try {
                if(!owner.begin(preparation))return;
                boolean character=TryOnController.CHARACTER.equals(request.mode);
                FalRealtimeProfile profile=character?FalRealtimeProfile.CHARACTER_26:FalRealtimeProfile.LEGACY_VTON;
                String ref;
                try { ref=request.configuration.provider==RealtimeCallConfiguration.Provider.LUCY?reference.lucyCharacterDataUri():reference.dataUri; }
                catch(java.io.IOException invalid) {main.post(()->{finish("failed","Could not prepare the reference image for Lucy. Select a clearer image with a less extreme aspect ratio.","invalid_reference");});return;}
                String prompt=prompt(request.mode,request.prompt);
                CallTryOnRtc.Listener listener=new CallTryOnRtc.Listener(){
                    public void onState(String value){main.post(()->{if(!terminating.get()&&run==revision){state=value;detail="Preparing try-on video…";publish();}});}
                    public void onRemoteFrame(byte[] jpeg,int width,int height){
                        LocalShoppingFrameBridge b=bridge;CallTryOnBridge.Frame frame=identity;
                        if(terminating.get()||b==null||frame==null)return;
                        try {if(b.submit(jpeg,width,height,++sequence,frame)){main.post(()->{if(!terminating.get()){outputs++;publish();}});}}
                        catch(CallTryOnBridge.Failure failure){main.post(()->finish("failed","Could not display cloud video.",failure.code));}
                    }
                    public void onDiagnostic(Map<String,Object> evidence){if(journal!=null)journal.rtc(1,evidence);}
                    public void onFailure(String failure){main.post(()->{String message=CallTryOnFailures.cloud(failure,transport==null?null:transport.diagnosticSnapshot());finish("session_limit".equals(failure)?"stopped":"failed",message,CallTryOnFailures.safeCode(failure));});}
                    public void onCleanup(boolean confirmed){main.post(()->{owner.cloudClosed(confirmed);if(journal!=null)journal.cloudCleanup(confirmed);releaseIfClosed();});}
                };
                String credential;
                RealtimeCallTransport prepared;
                if(request.configuration.provider==RealtimeCallConfiguration.Provider.LUCY) {
                    FalRealtimeTokenClient mint=new FalRealtimeTokenClient(this,profile);
                    if(!owner.cancellation(mint::cancel))return;
                    token=mint;journal.stage(1,"token_pending",0);credential=mint.mint();
                    journal.stage(1,"token_ready",token.diagnosticHttpStatus());prepared=new CallTryOnRtc(this,listener,profile);
                } else {credential=new XmaxKeyStore(this).get();prepared=new XmaxCallTransport(this,request.configuration.environment,listener);}
                journal.stage(1,"rtc_connecting",0);
                owner.start(prepared,()->{transport=prepared;prepared.start(credential,prompt,ref);});
            } catch(FalRealtimeTokenClient.Failure failure){main.post(()->{finish("failed",CallTryOnFailures.token(failure.code,failure.httpStatus),failure.code);});}
            catch(Exception failure){main.post(()->{finish("failed","Could not prepare cloud video. Check model settings and connection diagnostics.","rtc_start_failed");});}
            finally {owner.done(preparation);main.post(this::releaseIfClosed);}
        });
        frames.scheduleWithFixedDelay(()->{
            if(terminating.get())return;
            try {
                LocalShoppingFrameBridge b=bridge;if(b==null)return;
                CallTryOnBridge.Frame frame=b.source(identity==null?-1:identity.sequence);
                if(frame!=null){identity=frame;lastSource=SystemClock.elapsedRealtime();RealtimeCallTransport t=transport;if(t!=null){t.sendFrame(frame.jpeg,frame.width,frame.height,frame.copiedNs);inputs++;}}
            }catch(Exception failure){main.post(()->finish("failed","Camera video could not continue.","input_frame_failed"));}
        },0,66,TimeUnit.MILLISECONDS);
        main.post(tick);
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(terminating.get())return;
        long now=SystemClock.elapsedRealtime();
        if(now-began>=60000){finish("stopped","The 60-second preview ended.","session_limit");return;}
        if(now-lastSource>8000){finish("failed","Camera stopped providing frames.","camera_stalled");return;}
        publish();main.postDelayed(this,1000);
    }};
    static String prompt(String mode,String custom) {
        String base=TryOnController.CHARACTER.equals(mode)
                ? "Use the reference image as the target character appearance. Follow the live person's pose, hand movements and facial expressions. Preserve camera framing and background."
                : "Use the reference image as the clothing reference. Change only clothing and worn accessories. Preserve the live person's exact face, hair, body and hand movements, framing and background.";
        return custom.isEmpty()?base:custom+"\n"+base;
    }
    private void finish(String next,String message,String failure) {
        if(!terminating.compareAndSet(false,true))return;
        state=next;detail=message;code=failure;++revision;
        main.removeCallbacks(tick);frames.shutdownNow();
        owner.close();
        LocalShoppingFrameBridge b=bridge;bridge=null;if(b!=null)b.stop();
        transport=null;
        if(journal!=null)journal.finish("stopped",next,failure,inputs,outputs,0);
        if(window!=null)window.close(()->{owner.cameraClosed();if(journal!=null)journal.restored(true);if("failed".equals(state))TryOnResultWindow.show(this,detail);releaseIfClosed();});else {owner.cameraClosed();if("failed".equals(state))TryOnResultWindow.show(this,detail);}
        publish();releaseIfClosed();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    private void releaseIfClosed() {
        if(released||!terminating.get()||!owner.closed())return;
        released=true;
        if(reference!=null){reference.close();reference=null;}
        if(destroyed)ADMISSION.release(admissionGeneration);else {publish();stopSelf();}
    }
    private PendingIntent open(String action) {
        return PendingIntent.getActivity(this,action.hashCode(),new Intent(this,"expand".equals(action)?TryOnRetryActivity.class:TryOnActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent stopAction() {
        return PendingIntent.getBroadcast(this,402,new Intent(this,TryOnActionReceiver.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private void publish() {
        boolean active=!terminating.get();long remaining=began==0?60:Math.max(0,(60000-(SystemClock.elapsedRealtime()-began)+999)/1000);
        last=new Snapshot(state,detail,code,active,remaining,inputs,outputs,window==null?"closed":window.presentation());
        if(window!=null&&active)window.render(last);
        if(journal!=null&&active)journal.progress("preview",state,inputs,outputs,0);
        if(island==null)return;
        // Terminal failures have one top-island card, published only after camera onClosed.
        if (!active && "failed".equals(state)) { island.dismiss("tryon"); return; }
        List<IslandPublisher.Action> actions=new ArrayList<>();
        actions.add(new IslandPublisher.Action("open",active?"Expand":"Open Try-on",open("expand")));
        if(active)actions.add(new IslandPublisher.Action("stop","Stop",stopAction()));
        island.post("tryon",active?(resultShown?"Live":"Preparing"):"Try-on ended","AI try-on",detail,active,actions,"open",open("open"),!active);
    }
    private final BroadcastReceiver screen=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){finish("stopped","Screen off. Camera stopped.","screen_off");}};
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        destroyed=true;
        finish("stopped","Try-on stopped.","service_destroyed");
        try{unregisterReceiver(screen);}catch(RuntimeException ignored){}
        network.shutdown();frames.shutdownNow();if(current==this)current=null;if(owner.closed())ADMISSION.release(admissionGeneration);super.onDestroy();
    }
}
