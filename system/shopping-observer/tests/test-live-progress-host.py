#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Production progress callbacks with fake notifications and optional local IPC adapter.

No Android runtime, device, model, lease issuance, or real permission acceptance.
Pass --pixel-adapter to additionally exercise an ignored phone-only source file.
"""
from pathlib import Path
import argparse,os,subprocess,tempfile

parser=argparse.ArgumentParser()
parser.add_argument('--pixel-adapter',type=Path)
args=parser.parse_args()
module=Path(__file__).resolve().parents[1]
sources=module/'src/org/agentos/shopping'
stubs={
'android/content/Context.java':'''package android.content; public class Context {}''',
'android/content/Intent.java':'''package android.content; public class Intent {public Intent(Context context,Class<?> target){}}''',
'android/app/PendingIntent.java':'''package android.app; import android.content.*; public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;public static PendingIntent getActivity(Context context,int id,Intent intent,int flags){return new PendingIntent();}}''',
'android/os/Looper.java':'''package android.os; public class Looper {private static final Looper MAIN=new Looper();public static Looper myLooper(){return MAIN;}public static Looper getMainLooper(){return MAIN;}}''',
'android/os/SystemClock.java':'''package android.os; public class SystemClock {public static long now=1000;public static long elapsedRealtime(){return now;}}''',
'org/json/JSONObject.java':'''package org.json; import java.util.*; public class JSONObject extends LinkedHashMap<String,Object> {public JSONObject put(String key,Object value){super.put(key,value);return this;}public boolean has(String key){return containsKey(key);}public Object opt(String key){return get(key);}public String optString(String key){Object value=get(key);return value instanceof String?(String)value:"";}public long optLong(String key,long fallback){Object value=get(key);return value instanceof Number?((Number)value).longValue():fallback;}public JSONObject optJSONObject(String key){Object value=get(key);return value instanceof JSONObject?(JSONObject)value:null;}}''',
'org/json/JSONArray.java':'''package org.json; import java.util.*; public class JSONArray extends ArrayList<Object> {public JSONArray put(Object value){add(value);return this;}}''',
'org/taku/tryon/common/IslandPublisher.java':'''package org.taku.tryon.common;
import android.app.PendingIntent;import android.content.Context;import java.util.*;
public class IslandPublisher {
 public record Action(String id,String label,PendingIntent intent){}
 public record Post(String compact,String title,String body,boolean ongoing,List<Action> actions){}
 public static final List<Post> posts=new ArrayList<>();public static int dismissals;public static boolean postResult=true,throwPost;
 public IslandPublisher(Context context,String id,String name){}
 public boolean post(String id,String compact,String title,String body,boolean ongoing,List<Action> actions,String primary,PendingIntent open,boolean alert){if(throwPost)throw new IllegalStateException("presentation_unavailable");if(!postResult)return false;posts.add(new Post(compact,title,body,ongoing,List.copyOf(actions)));return true;}
 public void dismiss(String id){dismissals++;}
}''',
'org/agentos/shopping/ObserverActions.java':'''package org.agentos.shopping;
import android.app.PendingIntent;import android.content.Context;import java.util.*;
public class ObserverActions {
 static Map<String,Runnable> latest=Map.of();static int next;static String key;
 static String register(Map<String,Runnable> callbacks){latest=callbacks;return key="callback-"+(++next);}
 static void clear(String old){if(Objects.equals(old,key)){latest=Map.of();key=null;}}
 static PendingIntent pending(Context context,String key,String action){return new PendingIntent();}
}''',
'org/agentos/shopping/MainActivity.java':'''package org.agentos.shopping; class MainActivity {}''',
'org/agentos/shopping/ReminderConnection.java':'''package org.agentos.shopping; class ReminderConnection {static final String CONSENT_SUMMARY="LIVE image + camera video · Cloud AI";}''',
'org/agentos/proactivity/api/ProactivityClient.java':'''package org.agentos.proactivity.api;
import android.content.Context;import org.json.JSONObject;import java.util.*;
public class ProactivityClient implements AutoCloseable {
 public interface Listener {void onConnected();void onMessage(JSONObject message);default void onDisconnected(){}}
 public record Request(String id,String operation,JSONObject arguments){}
 public static boolean autoConnect=true;public static final List<ProactivityClient> clients=new ArrayList<>();
 public final List<Request> requests=new ArrayList<>();public final Listener listener;public boolean closed;
 public ProactivityClient(Context context,Listener listener){this.listener=listener;clients.add(this);}
 public void connect(){if(autoConnect)listener.onConnected();}
 public String request(String operation,JSONObject arguments){String id="request-"+(requests.size()+1);requests.add(new Request(id,operation,arguments));return id;}
 public void close(){closed=true;}
}''',
}
main_test=r'''package org.agentos.shopping;
import android.content.Context;import org.json.JSONObject;import java.util.*;import org.taku.tryon.common.IslandPublisher;
public final class LiveFrameProgressTest {
 static int checks,cancelled,started,retried,selected;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static final class Session implements LiveFrameProgressHost.Session {}
 static final class Host implements LiveFrameProgressHost {Listener listener;Card card;int shows,closes;boolean throwShow;public void show(Card card){if(throwShow)throw new IllegalStateException("presentation_unavailable");this.card=card;shows++;}public void close(){closes++;}}
 static Host host;
 public static void main(String[] ignored){
  Context context=new Context();LiveFrameProgressHost.installFactory(null);
  check(LiveFrameProgressHost.session(new JSONObject())==null,"normal build never parses optional capabilities");
  LiveFrameProgress progress=new LiveFrameProgress(context,()->cancelled++);progress.show();
  check(IslandPublisher.posts.size()==1&&IslandPublisher.posts.get(0).ongoing()&&IslandPublisher.posts.get(0).compact().equals("Matching…"),"default uses real notification publisher");
  Runnable stale=ObserverActions.latest.get("cancel");progress.showConfirmation(()->started++);stale.run();
  check(cancelled==0,"old notification cannot cancel a replacement card");
  Runnable start=ObserverActions.latest.get("try_on");start.run();start.run();check(started==1,"confirmation remains one-use");
  progress.showFailure("Not found",()->retried++,()->selected++);Runnable staleRetry=ObserverActions.latest.get("retry");
  progress.setRetryEnabled(false);check(!ObserverActions.latest.containsKey("retry")&&!ObserverActions.latest.containsKey("select"),"busy cleanup disables retry/select");
  staleRetry.run();check(retried==0,"stale retry cannot execute after disabled state");
  progress.setRetryEnabled(true);Runnable select=ObserverActions.latest.get("select");select.run();select.run();
  check(selected==1&&!progress.hasSelectionAction(),"selection consumes the same business callback once");
  progress.close();progress.close();start.run();check(started==1&&IslandPublisher.dismissals==1,"closed/default progress cannot revive old work");
  LiveFrameProgressHost.installFactory(new LiveFrameProgressHost.Factory(){
   public LiveFrameProgressHost.Session session(JSONObject receipt){return new Session();}
   public LiveFrameProgressHost create(Context context,LiveFrameProgressHost.Session session,LiveFrameProgressHost.Listener listener){host=new Host();host.listener=listener;return host;}
  });
  int before=IslandPublisher.posts.size();progress=new LiveFrameProgress(context,()->cancelled++);progress.show();
  check(IslandPublisher.posts.size()==before+1&&host==null,"no lease keeps normal notification even with optional factory");progress.close();
  progress=new LiveFrameProgress(context,LiveFrameProgressHost.session(new JSONObject()),()->cancelled++);before=IslandPublisher.posts.size();progress.show();
  check(host.shows==1&&host.card.ongoing()&&host.card.actions().get(0).id().equals("cancel")&&IslandPublisher.posts.size()==before,"leased host replaces only presentation transport");
  host.listener.action("unknown");check(cancelled==0,"unknown action cannot dispatch business work");
  host.listener.action("cancel");host.listener.action("cancel");host.listener.event("presentation_expired");
  check(cancelled==1,"host cancel/dismissal callbacks remain idempotent");progress.close();progress.close();check(host.closes==1,"host closes once");
  progress=new LiveFrameProgress(context,new Session(),()->cancelled++);progress.showFailure("Not found",()->retried++,null);
  host.listener.action("retry");host.listener.action("retry");check(retried==1,"host retry consumes the same source callback once");
  host.listener.event("presentation_error");host.listener.event("presentation_dismissed");check(cancelled==2,"host failures cancel existing attempt once");progress.close();
  progress=new LiveFrameProgress(context,new Session(),()->cancelled++);host.throwShow=true;progress.show();
  check(cancelled==3,"synchronous host publication exceptions cancel the attempt");host.listener.event("presentation_error");check(cancelled==3,"publication exception and async failure cancel only once");progress.close();
  LiveFrameProgressHost.installFactory(null);
  IslandPublisher.postResult=false;int postsBefore=IslandPublisher.posts.size();boolean[] live={true};int[] modelWork={0};
  progress=new LiveFrameProgress(context,()->{cancelled++;live[0]=false;});progress.show();
  if(live[0])modelWork[0]++;
  check(!live[0]&&modelWork[0]==0&&cancelled==4,"notification rejection synchronously invalidates the attempt before model scheduling");
  check(IslandPublisher.posts.size()==postsBefore&&ObserverActions.latest.isEmpty(),"rejected Matching publication leaves no invisible action callbacks");progress.close();
  progress=new LiveFrameProgress(context,()->cancelled++);progress.showConfirmation(()->started++);
  check(cancelled==5&&started==1&&ObserverActions.latest.isEmpty(),"undisplayable confirmation cancels without granting a start");progress.close();
  IslandPublisher.postResult=true;IslandPublisher.throwPost=true;progress=new LiveFrameProgress(context,()->cancelled++);progress.showFailure("Not found",()->retried++,()->selected++);
  check(cancelled==6&&ObserverActions.latest.isEmpty()&&retried==1&&selected==1,"notification exception cancels failure UI without exposing retry/select work");progress.close();IslandPublisher.throwPost=false;
  System.out.println("LiveFrameProgress production transport/callback checks: "+checks);
 }
}'''
pixel_test=r'''package org.agentos.shopping;
import android.content.Context;import android.os.SystemClock;import java.util.*;import org.json.*;import org.agentos.proactivity.api.ProactivityClient;
public final class PixelProgressHostTest {
 static int checks;static final String TOKEN="A".repeat(43),ID="00112233-4455-6677-8899-aabbccddeeff";
 static final PixelProgressHost.Factory factory=new PixelProgressHost.Factory();
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void invalid(JSONObject receipt){boolean invalid=false;try{factory.session(receipt);}catch(IllegalArgumentException expected){invalid=true;}check(invalid,"invalid or partial session rejected");}
 static JSONObject receipt(){return new JSONObject().put("presentationToken",TOKEN).put("presentationId",ID).put("presentationExpiresAt",100000L);}
 static JSONObject event(String type,String id,long revision,String action){return new JSONObject().put("ok",true).put("type",type).put("result",new JSONObject().put("presentationId",id).put("revision",revision).put("actionId",action));}
 static final class Listener implements LiveFrameProgressHost.Listener {final List<String> actions=new ArrayList<>(),events=new ArrayList<>();public void action(String id){actions.add(id);}public void event(String type){events.add(type);}}
 static ProactivityClient client(){return ProactivityClient.clients.get(ProactivityClient.clients.size()-1);}
 static LiveFrameProgressHost.Card card(boolean working){return new LiveFrameProgressHost.Card("Matching…","Matching…","Checking the presenter's top.",working,List.of(new LiveFrameProgressHost.Action("cancel","Cancel",true)));}
 public static void main(String[] ignored){
  check(factory.session(new JSONObject())==null,"missing optional lease remains normal notification");
  invalid(new JSONObject().put("presentationToken",TOKEN));invalid(receipt().put("presentationId","bad"));invalid(receipt().put("presentationToken","bad"));
  invalid(new JSONObject().put("presentationExpiresAt",100000L));invalid(new JSONObject().put("presentationToken",TOKEN).put("presentationId",ID));
  invalid(receipt().put("presentationExpiresAt",999L));invalid(receipt().put("presentationExpiresAt","100000"));
  LiveFrameProgressHost.Session session=factory.session(receipt());check(!session.toString().contains(TOKEN)&&!session.toString().contains(ID),"opaque session string contains no capability");
  Listener listener=new Listener();LiveFrameProgressHost host=factory.create(new Context(),session,listener);ProactivityClient client=client();
  host.show(card(true));JSONObject wire=client.requests.get(0).arguments();
  check(client.requests.size()==1&&client.requests.get(0).operation().equals("present_action")&&wire.size()==6&&wire.optLong("revision",0)==1&&wire.optString("status").equals("working"),"working presentation uses exact existing raw IPC shape");
  check(wire.optString("presentationToken").equals(TOKEN)&&wire.optString("title").equals("Matching…")&&((JSONArray)wire.opt("actions")).size()==1,"actual received lease and original text/actions are forwarded");
  host.show(card(true));check(client.requests.size()==1,"identical updates do not allocate revisions");
  client.listener.onMessage(event("presentation_action","wrong",1,"cancel"));client.listener.onMessage(event("presentation_action",ID,2,"cancel"));client.listener.onMessage(event("presentation_action",ID,1,"start"));
  check(listener.actions.isEmpty(),"wrong session/revision/unoffered action cannot execute");
  client.listener.onMessage(event("presentation_action",ID,1,"cancel"));client.listener.onMessage(event("presentation_action",ID,1,"cancel"));
  check(listener.actions.equals(List.of("cancel")),"authenticated current action is one-use");
  host.show(card(false));wire=client.requests.get(1).arguments();check(wire.size()==5&&!wire.has("status")&&wire.optLong("revision",0)==2,"needs_action preserves legacy five-field protocol");
  client.listener.onMessage(event("presentation_action",ID,1,"cancel"));check(listener.actions.size()==1,"superseded revision cannot execute newer card");
  client.listener.onMessage(new JSONObject().put("type","present_action").put("requestId","unrelated").put("ok",false));check(listener.events.isEmpty(),"unrelated failure receipt is ignored");
  client.listener.onMessage(new JSONObject().put("type","present_action").put("requestId",client.requests.get(1).id()).put("ok",false));check(listener.events.equals(List.of("presentation_error")),"matching rejection reaches cancellation path");
  host.close();check(client.closed&&client.requests.size()==3&&client.requests.get(2).operation().equals("dismiss_action_presentation")&&client.requests.get(2).arguments().optLong("revision",0)==2,"close dismisses only its own latest revision");
  host.close();client.listener.onMessage(event("presentation_action",ID,2,"cancel"));check(client.requests.size()==3&&listener.actions.size()==1,"close is idempotent and fences queued events");
  Listener nextListener=new Listener();LiveFrameProgressHost next=factory.create(new Context(),session,nextListener);ProactivityClient nextClient=client();next.show(card(true));
  check(nextClient.requests.get(0).arguments().optLong("revision",0)==3,"same session shares revision across progress/failure/retry handoff");
  next.show(new LiveFrameProgressHost.Card("Select an outfit","Select an outfit","Choose an outfit",false,List.of(new LiveFrameProgressHost.Action("select","Select outfit",true),new LiveFrameProgressHost.Action("retry","Try again",false),new LiveFrameProgressHost.Action("close","Close",false))));
  check(((JSONArray)nextClient.requests.get(1).arguments().opt("actions")).size()==2,"legacy selection preserves original two-button layout");
  next.close();
  ProactivityClient.autoConnect=false;Listener queuedListener=new Listener();LiveFrameProgressHost queued=factory.create(new Context(),factory.session(receipt()),queuedListener);ProactivityClient queuedClient=client();
  queued.show(card(true));queued.show(card(false));check(queuedClient.requests.isEmpty(),"unconnected transport never submits work");queuedClient.listener.onConnected();
  check(queuedClient.requests.size()==1&&!queuedClient.requests.get(0).arguments().has("status"),"connect sends only latest pending presentation");
  SystemClock.now=100001;queued.show(card(true));check(queuedClient.requests.size()==1&&queuedListener.events.contains("presentation_error"),"expired lease cannot publish or mint a replacement");queued.close();
  System.out.println("Pixel raw presentation protocol checks: "+checks);
 }
}'''
home=os.environ.get('JAVA_HOME');javac=str(Path(home)/'bin/javac') if home else 'javac';java=str(Path(home)/'bin/java') if home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-live-progress-') as temporary:
    target=Path(temporary);files=[]
    stubs['org/agentos/shopping/LiveFrameProgressTest.java']=main_test
    if args.pixel_adapter:stubs['org/agentos/shopping/PixelProgressHostTest.java']=pixel_test
    for name,source in stubs.items():
        path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source);files.append(str(path))
    files.extend(str(sources/name) for name in ['LiveFrameProgress.java','LiveFrameProgressHost.java'])
    if args.pixel_adapter:files.append(str(args.pixel_adapter.resolve(strict=True)))
    subprocess.run([javac,'--release','17','-encoding','UTF-8','-d',temporary,*files],check=True)
    subprocess.run([java,'-cp',temporary,'org.agentos.shopping.LiveFrameProgressTest'],check=True,timeout=15)
    if args.pixel_adapter:subprocess.run([java,'-cp',temporary,'org.agentos.shopping.PixelProgressHostTest'],check=True,timeout=15)
