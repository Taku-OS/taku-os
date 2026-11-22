#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compile the actual system preview adapter against deterministic Android doubles.

No device, camera, native library, host service or model is contacted.
"""
from pathlib import Path
import os, subprocess, tempfile, sys, xml.etree.ElementTree as ET

MODULE = Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[2]
SRC = MODULE/'app/src/org/taku/tryon/realtime'
manifest = ET.parse(MODULE/'app/AndroidManifest.xml').getroot()
ANDROID = '{http://schemas.android.com/apk/res/android}'
assert any(x.get(ANDROID+'name') == 'org.agentos.surfaces.permission.HOST_ISLAND_VIEW' for x in manifest.findall('uses-permission'))
assert any(x.get(ANDROID+'name') == 'org.agentos.assistant' for x in manifest.findall('./queries/package'))

files = {
'android/content/ComponentName.java':r'''package android.content;
public final class ComponentName {
 final String pkg,cls;public ComponentName(String p,String c){pkg=p;cls=c;}
 public String getPackageName(){return pkg;}public String getClassName(){return cls;}
 public boolean equals(Object x){return x instanceof ComponentName&&pkg.equals(((ComponentName)x).pkg)&&cls.equals(((ComponentName)x).cls);}
 public int hashCode(){return pkg.hashCode()+cls.hashCode();}
}''',
'android/content/Intent.java':r'''package android.content;public class Intent{public ComponentName component;public Intent setComponent(ComponentName c){component=c;return this;}}''',
'android/content/ServiceConnection.java':r'''package android.content;import android.os.IBinder;
public interface ServiceConnection{void onServiceConnected(ComponentName n,IBinder b);void onServiceDisconnected(ComponentName n);default void onBindingDied(ComponentName n){}default void onNullBinding(ComponentName n){}}''',
'android/content/Context.java':r'''package android.content;
import android.content.pm.*;import android.os.*;import android.hardware.display.DisplayManager;import android.view.*;
public class Context{
 public static final int BIND_AUTO_CREATE=1;public int permission=0,binds,unbinds;public boolean accepted=true,throwBind;
 public PackageManager packages=new PackageManager();public WindowManager windows=new WindowManager();public DisplayManager displays=new DisplayManager();
 public ServiceConnection connection;public Intent intent;public int flags;public Runnable inline;
 public int checkSelfPermission(String p){if(!p.equals("org.agentos.surfaces.permission.HOST_ISLAND_VIEW"))throw new AssertionError(p);return permission;}
 public PackageManager getPackageManager(){return packages;}
 public <T>T getSystemService(Class<T> c){return c.cast(c==WindowManager.class?windows:displays);}
 public Context createDisplayContext(Display d){return this;}
 public boolean bindService(Intent i,ServiceConnection c,int f){binds++;intent=i;connection=c;flags=f;if(throwBind)throw new IllegalStateException();if(inline!=null)inline.run();return accepted;}
 public void unbindService(ServiceConnection c){if(c!=connection)throw new AssertionError();unbinds++;}
}''',
'android/content/pm/ApplicationInfo.java':r'''package android.content.pm;public class ApplicationInfo{public int uid=10194;public boolean enabled=true;}''',
'android/content/pm/ServiceInfo.java':r'''package android.content.pm;public class ServiceInfo{public boolean enabled=true,exported=true;public String packageName="org.agentos.assistant",name="org.agentos.surfaces.IslandViewHostService",permission="org.agentos.surfaces.permission.HOST_ISLAND_VIEW";public ApplicationInfo applicationInfo=new ApplicationInfo();}''',
'android/content/pm/PackageManager.java':r'''package android.content.pm;import android.content.ComponentName;
public class PackageManager{
 public static final int PERMISSION_GRANTED=0,SIGNATURE_MATCH=0;public boolean missing,broken;public int signature=0,queries;public ServiceInfo service=new ServiceInfo();
 public static class NameNotFoundException extends Exception{}
 public ServiceInfo getServiceInfo(ComponentName c,int flags)throws NameNotFoundException{queries++;if(!c.getPackageName().equals("org.agentos.assistant")||!c.getClassName().equals("org.agentos.surfaces.IslandViewHostService")||flags!=0)throw new AssertionError("wrong component");if(missing)throw new NameNotFoundException();if(broken)throw new IllegalStateException();return service;}
 public int checkSignatures(String a,String b){if(!a.equals("android")||!b.equals("org.agentos.assistant"))throw new AssertionError("wrong signatures");return signature;}
}''',
'android/graphics/Rect.java':r'''package android.graphics;public class Rect{public int left,top,right,bottom;public Rect(){}public Rect(int l,int t,int r,int b){set(l,t,r,b);}public void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public int width(){return right-left;}public int height(){return bottom-top;}}''',
'android/graphics/Region.java':r'''package android.graphics;public class Region{public Rect bounds;public Region(){}public Region(Rect r){bounds=new Rect(r.left,r.top,r.right,r.bottom);}public boolean empty(){return bounds==null;}}''',
'android/hardware/display/DisplayManager.java':r'''package android.hardware.display;import android.view.Display;public class DisplayManager{public Display display=new Display();public Display getDisplay(int id){return id==0?display:null;}}''',
'android/os/IBinder.java':r'''package android.os;public interface IBinder{}''',
'android/os/RemoteException.java':r'''package android.os;public class RemoteException extends Exception{}''',
'android/os/Looper.java':r'''package android.os;public class Looper{static final Looper MAIN=new Looper();public static Looper getMainLooper(){return MAIN;}}''',
'android/os/Bundle.java':r'''package android.os;import java.util.*;public class Bundle extends HashMap<String,Object>{
 public void putInt(String k,int v){put(k,v);}public void putFloat(String k,float v){put(k,v);}public void putBoolean(String k,boolean v){put(k,v);}public void putParcelable(String k,Object v){put(k,v);}public void putBinder(String k,IBinder v){put(k,v);}
 public IBinder getBinder(String k){return (IBinder)get(k);}public int getInt(String k,int d){return containsKey(k)?(Integer)get(k):d;}
}''',
'android/os/Message.java':r'''package android.os;public class Message{public int what,sendingUid;public Messenger replyTo;private Bundle data=new Bundle();public static Message obtain(Object ignored,int what){Message m=new Message();m.what=what;return m;}public Bundle getData(){return data;}public void setData(Bundle b){data=b;}}''',
'android/os/Handler.java':r'''package android.os;import java.util.*;
public class Handler{
 public interface Callback{boolean handleMessage(Message m);}public final Callback callback;public Handler(Looper l){callback=null;}public Handler(Looper l,Callback c){callback=c;}
 static class Task{Runnable r;long due;Task(Runnable r,long d){this.r=r;due=d;}}static final List<Task> tasks=new ArrayList<>();static long clock;
 public boolean postDelayed(Runnable r,long d){tasks.add(new Task(r,clock+d));return true;}public boolean post(Runnable r){return postDelayed(r,0);}
 public void removeCallbacks(Runnable r){tasks.removeIf(t->t.r==r);}
 public static void reset(){tasks.clear();clock=0;}
 public static void advance(long d){long end=clock+d;int limit=10000;while(true){Task next=null;for(Task t:tasks)if(t.due<=end&&(next==null||t.due<next.due))next=t;if(next==null)break;if(--limit==0)throw new AssertionError("unbounded actor");tasks.remove(next);clock=next.due;next.r.run();}clock=end;}
}''',
'android/os/Messenger.java':r'''package android.os;
public class Messenger{
 public interface Endpoint extends IBinder{void send(Message m)throws RemoteException;}public final IBinder binder;
 public Messenger(Handler h){binder=(Endpoint)m->{h.callback.handleMessage(m);};}public Messenger(IBinder b){binder=b;}
 public void send(Message m)throws RemoteException{((Endpoint)binder).send(m);}public IBinder getBinder(){return binder;}
}''',
'android/view/Display.java':r'''package android.view;public class Display{public static final int DEFAULT_DISPLAY=0;}''',
'android/view/Gravity.java':r'''package android.view;public class Gravity{public static final int TOP=48,LEFT=3;}''',
'android/view/AttachedSurfaceControl.java':r'''package android.view;import android.graphics.Region;public class AttachedSurfaceControl{public Region region=new Region();public void setTouchableRegion(Region r){region=r;}}''',
'android/view/View.java':r'''package android.view;import android.content.Context;public class View{
 public boolean attached;public Object params,parent;public View(){}public View(Context c){}public boolean isAttachedToWindow(){return attached;}public void setLayoutParams(Object p){params=p;}
 protected void onAttachedToWindow(){}protected void onLayout(boolean c,int l,int t,int r,int b){}public void attached(boolean v){attached=v;if(v)onAttachedToWindow();}
}''',
'android/widget/FrameLayout.java':r'''package android.widget;import android.view.*;import android.content.Context;import java.util.*;
public class FrameLayout extends View{
 public final List<View> children=new ArrayList<>();public final AttachedSurfaceControl control=new AttachedSurfaceControl();
 public FrameLayout(Context c){super(c);}public void setClipChildren(boolean c){}public void addView(View v){if(v.parent!=null)throw new AssertionError("reparent");children.add(v);v.parent=this;}
 public AttachedSurfaceControl getRootSurfaceControl(){return control;}
 @Override public void attached(boolean v){super.attached(v);for(View child:children)child.attached(v);}
 public static class LayoutParams{public int width,height,gravity,leftMargin,topMargin;public LayoutParams(int w,int h,int g){width=w;height=h;gravity=g;}}
}''',
'android/view/WindowManager.java':r'''package android.view;import android.graphics.Rect;
public class WindowManager{
 public int adds,updates,removes;public Rect bounds=new Rect(0,0,1080,2424);public Metrics getMaximumWindowMetrics(){return new Metrics(bounds);}
 public void addView(View v,LayoutParams p){adds++;v.attached(true);}public void updateViewLayout(View v,LayoutParams p){updates++;}public void removeViewImmediate(View v){removes++;v.attached(false);}
 public static class Metrics{final Rect bounds;Metrics(Rect b){bounds=b;}public Rect getBounds(){return bounds;}}
 public static class LayoutParams{public static final int FLAG_NOT_TOUCHABLE=16;public int x,y,width=1,height=1,flags;public float alpha=1;public void copyFrom(LayoutParams p){x=p.x;y=p.y;width=p.width;height=p.height;flags=p.flags;alpha=p.alpha;}}
}''',
'android/view/SurfaceControlViewHost.java':r'''package android.view;import android.content.Context;import android.os.IBinder;import android.widget.FrameLayout;
public class SurfaceControlViewHost{
 public static int created,releases;public static boolean autoAttach=true,noSurface;public static SurfaceControlViewHost last;
 public FrameLayout stage;public int width,height,relayouts;public SurfacePackage surface=new SurfacePackage();
 public SurfaceControlViewHost(Context c,Display d,IBinder token){created++;last=this;}
 public void setView(FrameLayout v,int w,int h){stage=v;width=w;height=h;if(autoAttach)v.attached(true);}
 public void relayout(int w,int h){width=w;height=h;relayouts++;}public SurfacePackage getSurfacePackage(){return noSurface?null:surface;}
 public void release(){releases++;if(stage!=null)stage.attached(false);}
 public static void reset(){created=0;releases=0;autoAttach=true;noSurface=false;last=null;}
 public static class SurfacePackage{public int releases;public void release(){releases++;}}
}''',
'org/taku/tryon/realtime/SystemIslandHostTest.java':r'''package org.taku.tryon.realtime;
import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import android.graphics.*;import java.util.*;
public final class SystemIslandHostTest{
 static int checks;static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
 static WindowManager.LayoutParams geometry(int x,int y,int w,int h){var p=new WindowManager.LayoutParams();p.x=x;p.y=y;p.width=w;p.height=h;return p;}
 static class Peer implements Messenger.Endpoint{
  final List<Message> messages=new ArrayList<>();Messenger inbox;int failWhat=-1;boolean inlineReady;
  public void send(Message m)throws RemoteException{if(m.what==failWhat)throw new RemoteException();messages.add(m);inbox=m.replyTo;if(inlineReady&&m.what==1)ready(10194,new IBinder(){});}
  void ready(int uid,IBinder token)throws RemoteException{Message m=Message.obtain(null,10);m.sendingUid=uid;Bundle b=new Bundle();b.putBinder("token",token);b.putInt("display",0);m.setData(b);inbox.send(m);}
  void error()throws RemoteException{Message m=Message.obtain(null,11);m.sendingUid=10194;inbox.send(m);}
  long count(int what){return messages.stream().filter(x->x.what==what).count();}
 }
 static class Fixture{
  final Context c=new Context();final View root=new View();final Peer peer=new Peer();final SystemIslandPreviewHost host=new SystemIslandPreviewHost(c,root);final List<String> errors=new ArrayList<>();int ready;
  Fixture(){Handler.reset();SurfaceControlViewHost.reset();ShoppingPreviewHost.installFactory(null);}
  void attach(){host.attach(geometry(402,28,341,116),()->ready++,errors::add);}
  void connect(){c.connection.onServiceConnected(SystemIslandPreviewHost.COMPONENT,peer);}
  void close(){host.close();host.close();}
 }
 public static void main(String[] args)throws Exception{
  availability();normalLifecycle();pendingGeometry();timeoutAndLateMessages();waitsForRealAttach();failures();reentrantBindClose();
  System.out.println("System preview host actual-adapter checks: "+checks+"; deterministic Android doubles, no device/camera/model");
 }
 static void availability(){
  check(!SystemIslandPreviewHost.available(null),"null context unavailable");
  for(String reason:new String[]{"permission","missing","disabled","app_disabled","unexported","signature","package","class","binding_permission","null_app","broken"}){
   Context c=new Context();switch(reason){case "permission"->c.permission=-1;case "missing"->c.packages.missing=true;case "disabled"->c.packages.service.enabled=false;case "app_disabled"->c.packages.service.applicationInfo.enabled=false;case "unexported"->c.packages.service.exported=false;case "signature"->c.packages.signature=-1;case "package"->c.packages.service.packageName="untrusted";case "class"->c.packages.service.name="WrongService";case "binding_permission"->c.packages.service.permission="other";case "null_app"->c.packages.service.applicationInfo=null;case "broken"->c.packages.broken=true;}
   check(!SystemIslandPreviewHost.available(c),"host not trusted: "+reason);check("application_overlay".equals(ShoppingPreviewHost.create(c,new View()).id()),"fallback remains ordinary overlay: "+reason);check(c.binds==0,"availability cannot bind: "+reason);
  }
  Context c=new Context();check(SystemIslandPreviewHost.available(c),"exact platform host and held permission accepted");check("system_island".equals(ShoppingPreviewHost.create(c,new View()).id()),"default uses original system host");
  ShoppingPreviewHost explicit=new ShoppingPreviewHost.ApplicationOverlay(c,new View());ShoppingPreviewHost.installFactory((a,b)->explicit);check(ShoppingPreviewHost.create(c,new View())==explicit,"explicit custom factory retains priority");
  ShoppingPreviewHost.installFactory((a,b)->null);check("application_overlay".equals(ShoppingPreviewHost.create(c,new View()).id()),"explicit null factory retains previous fallback contract");ShoppingPreviewHost.installFactory(null);
  c.permission=-1;int[] ready={0};View root=new View();ShoppingPreviewHost fallback=ShoppingPreviewHost.create(c,root);fallback.attach(geometry(0,0,20,20),()->ready[0]++,x->{throw new AssertionError(x);});fallback.update(geometry(0,0,30,30));fallback.close();fallback.close();check(ready[0]==1&&c.windows.adds==1&&c.windows.updates==1&&c.windows.removes==1,"ordinary host lifecycle still works exactly once");
 }
 static void normalLifecycle()throws Exception{
  Fixture f=new Fixture();f.attach();check(f.c.binds==1&&f.c.flags==Context.BIND_AUTO_CREATE&&SystemIslandPreviewHost.COMPONENT.equals(f.c.intent.component),"bind exact old component");check(f.ready==0&&f.errors.isEmpty(),"bind alone never acknowledges attached preview");
  f.connect();check(f.peer.count(1)==1&&f.ready==0,"OPEN precedes rendering and camera-ready callback");
  f.peer.ready(10001,new IBinder(){});check(SurfaceControlViewHost.created==0,"foreign UID cannot publish a surface token");
  f.peer.ready(10194,new IBinder(){});check(f.ready==1&&f.errors.isEmpty()&&f.root.isAttachedToWindow(),"READY and real attached root report readiness");
  check(f.peer.count(2)==1&&SurfaceControlViewHost.last.surface.releases==1,"original SURFACE sent with one temporary package release");
  check(f.c.windows.adds==0,"system host never adds a privileged app overlay");
  FrameLayout.LayoutParams placed=(FrameLayout.LayoutParams)f.root.params;check(placed.leftMargin==402&&placed.topMargin==28&&placed.width==341&&placed.height==116,"same root tree placed in full display surface");Object parent=f.root.parent;
  f.peer.ready(10194,new IBinder(){});check(f.ready==1&&SurfaceControlViewHost.created==1,"duplicate READY cannot replace camera root");
  var p=geometry(410,30,300,100);f.host.update(p);check(f.peer.count(3)==1&&f.root.parent==parent,"layout update never reparents root");
  var control=SurfaceControlViewHost.last.stage.control;check(!control.region.empty()&&control.region.bounds.left==410&&control.region.bounds.top==30,"actual embedded input region follows geometry");
  p.alpha=0;f.host.update(p);check(control.region.empty(),"capture redaction removes input region");p.alpha=1;p.flags=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;f.host.update(p);check(control.region.empty(),"disabled touch removes input region");p.flags=0;f.host.update(p);check(!control.region.empty(),"input region restores without a new surface");
  f.c.windows.bounds=new Rect(0,0,720,1280);p.x=999;p.width=1000;f.host.update(p);placed=(FrameLayout.LayoutParams)f.root.params;check(SurfaceControlViewHost.last.relayouts==1&&placed.width==720&&placed.leftMargin==0,"display change relayouts and clamps within host bounds");
  f.close();check(f.c.unbinds==1&&SurfaceControlViewHost.releases==1&&f.peer.count(4)==1,"close releases bind, child surface and host session once");f.peer.ready(10194,new IBinder(){});f.host.update(p);Handler.advance(6000);check(f.ready==1&&f.errors.isEmpty()&&SurfaceControlViewHost.created==1,"late READY/update/deadline cannot restart a closed preview");
 }
 static void pendingGeometry()throws Exception{
  Fixture f=new Fixture();f.attach();var p=geometry(11,22,333,444);p.alpha=0;p.flags=16;f.host.update(p);f.connect();Bundle b=f.peer.messages.get(0).getData();check((Integer)b.get("x")==11&&(Integer)b.get("height")==444&&(Float)b.get("alpha")==0f&&(Boolean)b.get("hidden"),"pending updates reach original OPEN geometry");f.peer.ready(10194,new IBinder(){});check(SurfaceControlViewHost.last.stage.control.region.empty(),"initial hidden state is applied before ready callback");f.close();
 }
 static void timeoutAndLateMessages()throws Exception{
  Fixture f=new Fixture();f.attach();Handler.advance(4999);check(f.errors.isEmpty(),"bind deadline remains five seconds");Handler.advance(1);check(f.errors.equals(List.of("system_island_timeout"))&&f.c.unbinds==1,"unanswered bind fails closed at five seconds");f.connect();check(f.peer.messages.isEmpty()&&f.ready==0,"late service connection cannot send OPEN or start camera");f.close();check(f.c.unbinds==1,"timeout plus close remains idempotent");
  f=new Fixture();f.attach();f.connect();f.close();f.peer.ready(10194,new IBinder(){});Handler.advance(6000);check(f.ready==0&&f.errors.isEmpty()&&SurfaceControlViewHost.created==0&&f.c.unbinds==1,"close before READY rejects delayed host token");
 }
 static void waitsForRealAttach()throws Exception{
  Fixture f=new Fixture();SurfaceControlViewHost.autoAttach=false;f.attach();f.connect();f.peer.ready(10194,new IBinder(){});Handler.advance(100);check(f.ready==0&&f.peer.count(2)==1,"surface submission alone is not actual view attachment");f.root.attached(true);Handler.advance(16);check(f.ready==1,"actual attach releases ready callback");Handler.advance(6000);check(f.errors.isEmpty(),"successful attach cancels deadline");f.close();
  f=new Fixture();SurfaceControlViewHost.autoAttach=false;f.attach();f.connect();f.peer.ready(10194,new IBinder(){});f.close();f.root.attached(true);Handler.advance(6000);check(f.ready==0&&f.errors.isEmpty()&&SurfaceControlViewHost.releases==1,"close removes pending attach callback and child surface");
  f=new Fixture();SurfaceControlViewHost.autoAttach=false;f.attach();f.connect();f.peer.ready(10194,new IBinder(){});Handler.advance(5000);check(f.ready==0&&f.errors.equals(List.of("system_island_timeout"))&&SurfaceControlViewHost.releases==1,"unattached surface is bounded by original five-second deadline");
 }
 static void failures()throws Exception{
  Fixture f=new Fixture();f.c.accepted=false;f.attach();check(f.errors.equals(List.of("system_island_unavailable"))&&f.c.unbinds==0,"rejected bind reports fixed unavailable code");
  f=new Fixture();f.c.throwBind=true;f.attach();check(f.errors.equals(List.of("system_island_bind_failed")),"binding exception never leaks raw upstream detail");
  f=new Fixture();f.attach();f.c.packages.signature=-1;f.connect();check(f.errors.equals(List.of("system_island_unavailable"))&&f.c.unbinds==1&&f.peer.messages.isEmpty(),"host trust rechecked at connection time");
  f=new Fixture();f.attach();f.connect();f.peer.ready(10194,null);check(f.errors.equals(List.of("system_island_invalid_display"))&&f.ready==0,"missing token cannot start camera");
  f=new Fixture();f.attach();f.connect();f.c.displays.display=null;f.peer.ready(10194,new IBinder(){});check(f.errors.equals(List.of("system_island_invalid_display")),"missing display fails without native work");
  f=new Fixture();f.attach();f.connect();f.peer.error();f.peer.error();check(f.errors.equals(List.of("system_island_error"))&&f.c.unbinds==1,"host error is bounded and cleanup happens once");
  f=new Fixture();f.attach();f.peer.failWhat=1;f.connect();check(f.errors.equals(List.of("system_island_connection_lost"))&&f.ready==0&&f.c.unbinds==1,"OPEN send failure closes bind");
  f=new Fixture();f.attach();f.connect();f.peer.failWhat=2;f.peer.ready(10194,new IBinder(){});check(f.errors.equals(List.of("system_island_connection_lost"))&&f.ready==0&&SurfaceControlViewHost.releases==1&&SurfaceControlViewHost.last.surface.releases==1,"SURFACE send failure releases both resources without ready");
  f=new Fixture();f.attach();f.connect();SurfaceControlViewHost.noSurface=true;f.peer.ready(10194,new IBinder(){});check(f.errors.equals(List.of("system_island_missing_surface"))&&SurfaceControlViewHost.releases==1,"missing surface package cannot acknowledge startup");
 }
 static void reentrantBindClose()throws Exception{
  Fixture f=new Fixture();f.peer.inlineReady=true;f.c.inline=()->f.c.connection.onServiceConnected(SystemIslandPreviewHost.COMPONENT,f.peer);f.host.attach(geometry(0,0,100,100),()->{f.ready++;f.host.close();},f.errors::add);check(f.ready==1&&f.c.unbinds==1&&SurfaceControlViewHost.releases==1,"even inline bind ready-close releases accepted connection once");
 }
}'''
}
home = os.environ.get('JAVA_HOME')
javac = str(Path(home)/'bin/javac') if home else 'javac'
java = str(Path(home)/'bin/java') if home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-original-system-host-') as temporary:
    out = Path(temporary)
    for relative, body in files.items():
        target = out/relative; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(body)
    sources = [str(out/name) for name in files]
    sources += [str(SRC/name) for name in ('ShoppingPreviewHost.java','SystemIslandPreviewHost.java')]
    subprocess.run([javac,'--release','17','-encoding','UTF-8','-d',temporary,*sources],check=True)
    subprocess.run([java,'-cp',temporary,'org.taku.tryon.realtime.SystemIslandHostTest'],check=True,timeout=15)
