#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compile actual Service notification/publication methods and the actual action receiver.

Android objects are deterministic doubles: no device, camera, transport or model runs.
An optional module argument also exercises a frozen device-build source tree.
"""
from pathlib import Path
import os,re,subprocess,sys,tempfile

MODULE=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[2]
source=(MODULE/'app/src/org/taku/tryon/realtime/CallTryOnService.java').read_text()
receiver=(MODULE/'app/src/org/taku/tryon/realtime/TryOnActionReceiver.java').read_text()

def method(name):
    match=re.search(r'^    (?:@Override )?(?:private |public )?(?:static )?[\w<>]+ '+name+r'\([^\n]*\)\s*\{',source,re.M)
    assert match,name
    at=source.index('{',match.start())+1;depth=1
    while depth:
        depth+=(source[at]=='{')-(source[at]=='}');at+=1
    return source[match.start():at]

# Keep the real foreground start and independent failure result path connected.
assert 'startCameraForeground();' in method('onStartCommand')
assert 'TryOnResultWindow.show(this,detail)' in method('finish')
assert 'IslandPublisher' not in source
assert 'taku.island.v1' not in source

files={
'android/content/Context.java':r'''package android.content;
import android.app.*;
public class Context {
 public static final int RECEIVER_NOT_EXPORTED=4;
 public NotificationManager manager=new NotificationManager();
 public <T>T getSystemService(Class<T> type){return type.cast(manager);}
 public void registerReceiver(Object value,IntentFilter filter,int flags){if(flags!=RECEIVER_NOT_EXPORTED)throw new AssertionError("exported receiver");}
}''',
'android/content/Intent.java':r'''package android.content;
import java.util.*;
public class Intent {
 public static final String ACTION_SCREEN_OFF="screen_off";
 public Class<?> target;private String action;private final Map<String,String> extras=new HashMap<>();
 public Intent(){} public Intent(Context c,Class<?> target){this.target=target;}
 public Intent setAction(String value){action=value;return this;}public String getAction(){return action;}
 public String getStringExtra(String key){return extras.get(key);}public Intent putExtra(String key,String value){extras.put(key,value);return this;}
}''',
'android/content/IntentFilter.java':'package android.content; public class IntentFilter{public IntentFilter(String value){}}',
'android/content/BroadcastReceiver.java':'package android.content; public abstract class BroadcastReceiver{public abstract void onReceive(Context context,Intent intent);}',
'android/app/Service.java':r'''package android.app;
import android.content.*;
public class Service extends Context {
 public Notification foreground;public int foregroundId,foregroundType;
 public void onCreate(){}
 public void startForeground(int id,Notification n,int type){foregroundId=id;foreground=n;foregroundType=type;manager.notifications.put("camera:"+id,n);}
}''',
'android/app/PendingIntent.java':r'''package android.app;
import android.content.*;
public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;
 public Intent intent;public int flags,request;
 public static PendingIntent getBroadcast(Context c,int request,Intent i,int flags){PendingIntent p=new PendingIntent();p.intent=i;p.flags=flags;p.request=request;return p;}
}''',
'android/app/NotificationChannel.java':r'''package android.app;
public class NotificationChannel {public String id,name;public int importance;public NotificationChannel(String id,String name,int importance){this.id=id;this.name=name;this.importance=importance;}}''',
'android/app/NotificationManager.java':r'''package android.app;
import java.util.*;
public class NotificationManager {
 public static final int IMPORTANCE_LOW=2;
 public final Map<String,Notification> notifications=new HashMap<>();public final List<String> cancelled=new ArrayList<>();public int notifyCalls;public Notification last;public NotificationChannel channel;
 public void cancel(String tag,int id){String key=tag+":"+id;cancelled.add(key);notifications.remove(key);}
 public void createNotificationChannel(NotificationChannel channel){this.channel=channel;}
 public void notify(int id,Notification n){notifyCalls++;last=n;notifications.put("camera:"+id,n);}
}''',
'android/app/Notification.java':r'''package android.app;
import android.content.*;import java.util.*;
public class Notification {
 public static final int VISIBILITY_PRIVATE=0;
 public String channel,title,text,bigText;public boolean ongoing,onlyAlertOnce;public int visibility;public PendingIntent open;public final List<Action> actions=new ArrayList<>();public final Map<String,Object> extras=new HashMap<>();
 public static class Builder {
  private final Notification value=new Notification();public Builder(Context c,String channel){value.channel=channel;}
  public Builder setSmallIcon(int icon){return this;}public Builder setContentTitle(String s){value.title=s;return this;}public Builder setContentText(String s){value.text=s;return this;}
  public Builder setStyle(BigTextStyle s){value.bigText=s.text;return this;}public Builder setOngoing(boolean s){value.ongoing=s;return this;}public Builder setOnlyAlertOnce(boolean s){value.onlyAlertOnce=s;return this;}
  public Builder setVisibility(int s){value.visibility=s;return this;}public Builder setContentIntent(PendingIntent p){value.open=p;return this;}public Builder addAction(Action a){value.actions.add(a);return this;}public Notification build(){return value;}
 }
 public static class BigTextStyle {String text;public BigTextStyle bigText(String s){text=s;return this;}}
 public static class Action {public String label;public PendingIntent intent;public static class Builder {private final Action value=new Action();public Builder(Object icon,String label,PendingIntent p){value.label=label;value.intent=p;}public Action build(){return value;}}}
}''',
'android/content/pm/ServiceInfo.java':'package android.content.pm; public class ServiceInfo {public static final int FOREGROUND_SERVICE_TYPE_CAMERA=64;}',
'android/os/SystemClock.java':'package android.os; public class SystemClock {public static long elapsedRealtime(){return 25000;}}',
'android/R.java':'package android; public class R {public static class drawable {public static final int ic_menu_camera=1;}}',
'org/taku/tryon/realtime/TryOnActionReceiver.java':receiver,
}

fixture=r'''package org.taku.tryon.realtime;
import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.os.SystemClock;
import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
public final class CallTryOnService extends Service {
 private static CallTryOnService current;private static final Admission ADMISSION=new Admission();private long admissionGeneration;
 private final Object screen=new Object();private boolean foregroundStarted;private String notifiedDetail;
 private final AtomicBoolean terminating=new AtomicBoolean();private long began,inputs=4,outputs=2;private String state="starting",detail="Preparing reference image…",code="none";
 private Window window;private Journal journal;private static Snapshot last;private static final List<String> controls=new ArrayList<>();
 static boolean control(String action){controls.add(action);return current!=null&&current.window!=null&&!current.terminating.get();}
'''+ '\n'.join(method(n) for n in ('onCreate','expandAction','startCameraForeground','cameraNotification','stopAction','publish'))+r'''
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void safe(Notification n){
  check(n.extras.isEmpty(),"ordinary notification has no island payload or alternate expanded template");
  check(n.ongoing&&n.onlyAlertOnce&&n.visibility==Notification.VISIBILITY_PRIVATE,"camera foreground notification retains quiet ongoing/private behavior");
  check(n.actions.size()==1&&"Stop try-on".equals(n.actions.get(0).label),"only original ordinary stop action remains, no Expand button");
  for(PendingIntent p:List.of(n.open,n.actions.get(0).intent))check(p.intent.target==TryOnActionReceiver.class&&p.flags==(PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT),"all callbacks remain explicit immutable app-owned broadcasts");
  check("expand".equals(n.open.intent.getAction())&&"stop".equals(n.actions.get(0).intent.intent.getAction()),"notification click returns to existing window; stop closes existing attempt");
 }
 public static void main(String[] ignored){
  CallTryOnService s=new CallTryOnService();Notification stale=new Notification();s.manager.notifications.put("tryon:1",stale);s.manager.notifications.put("tryon-result:1",stale);
  s.onCreate();check(s.manager.cancelled.equals(List.of("tryon:1"))&&!s.manager.notifications.containsKey("tryon:1"),"onCreate removes old generic card");check(s.manager.notifications.containsKey("tryon-result:1"),"independent failure result card is untouched");check(current==s&&s.admissionGeneration==7,"current service and admission generation remain authoritative");
  s.window=new Window();s.journal=new Journal();s.publish();check(last.active&&last.sourceFrames==4&&last.returnedFrames==2&&last.presentation.equals("compact"),"API snapshot preserves actual compact host and counters");check(s.window.renders==1&&s.window.latest==last&&s.journal.progress==1,"renderer and diagnostics still receive snapshot updates");check(s.manager.notifyCalls==0,"no pre-foreground publication creates another UI");
  s.startCameraForeground();check(s.foregroundId==401&&s.foregroundType==ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,"actual foreground helper starts required camera FGS notification");check(s.manager.channel.importance==NotificationManager.IMPORTANCE_LOW&&s.manager.channel.id.equals("tryon-camera"),"ordinary channel remains low priority");check(s.foreground.title.equals("Taku Live Try-On")&&s.foreground.text.equals(s.detail),"old notification identity and current preparation text are preserved");safe(s.foreground);
  for(String mode:List.of("compact","preview","fullscreen")){
   s.window.mode=mode;s.state="running";s.detail="Live try-on";int before=s.manager.notifyCalls;s.publish();
   check(last.presentation.equals(mode)&&s.window.latest==last,"compact/preview/fullscreen use the real window state");check(s.manager.notifyCalls==before+(before==0?1:0),"frame publication updates ordinary notification only when detail changes");safe(s.manager.last);
  }
  int before=s.manager.notifyCalls;s.outputs++;s.publish();check(s.manager.notifyCalls==before&&last.returnedFrames==3,"video counter progress does not repost notifications each frame");
  TryOnActionReceiver receiver=new TryOnActionReceiver();receiver.onReceive(s,null);receiver.onReceive(s,new Intent().setAction("unknown"));check(controls.isEmpty()&&TryOnController.stops==0,"null/unknown callbacks cannot start or stop anything");receiver.onReceive(s,s.foreground.open.intent);check(controls.equals(List.of("expand"))&&TryOnController.stops==0,"ordinary notification click only expands existing window");receiver.onReceive(s,s.foreground.actions.get(0).intent.intent);check(TryOnController.stops==1,"ordinary Stop callback still reaches shared Controller");
  s.terminating.set(true);s.state="stopped";s.window.mode="closed";int renders=s.window.renders,progress=s.journal.progress;s.publish();check(!last.active&&last.presentation.equals("closed")&&s.manager.notifyCalls==before,"terminal snapshot does not publish an ended card");check(s.window.renders==renders&&s.journal.progress==progress,"closed attempt does not mutate preview or active diagnostic progress");
  s.state="failed";s.detail="Cloud failure";s.publish();check(s.manager.notifyCalls==before&&!last.active&&last.state.equals("failed"),"failure remains available to status without a generic duplicate card");
  System.out.println("Camera notification actual-method checks: "+checks);
 }
 static final class Admission{long current(){return 7;}}
 static final class Snapshot{final String state;final boolean active;final long sourceFrames,returnedFrames;final Object presentation;Snapshot(String s,String d,String c,boolean a,long r,long input,long output,Object p){state=s;active=a;sourceFrames=input;returnedFrames=output;presentation=p;}}
 static final class Window{String mode="compact";int renders;Snapshot latest;String presentation(){return mode;}String presentationState(){return mode;}void render(Snapshot s){renders++;latest=s;}}
 static final class Journal{int progress;void progress(String phase,String state,long input,long output,int recovery){progress++;}}
}
class ShoppingPreviewState {static String closed(){return "closed";}}
class TryOnController {static int stops;static void stop(Context context){stops++;}}
class TryOnResultWindow {static final String ACTION="result";static int calls;static void act(Context context,String token,String action){calls++;}}
'''
if 'TryOnResultWindow.ACTION' in receiver:
    fixture=fixture.replace('  s.terminating.set(true);',
        '  receiver.onReceive(s,new Intent().setAction(TryOnResultWindow.ACTION).putExtra("resultToken","fixture-token").putExtra("resultAction","close"));check(TryOnResultWindow.calls==1&&TryOnController.stops==1,"independent failure-card callback is preserved");\n  s.terminating.set(true);')
files['org/taku/tryon/realtime/CallTryOnService.java']=fixture
home=os.environ.get('JAVA_HOME');javac=str(Path(home)/'bin/javac') if home else 'javac';java=str(Path(home)/'bin/java') if home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-camera-notification-') as temporary:
    target=Path(temporary);sources=[]
    for relative,text in files.items():
        path=target/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);sources.append(str(path))
    subprocess.run([javac,'--release','17','-encoding','UTF-8','-d',temporary,*sources],check=True)
    subprocess.run([java,'-cp',temporary,'org.taku.tryon.realtime.CallTryOnService'],check=True,timeout=15)
