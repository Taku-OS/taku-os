// Character voice ticket entry; camera, transport and cleanup remain owned by the shared controller.
package org.taku.tryon.realtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.taku.cosplay.api.CosplayContract;

/** Ticket only. An exported Intent alone can never authorize camera/cloud generation. */
public final class DirectCosplayActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private AlertDialog failureDialog;
    private String pendingFailure;
    private boolean consumed,resumed,closed,started,handedOff,destroyed;
    private String ticket;
    private long deadline;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);showPreparingWindow();
        Intent intent=getIntent();
        try{Bundle values=intent.getExtras();
            if(saved!=null||intent.getData()!=null||intent.getClipData()!=null||intent.getType()!=null||intent.getSelector()!=null
                    ||values==null||values.size()!=1||!(values.get(CosplayContract.EXTRA_TICKET) instanceof String))throw new IllegalArgumentException();
            ticket=values.getString(CosplayContract.EXTRA_TICKET);intent.removeExtra(CosplayContract.EXTRA_TICKET);
            if(!CosplayPolicy.validTicket(ticket))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){fail("This request expired. Return to TikTok and ask again.");}
    }
    private void showPreparingWindow(){
        // Retain the foreground lifecycle lease until the same existing camera-window ACK.
        // Normal preparation has no page, text, or touch target over the original video.
        View bridge=new View(this);
        bridge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setContentView(bridge);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().setGravity(Gravity.TOP|Gravity.LEFT);
        getWindow().setLayout(1,1);
    }
    @Override protected void onResume(){super.onResume();resumed=true;TryOnController.visible(true);
        if(closed){if(pendingFailure==null)finish();else showFailureDialog();return;}if(consumed)return;consumed=true;
        CosplayRequestStore.Request request=CosplayRequestStore.take(ticket,SystemClock.elapsedRealtime());ticket=null;
        if(request==null||!unlocked()||!LegacyCosplayGateway.ready(this)||LegacyCosplayGateway.voiceOnly(this)){if(request!=null)Arrays.fill(request.jpeg(),(byte)0);fail("Character preview is not ready. Check model settings and ask again.");return;}
        deadline=Math.min(request.invokedAtElapsedMs()+CosplayContract.MAX_INVOCATION_AGE_MS,request.capturedAtElapsedMs()+CosplayContract.MAX_CAPTURE_AGE_MS);
        if(!ReferenceImport.begin()){Arrays.fill(request.jpeg(),(byte)0);fail("Another reference is being prepared. Please wait.");return;}
        work.execute(()->{
            try{ReferenceImport.write(this,new ByteArrayInputStream(request.jpeg()));}
            finally{Arrays.fill(request.jpeg(),(byte)0);}
            main.post(()->{
                if(closed||!resumed)return;
                if(!unlocked()||SystemClock.elapsedRealtime()>=deadline){fail("Character request expired before startup. Ask again from TikTok.");return;}
                if(!"ready".equals(ReferenceImport.status().optString("state"))){fail("Could not prepare the character image. Ask again from TikTok.");return;}
                Bundle draft=new Bundle();draft.putString("mode",TryOnController.CHARACTER);
                // Preserve the existing Pixel voice feature's fixed outfit/accessory-only policy.
                // Its old model metadata declares these as requested constraints, not a face-lock guarantee.
                draft.putString("prompt","Transfer only the reference character's outfit and worn accessories to the live person. Preserve the live person's face, hair, skin, identity and body. Do not copy the reference person's face or hair. These outfit-only constraints take priority over the general target-character description.");
                draft.putLong("referenceExpires",deadline);
                Bundle result=TryOnController.draft(this,draft);
                if(result.getBoolean("ok"))result=TryOnController.start(this,new Bundle());
                if(!result.getBoolean("ok")){fail("Could not start character preview: "+result.getString("code","unavailable"));return;}
                started=true;main.post(check);
            });
        });
    }
    private final Runnable check=new Runnable(){public void run(){
        if(closed||handedOff)return;
        CallTryOnService.Snapshot state=CallTryOnService.snapshot();
        if(state.active&&!"starting".equals(state.state)){handedOff=true;finish();return;}
        if(!CallTryOnService.busy()){fail(state.detail);return;}
        if(SystemClock.elapsedRealtime()>=deadline){TryOnController.stop(DirectCosplayActivity.this);fail("Character request expired before camera startup.");return;}
        main.postDelayed(this,100);
    }};
    private boolean unlocked(){KeyguardManager keyguard=getSystemService(KeyguardManager.class);PowerManager power=getSystemService(PowerManager.class);
        return power!=null&&power.isInteractive()&&keyguard!=null&&!keyguard.isKeyguardLocked();}
    private void fail(String value){
        if(closed||destroyed)return;
        closed=true;pendingFailure=value==null||value.isBlank()?"Could not start character preview. Return to TikTok and ask again.":value;
        main.removeCallbacksAndMessages(null);showFailureDialog();
    }
    private void showFailureDialog(){
        // Invalid creation-time envelopes wait only for the first safe resumed frame.
        // Pausing clears the outcome, so a late callback cannot reopen a background Activity.
        if(pendingFailure==null||!resumed||destroyed||isFinishing()||isDestroyed()||failureDialog!=null)return;
        failureDialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Couldn't start try-on").setMessage(pendingFailure)
                .setPositiveButton("Close",(dialog,which)->finish())
                .setOnCancelListener(dialog->finish()).create();
        failureDialog.show();
    }
    private void dismissFailureDialog(){
        AlertDialog previous=failureDialog;failureDialog=null;
        if(previous!=null)previous.dismiss();
    }
    @Override protected void onPause(){resumed=false;pendingFailure=null;dismissFailureDialog();TryOnController.visible(false);if(!handedOff){closed=true;if(started)TryOnController.stop(this);CosplayRequestStore.discard(ticket);ticket=null;}super.onPause();}
    @Override protected void onDestroy(){destroyed=true;closed=true;pendingFailure=null;dismissFailureDialog();CosplayRequestStore.discard(ticket);main.removeCallbacksAndMessages(null);work.shutdownNow();super.onDestroy();}
}
