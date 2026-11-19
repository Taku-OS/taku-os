package org.agentos.memory;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.os.*;
import android.view.accessibility.*;
import org.agentos.surfaces.SurfaceSpec;
import org.agentos.surfaces.client.SurfaceClient;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Event-driven observations on user-selected apps. No timer capture, image retention or remote credentials. */
public final class ObservationService extends AccessibilityService {
 static volatile ObservationService current;
 final Handler main=new Handler(Looper.getMainLooper());
 final ExecutorService io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
 MemoryStore store;SurfaceClient surfaces;boolean connected;Runnable pending;String visibleId="";long lastOffer;
 @Override public void onServiceConnected(){
  current=this;store=MemoryStore.get(this);MemoryMaintenance.schedule(this);
  surfaces=new SurfaceClient(this,new SurfaceClient.Listener(){public void onConnected(){connected=true;store.prefs.edit().putString("surfaceStatus","connected").apply();}public void onEvent(String type,String id,long revision,String value){if(type.equals("disconnected"))connected=false;if(type.equals("error")){store.prefs.edit().putString("surfaceStatus","surface_unavailable").apply();store.changed();}}
   public void onAction(SurfaceClient.ActionEvent event){if(event.actionId().equals("accept"))accept(event.id(),(int)event.revision());else if(event.actionId().equals("open"))openSuggestions();}});
  AnalysisJob.schedule(this);
  try{surfaces.connect();}catch(Exception unavailable){connected=false;}
 }
 /** A proactive reminder as a system-display hint; false when the display is not connected (the caller posts a notification). */
 static boolean nudge(String id,String title,String body){
  ObservationService s=current;if(s==null||!s.connected)return false;
  s.main.post(()->{try{s.surfaces.publish(SurfaceSpec.hint(id,1,title,body,new SurfaceSpec.Action("open",s.getString(R.string.nudge_open),true)).withTtlMs(600000));}catch(Exception ignored){}});
  return true;
 }
 void openSuggestions(){try{startActivity(new android.content.Intent(this,MemoryActivity.class).putExtra("tab","suggestions").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));}catch(Exception ignored){}}
 static void policyChanged(){ObservationService s=current;if(s!=null)s.main.post(()->{if(s.pending!=null)s.main.removeCallbacks(s.pending);s.withdrawAll();});}
 static void withdraw(String id){ObservationService s=current;if(s!=null)s.main.post(()->{if(s.connected)try{s.surfaces.dismiss(id);}catch(Exception ignored){}if(s.visibleId.equals(id))s.visibleId="";});}
 void withdrawAll(){if(!visibleId.isEmpty())withdraw(visibleId);}
 final ExecutorService notes=Executors.newSingleThreadExecutor();
 @Override public void onAccessibilityEvent(AccessibilityEvent e){
  if(store==null||e.getPackageName()==null)return;String pkg=e.getPackageName().toString();
  if(!eligible(pkg))return;
  if(pending!=null)main.removeCallbacks(pending);
  pending=()->observe(pkg);main.postDelayed(pending,900);
 }
 /** Notifications from user-selected apps: title and text only, skipping ongoing, secret and one-time-code ones. */
 void notification(String pkg,AccessibilityEvent e){
  if(android.util.Log.isLoggable("TakuMemory",android.util.Log.DEBUG))android.util.Log.d("TakuMemory","Notification event "+pkg+" observing="+store.observing(pkg)+" data="+(e.getParcelableData()==null?"null":e.getParcelableData().getClass().getSimpleName())+" text="+e.getText().size());
  if(current!=this||!store.observing(pkg)||pkg.equals(getPackageName())||pkg.equals("org.agentos.assistant")||!getSystemService(UserManager.class).isUserUnlocked())return;
  if(!(e.getParcelableData() instanceof android.app.Notification))return;android.app.Notification n=(android.app.Notification)e.getParcelableData();
  if((n.flags&(android.app.Notification.FLAG_ONGOING_EVENT|android.app.Notification.FLAG_FOREGROUND_SERVICE|android.app.Notification.FLAG_GROUP_SUMMARY))!=0||n.visibility==android.app.Notification.VISIBILITY_SECRET)return;
  Bundle x=n.extras;if(x==null)return;CharSequence title=x.getCharSequence(android.app.Notification.EXTRA_TITLE),text=x.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);if(text==null)text=x.getCharSequence(android.app.Notification.EXTRA_TEXT);
  String t=title==null?"":title.toString().trim(),b=text==null?"":text.toString().trim();if(b.isEmpty())return;
  String body=(t.isEmpty()?"":t+"\n")+b;if(body.matches("(?s).*(sk-[A-Za-z0-9_-]{20,}|Bearer [A-Za-z0-9._-]{20,}|\\bG-\\d{6}\\b|验证码|校验码|动态码|認証コード|確認コード|(?i:verification code|security code|authentication code|2-step verification|one-time)).*"))return;
  long generation=store.prefs.getLong("generation",0);String label=t.isEmpty()?pkg:t;
  notes.execute(()->{try{store.ingest(pkg,"notification",label,body,generation);}catch(Exception failed){store.failed("Notification",failed);}});
 }
 boolean eligible(String pkg){return current==this&&store.observing(pkg)&&!pkg.equals(getPackageName())&&!pkg.equals("org.agentos.assistant")&&!pkg.equals("org.agentos.surfaces")&&!pkg.equals("com.android.systemui")&&!getSystemService(KeyguardManager.class).isKeyguardLocked()&&getSystemService(UserManager.class).isUserUnlocked()&&getSystemService(PowerManager.class).isInteractive();}
 static final class Page {String source,title,text;int window;Page(String s,String t,String b,int w){source=s;title=t;text=b;window=w;}}
 Page page(String source){
  AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null||root.getPackageName()==null||!source.contentEquals(root.getPackageName()))return null;
  int id=root.getWindowId();String title="";boolean mainWindow=false;
  for(AccessibilityWindowInfo w:getWindows())if(w.getId()==id&&w.getDisplayId()==0&&w.isActive()){mainWindow=true;title=String.valueOf(w.getTitle()==null?source:w.getTitle());break;}
  if(!mainWindow)return null;
  StringBuilder text=new StringBuilder();int[] count={0};boolean[] sensitive={false};collect(root,text,count,sensitive,0);
  String body=text.toString().trim();if(sensitive[0]||body.length()<16||body.matches("(?s).*(sk-[A-Za-z0-9_-]{20,}|Bearer [A-Za-z0-9._-]{20,}|\\bG-\\d{6}\\b|(?i:verification code|security code|authentication code)).*"))return null;
  String documentTitle=webTitle(root,new int[]{0},0);if(!documentTitle.isEmpty()&&Arrays.asList(body.split("\\n")).contains(documentTitle))title=documentTitle;
  return new Page(source,title,body,id);
 }
 // A browser's window is named after the app; the WebView exposes the actual document title.
 // Use it only when the same title was already included by the existing protected text collector.
 static String webTitle(AccessibilityNodeInfo node,int[] count,int depth){
  if(node==null||depth>32||++count[0]>500||!node.isVisibleToUser()||node.isPassword()||node.isEditable()||node.isAccessibilityDataSensitive())return "";
  if("android.webkit.WebView".contentEquals(node.getClassName()==null?"":node.getClassName())){CharSequence t=node.getText();if(t!=null&&!t.toString().isBlank()&&t.length()<=200)return t.toString().trim();}
  for(int i=0;i<node.getChildCount()&&count[0]<500;i++){String title=webTitle(node.getChild(i),count,depth+1);if(!title.isEmpty())return title;}return "";
 }
 static void collect(AccessibilityNodeInfo node,StringBuilder out,int[] count,boolean[] sensitive,int depth){
  if(node==null||depth>32||++count[0]>500||out.length()>=8000||!node.isVisibleToUser())return;
  if(node.isPassword()||node.isAccessibilityDataSensitive()){sensitive[0]=true;return;}
  if(node.isEditable())return;
  CharSequence t=node.getText();if(t==null)t=node.getContentDescription();if(t!=null&&!t.toString().isBlank()){if(out.length()>0)out.append('\n');String value=t.toString().trim();out.append(value,0,Math.min(value.length(),8000-out.length()));}
  for(int i=0;i<node.getChildCount()&&count[0]<500;i++)collect(node.getChild(i),out,count,sensitive,depth+1);
 }
 void observe(String source){
  if(!eligible(source))return;Page p=page(source);if(p==null)return;long generation=store.prefs.getLong("generation",0);
  // Reject FLAG_SECURE windows using the platform screenshot gate. Pixels are closed immediately, never saved.
  takeScreenshotOfWindow(p.window,getMainExecutor(),new TakeScreenshotCallback(){
   public void onFailure(int code){}
   public void onSuccess(ScreenshotResult shot){shot.getHardwareBuffer().close();if(!eligible(source))return;Page now=page(source);if(now==null||now.window!=p.window||!now.text.equals(p.text))return;
    try{JSONObject memory=store.ingest(source,"window:"+p.window,p.title,p.text,generation);}catch(Exception failed){store.failed("Page",failed);}
   }
  });
 }
 static void suggest(JSONArray ids){ObservationService s=current;if(s!=null&&ids.length()>0)s.main.post(()->{try{String id=ids.getString(ids.length()-1);s.offer(id,s.store.read(id,false).getInt("revision"));}catch(Exception ignored){}});}
 void offer(String id,int revision){
  try{JSONObject r=store.read(id,false);if(!eligible(r.getString("source"))||!store.prefs.getBoolean("analysisEnabled",false)||!store.prefs.getBoolean("proactive",true)||!store.prefs.getBoolean("queryEnabled",false)||r.getInt("revision")!=revision||!r.getString("model").equals(store.effectiveModel())||!connected||SystemClock.elapsedRealtime()-lastOffer<60000||!r.getString("task_id").isEmpty())return;
   MemoryCopy copy=new MemoryCopy(this);withdrawAll();surfaces.publish(SurfaceSpec.hint(id,revision,copy.suggestionTitle(r),copy.suggestionSummary(r),new SurfaceSpec.Action("accept",copy.action(r),true)).withTtlMs(300000));visibleId=id;lastOffer=SystemClock.elapsedRealtime();
  }catch(Exception ignored){}
 }
 void accept(String id,int revision){withdraw(id);io.execute(()->{try{BrokerClient.call("accept",new JSONObject().put("id",id).put("revision",revision));}catch(Exception ignored){}});}
 @Override public void onInterrupt(){withdrawAll();}
 @Override public void onDestroy(){if(pending!=null)main.removeCallbacks(pending);if(surfaces!=null)surfaces.close();io.shutdownNow();current=null;super.onDestroy();}
}
