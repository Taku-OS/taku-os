// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.content.*;import android.content.pm.PackageManager;import android.os.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;import org.taku.tryon.contract.LiveTryOnContract;
public final class LiveEntryTest {
 static int checks;static final String TOKEN="A".repeat(43),REQUEST="00112233-4455-6677-8899-aabbccddeeff",REF="content://org.agentos.shopping.references/reference/one";
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static Intent input(){return new Intent().setAction(LiveTryOnContract.ACTION_TRY_ON).putExtra("referenceUri",REF).putExtra("admissionToken",TOKEN).putExtra("requestId",REQUEST).putExtra("expiresAtElapsedMs",31000L).putExtra("category","untrusted_intent_category").putExtra("prompt","untrusted intent prompt");}
 static Bundle receipt(){Bundle b=new Bundle();b.putBoolean("ok",true);b.putString("code","redeemed");b.putString("referenceUri",REF);b.putString("requestId",REQUEST);b.putLong("expiresAtElapsedMs",31000);b.putString("category","upper_body");b.putString("prompt","authoritative source outfit");return b;}
 static void reset(){SystemClock.now=1000;Handler.reset();CallTryOnService.startup=CallTryOnService.STARTUP_ATTACHED;CallTryOnService.generation=1;CallTryOnService.cancelled=0;ContentResolver.calls.set(0);ContentResolver.reply=args->receipt();PackageManager.sourcePackage="org.agentos.shopping";TryOnController.starts=0;TryOnController.visible=false;TryOnController.accept=true;TryOnController.lastArgs=null;TryOnActivity.unsafeParentEnvelopes=0;android.widget.Toast.last=null;}
 static DirectLiveTryOnActivity create(Intent intent,Bundle saved){DirectLiveTryOnActivity a=new DirectLiveTryOnActivity();a.setIntent(intent);a.onCreate(saved);return a;}
 static void queued()throws Exception{long end=System.nanoTime()+2_000_000_000L;while(Handler.jobs.isEmpty()&&System.nanoTime()<end)Thread.sleep(1);check(!Handler.jobs.isEmpty(),"worker posted receipt");}
 static void badReceipt(Consumer<Bundle> mutation)throws Exception{reset();ContentResolver.reply=args->{Bundle b=receipt();mutation.accept(b);return b;};DirectLiveTryOnActivity a=create(input(),null);try{a.onResume();queued();Handler.drain();check(TryOnController.starts==0&&a.finishing,"invalid receipt never starts camera/cloud");}finally{a.onDestroy();}}
 public static void main(String[] ignored)throws Exception{
  reset();Intent raw=input();DirectLiveTryOnActivity a=create(raw,null);try{
   check(!raw.getExtras().containsKey("admissionToken")&&a.getIntent().getAction()==null&&TryOnActivity.unsafeParentEnvelopes==0,"secret/control envelope removed before Activity entry");
   check(a.getClass().getSuperclass()==android.app.Activity.class&&a.window.width==1&&a.window.height==1&&a.window.background==android.R.color.transparent&&a.window.animations==0,"entry never creates settings page and uses transparent one-pixel non-animated bridge");
   check((a.window.flags&android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,"bridge never blocks the underlying LIVE touch target");
   queued();Runnable delivery=Handler.jobs.poll();delivery.run();check(TryOnController.starts==0,"valid receipt waits for resumed parent lifecycle");
   a.onResume();delivery.run();a.onResume();check(TryOnController.starts==1&&a.finishing,"receipt/resume duplication starts once");
   check("outfit".equals(TryOnController.lastArgs.getString("mode"))&&REF.equals(TryOnController.lastArgs.getString("referenceUri"))&&"authoritative source outfit".equals(TryOnController.lastArgs.getString("prompt")),"start is OUTFIT and uses source receipt only");
   check(TryOnController.lastArgs.getLong("referenceExpires")==121000,"reference TTL remains independent of 30s admission");
   Intent duplicate=input();a.onNewIntent(duplicate);check(ContentResolver.calls.get()==1&&!duplicate.getExtras().containsKey("admissionToken")&&TryOnActivity.unsafeParentEnvelopes==0,"duplicate Intent cannot redeem/start or reach old voice path");
  }finally{a.onDestroy();}
  for(String action:new String[]{null,"retry","expand","org.taku.tryon.action.LIVE_TRY_ON"}){
   reset();Intent bad=new Intent().setAction(action).putExtra("admissionToken","bad");a=create(bad,null);try{a.onResume();Handler.drain();check(ContentResolver.calls.get()==0&&TryOnController.starts==0&&a.finishing&&TryOnActivity.unsafeParentEnvelopes==0,"bad/exported control Intent remains inert");}finally{a.onDestroy();}
  }
  reset();a=create(input(),new Bundle());try{a.onResume();check(ContentResolver.calls.get()==0&&TryOnController.starts==0&&a.finishing,"saved-state recreation cannot redeem twice");}finally{a.onDestroy();}
  badReceipt(b->b.putBoolean("ok",false));badReceipt(b->b.putString("referenceUri",REF+"other"));badReceipt(b->b.putString("requestId","different"));
  badReceipt(b->b.putLong("expiresAtElapsedMs",32000));badReceipt(b->b.putString("expiresAtElapsedMs","31000"));badReceipt(b->b.putString("category","character"));
  badReceipt(b->b.putString("prompt"," "));badReceipt(b->b.put("prompt",Boolean.TRUE));badReceipt(b->b.putString("prompt","x".repeat(2001)));badReceipt(b->b.putString("ok","true"));
  reset();a=create(input(),null);try{queued();Handler.drain();SystemClock.now=31000;a.onResume();check(TryOnController.starts==0&&a.finishing,"receipt expires while waiting for foreground");}finally{a.onDestroy();}
  reset();a=create(input(),null);try{a.onResume();queued();a.onPause();Handler.drain();a.onResume();check(TryOnController.starts==0&&a.finishing,"pause generation fences late receipt and later resume");}finally{a.onDestroy();}
  reset();a=create(input(),null);queued();Runnable late=Handler.jobs.poll();a.onDestroy();late.run();check(TryOnController.starts==0,"destroyed instance fences queued receipt");
  reset();PackageManager.sourcePackage="wrong.owner";a=create(input(),null);try{a.onResume();queued();Handler.drain();check(ContentResolver.calls.get()==0&&TryOnController.starts==0,"authority must belong to actual shopping source");}finally{a.onDestroy();}
  reset();TryOnController.accept=false;a=create(input(),null);try{a.onResume();queued();Runnable deliver=Handler.jobs.poll();deliver.run();deliver.run();a.onResume();check(TryOnController.starts==1&&a.finishing&&"busy".equals(android.widget.Toast.last),"Controller denial is surfaced without automatic retry");}finally{a.onDestroy();}
  reset();CallTryOnService.startup=CallTryOnService.STARTUP_PENDING;a=create(input(),null);try{
   a.onResume();queued();Handler.drain();check(TryOnController.starts==1&&!a.finishing,"FGS enqueue is not a window attach acknowledgement");
   Handler.advance(100);check(!a.finishing,"bridge remains resumed while its own window is pending");
   CallTryOnService.startup=CallTryOnService.STARTUP_ATTACHED;Handler.advance(100);check(a.finishing,"exact-generation attach hands over without waiting for cloud/video");
   a.onPause();check(!TryOnController.visible&&CallTryOnService.cancelled==0,"handoff pause balances visibility and keeps admitted service alive");
  }finally{a.onDestroy();}check(CallTryOnService.cancelled==0,"handoff destroy cannot cancel successful attempt");
  reset();CallTryOnService.startup=CallTryOnService.STARTUP_PENDING;a=create(input(),null);try{
   a.onResume();queued();Handler.drain();Handler.advance(15000);
   check(a.finishing&&CallTryOnService.cancelled==1&&"live_start_timeout".equals(android.widget.Toast.last),"15-second startup deadline cancels only its own pending service");
  }finally{a.onDestroy();}check(CallTryOnService.cancelled==1,"timeout/destroy cancellation is one-use");
  reset();CallTryOnService.startup=CallTryOnService.STARTUP_PENDING;a=create(input(),null);try{
   a.onResume();queued();Handler.drain();a.onPause();check(!TryOnController.visible&&CallTryOnService.cancelled==1,"leaving foreground cancels pending own attempt");
   CallTryOnService.startup=CallTryOnService.STARTUP_ATTACHED;Handler.advance(100);a.onResume();check(a.finishing&&TryOnController.starts==1,"late window attach and resume cannot revive a cancelled bridge");
  }finally{a.onDestroy();}
  reset();CallTryOnService.startup=CallTryOnService.STARTUP_PENDING;a=create(input(),null);try{
   a.onResume();queued();Handler.drain();CallTryOnService.generation=2;CallTryOnService.startup=CallTryOnService.STARTUP_ATTACHED;Handler.advance(100);
   check(a.finishing&&CallTryOnService.cancelled==0,"another generation neither acknowledges the old bridge nor is globally stopped by its failure");
  }finally{a.onDestroy();}check(CallTryOnService.cancelled==0,"stale destroy cannot cancel the replacement attempt");
  reset();CallTryOnService.startup=CallTryOnService.STARTUP_PENDING;a=create(input(),null);a.onResume();queued();Handler.drain();a.onDestroy();Handler.advance(16000);
  check(CallTryOnService.cancelled==1&&TryOnController.starts==1,"destroy cancels own pending attempt and fences startup polling");
  reset();CallTryOnService.startup=CallTryOnService.STARTUP_PENDING;a=create(input(),null);try{
   queued();Handler.drain();SystemClock.now=30500;a.onResume();Handler.advance(500);
   check(a.finishing&&CallTryOnService.cancelled==1,"admission deadline also bounds service startup acknowledgement");
  }finally{a.onDestroy();}
  check(TryOnActivity.unsafeParentEnvelopes==0,"parent never observes LIVE token or control action");
  System.out.println("Added LIVE Activity production lifecycle/admission checks: "+checks);
 }
}