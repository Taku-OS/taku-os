#!/usr/bin/env python3
"""Exercise the production Service control methods with deterministic main-loop/window doubles."""
from pathlib import Path
import os,re,subprocess,tempfile
MODULE=Path(__file__).resolve().parents[2]
source=(MODULE/'app/src/org/taku/tryon/realtime/CallTryOnService.java').read_text()
def method(name):
    match=re.search(r'^    (?:private )?(?:static )?boolean '+name+r'\([^\n]*\)\s*\{',source,re.M)
    if not match:raise AssertionError(name)
    at=source.index('{',match.start())+1;depth=1
    while depth:
        depth+=(source[at]=='{')-(source[at]=='}');at+=1
    return source[match.start():at]
fixture=r'''package org.taku.tryon.realtime;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public final class CallTryOnService {
 private static final StartAdmission ADMISSION=new StartAdmission();
 private static final long PRESENTATION_RESPONSE_MS=1_000;
 private static volatile CallTryOnService current;
 final Handler main=new Handler();Window window;final AtomicBoolean terminating=new AtomicBoolean();boolean destroyed;int publications,finishes;String published="closed";
 void publish(){publications++;published=window.presentation;}
 void finish(String state,String detail,String code){finishes++;}
'''+ '\n'.join(method(n) for n in ['control','requestPresentation','applyPresentation'])+r'''
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static CallTryOnService fresh(){ADMISSION.release(ADMISSION.current());CallTryOnService s=new CallTryOnService();s.window=new Window();current=s;return s;}
 static void awaitQueued(CallTryOnService s)throws Exception{long until=System.nanoTime()+2_000_000_000L;while(s.main.queue.isEmpty()&&System.nanoTime()<until)Thread.sleep(1);check(!s.main.queue.isEmpty(),"Binder request queued on main");}
 public static void main(String[] ignored)throws Exception{
  Looper.current.set(Looper.MAIN);ExecutorService binder=Executors.newSingleThreadExecutor();
  try{
   current=null;check(!control("expand"),"no service returns false");
   CallTryOnService s=fresh();s.window=null;check(!control("expand")&&s.main.queue.isEmpty(),"main caller rejects missing host inline without self-wait");
   s=fresh();s.terminating.set(true);check(!control("fullscreen"),"terminating host rejects action");
   s=fresh();s.destroyed=true;check(!control("minimize"),"destroyed host rejects action");
   for(String action:new String[]{"minimize","expand","fullscreen"}){
    s=fresh();check(control(action),"main action reports actual acceptance");check(s.window.presentation.equals(action)&&s.published.equals(action)&&s.publications==1&&s.window.mainCalls==1,"main action and snapshot are updated before return");check(s.main.queue.isEmpty(),"main caller never waits for itself");
    s=fresh();Future<Boolean> response=binder.submit(()->control(action));awaitQueued(s);check(!response.isDone()&&s.publications==0,"Binder waits for actual main execution");s.main.drain();check(response.get(2,TimeUnit.SECONDS)&&s.published.equals(action)&&s.window.mainCalls==1,"Binder result observes updated window and snapshot");
   }
   for(String stale:new String[]{"window","terminating","destroyed","generation"}){
    s=fresh();Future<Boolean> response=binder.submit(()->control("expand"));awaitQueued(s);Window previous=s.window;
    if(stale.equals("window"))s.window=null;if(stale.equals("terminating"))s.terminating.set(true);if(stale.equals("destroyed"))s.destroyed=true;if(stale.equals("generation"))current=new CallTryOnService();
    s.main.drain();check(!response.get(2,TimeUnit.SECONDS)&&previous.mainCalls==0&&s.publications==0,"queued request rechecks current host: "+stale);
   }
   s=fresh();s.main.accept=false;check(!binder.submit(()->control("expand")).get(2,TimeUnit.SECONDS),"failed Handler post is rejected");
   s=fresh();Future<Boolean> response=binder.submit(()->control("fullscreen"));awaitQueued(s);Runnable stale=s.main.queue.peek();check(!response.get(2,TimeUnit.SECONDS)&&s.main.queue.isEmpty(),"bounded Binder wait cancels and removes queued request");stale.run();check(s.window.mainCalls==0&&s.publications==0,"timed-out queued task cannot mutate window later");
   s=fresh();final AtomicBoolean interrupted=new AtomicBoolean();Thread waiter=new Thread(()->{check(!control("minimize"),"interrupted Binder request rejected");interrupted.set(Thread.currentThread().isInterrupted());});waiter.start();awaitQueued(s);stale=s.main.queue.peek();waiter.interrupt();waiter.join(2000);check(!waiter.isAlive()&&interrupted.get()&&s.main.queue.isEmpty(),"interruption cancels task and preserves interrupt");stale.run();check(s.window.mainCalls==0,"interrupted queued request cannot execute later");
   s=fresh();s.window.fail=true;check(!control("expand")&&s.publications==0,"window error reports failure consistently on main");
   s=fresh();check(!control("unknown")&&s.window.mainCalls==0,"unknown control cannot claim success");
   current=null;long pending=ADMISSION.reserve();check(control("stop")&&!ADMISSION.accepted(pending)&&ADMISSION.busy(),"stop retains cancellation of pending launch without releasing admission");ADMISSION.release(pending);
   s=fresh();pending=ADMISSION.reserve();check(control("stop")&&!ADMISSION.accepted(pending)&&s.finishes==0,"stop still queues existing finish path");s.main.drain();check(s.finishes==1,"queued stop finish executes once");ADMISSION.release(pending);
   System.out.println("Service presentation control checks: "+checks);
  }finally{binder.shutdownNow();}
 }
 static final class Window {
  String presentation="preview";int mainCalls;boolean fail;
  void action(String value){if(Looper.myLooper()!=Looper.MAIN)throw new AssertionError("window accessed off main");if(fail)throw new IllegalStateException("gone");mainCalls++;presentation=value;}
  void setCollapsed(boolean value){action("minimize");}void expand(){action("expand");}void setFullscreen(boolean value){action("fullscreen");}
 }
}
class Looper {static final Looper MAIN=new Looper();static final ThreadLocal<Looper> current=new ThreadLocal<>();static Looper myLooper(){return current.get();}}
class Handler {final Queue<Runnable> queue=new ConcurrentLinkedQueue<>();boolean accept=true;Looper getLooper(){return Looper.MAIN;}boolean post(Runnable r){if(!accept)return false;queue.add(r);return true;}void removeCallbacks(Runnable r){queue.remove(r);}void drain(){if(Looper.myLooper()!=Looper.MAIN)throw new AssertionError("not main");Runnable r;while((r=queue.poll())!=null)r.run();}}
'''
home=os.environ.get('JAVA_HOME');javac=str(Path(home)/'bin/javac') if home else 'javac';java=str(Path(home)/'bin/java') if home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-service-presentation-') as temporary:
    target=Path(temporary);file=target/'CallTryOnService.java';file.write_text(fixture)
    subprocess.run([javac,'--release','17','-d',temporary,str(file),str(MODULE/'app/src/org/taku/tryon/realtime/StartAdmission.java')],check=True)
    subprocess.run([java,'-cp',temporary,'org.taku.tryon.realtime.CallTryOnService'],check=True,timeout=15)
