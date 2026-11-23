// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.os.Looper;
public final class StartupAckTest {
 static int checks;static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 static CallTryOnService service(long gen,boolean attached){CallTryOnService s=new CallTryOnService();s.admissionGeneration=gen;s.window=new CallTryOnService.Window();s.window.attached=attached;return s;}
 public static void main(String[] ignored)throws Exception {
  check(CallTryOnService.startupState(0)==CallTryOnService.STARTUP_ENDED,"zero/unknown request cannot ACK");
  long one=CallTryOnService.ADMISSION.reserve();
  check(CallTryOnService.startupState(one)==CallTryOnService.STARTUP_PENDING,"queued FGS still awaits an actual window");
  CallTryOnService.current=service(one,false);
  check(CallTryOnService.startupState(one)==CallTryOnService.STARTUP_PENDING,"constructed window is not attached");
  CallTryOnService.current.window.attached=true;
  check(CallTryOnService.startupState(one)==CallTryOnService.STARTUP_ATTACHED,"only same admitted generation and live attached window ACK");
  CallTryOnService.current.terminating.set(true);
  check(CallTryOnService.startupState(one)==CallTryOnService.STARTUP_ENDED,"terminating service cannot ACK");
  CallTryOnService.current.terminating.set(false);CallTryOnService.current.destroyed=true;
  check(CallTryOnService.startupState(one)==CallTryOnService.STARTUP_ENDED,"destroyed service cannot ACK");
  CallTryOnService.current.destroyed=false;CallTryOnService stale=CallTryOnService.current;
  CallTryOnService.ADMISSION.release(one);long two=CallTryOnService.ADMISSION.reserve();
  check(CallTryOnService.startupState(one)==CallTryOnService.STARTUP_ENDED,"old generation cannot borrow a new reservation");
  check(CallTryOnService.startupState(two)==CallTryOnService.STARTUP_PENDING,"old attached service cannot ACK replacement generation");
  CallTryOnService.cancelStartup(one);
  check(CallTryOnService.ADMISSION.accepted(two)&&stale.finishes==0,"stale bridge cannot cancel a new reservation or old unrelated UI");
  CallTryOnService.current=service(two,true);CallTryOnService current=CallTryOnService.current;
  Looper.main=false;boolean badRead=false,badWrite=false;
  try{CallTryOnService.startupState(two);}catch(IllegalStateException expected){badRead=true;}
  try{CallTryOnService.cancelStartup(two);}catch(IllegalStateException expected){badWrite=true;}
  Looper.main=true;check(badRead&&badWrite&&CallTryOnService.ADMISSION.accepted(two),"startup window access is main-thread-only");
  CallTryOnService.cancelStartup(two);
  check(!CallTryOnService.ADMISSION.accepted(two)&&current.finishes==1,"matching bridge cancels its own reserved service");
  check(CallTryOnService.startupState(two)==CallTryOnService.STARTUP_ENDED,"cancelled generation cannot ACK later attach");
  CallTryOnService.ADMISSION.release(two);CallTryOnService.current=null;long three=CallTryOnService.ADMISSION.reserve();
  CallTryOnService.cancelStartup(three);
  check(CallTryOnService.ADMISSION.busy()&&CallTryOnService.ADMISSION.reserve()==0,"cancelled queued FGS keeps reservation until its own destruction");
  check(CallTryOnService.startupState(three)==CallTryOnService.STARTUP_ENDED,"a cancelled queued FGS never starts on late delivery");
  CallTryOnService.ADMISSION.release(three);long four=CallTryOnService.ADMISSION.reserve();
  CallTryOnService.cancelStartup(three);check(CallTryOnService.ADMISSION.accepted(four),"late cancellation after release cannot affect the next launch");
  for(int i=0;i<500;i++) {
   StartAdmission admission=new StartAdmission();long old=admission.reserve();
   Thread staleCancel=new Thread(()->admission.cancel(old));staleCancel.start();admission.release(old);long next=admission.reserve();staleCancel.join();
   check(admission.accepted(next),"cancel(generation) is atomic against release/reserve");
  }
  System.out.println("Actual service startup ACK/cancel checks: "+checks);
 }
}
