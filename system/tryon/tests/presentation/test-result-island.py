#!/usr/bin/env python3
"""Run production result action admission with deterministic Android/time doubles; no model."""
import os,re,subprocess,tempfile,xml.etree.ElementTree as ET
from pathlib import Path
module=Path(__file__).resolve().parents[2]
source=(module/'app/src/org/taku/tryon/realtime/TryOnResultWindow.java').read_text()
def method(name):
    m=re.search(r'    (?:private )?static (?:TryOnResultWindow|void|boolean) '+name+r'\(',source)
    if not m:m=re.search(r'    private void '+name+r'\(',source)
    assert m,name
    at=source.index('{',m.start());end=at+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[m.start():end]
fixture='''package org.taku.tryon.realtime;
import java.util.*;
public final class TryOnResultWindow {
 static final Handler MAIN=new Handler();static TryOnResultWindow shown;
 final String token;final long expires;boolean closed,retryLaunched;final PendingIntent retry=new PendingIntent();final Publisher publisher=new Publisher();
 final String notificationId="result";final Runnable expire=()->{},expand=()->{};
 TryOnResultWindow(String token,long expires){this.token=token;this.expires=expires;}
'''+ '\n'.join(method(n) for n in ['current','act','claimRetry','dismissCurrent','dismiss'])+'''
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static TryOnResultWindow fresh(String id){MAIN.queue.clear();Looper.main=true;SystemClock.now=1000;return shown=new TryOnResultWindow(id,121000);}
 public static void main(String[] ignored){
  Context c=new Context();shown=null;act(c,"missing","retry");check(c.starts.isEmpty(),"No card cannot create retry");
  TryOnResultWindow a=fresh("one");act(c,"stale","retry");check(c.starts.isEmpty()&&!a.closed&&a.retry.sends==0,"Stale action cannot consume current result");
  act(c,"one","unknown");check(!a.closed&&c.starts.isEmpty()&&a.retry.sends==0,"Unknown action cannot mutate result");
  act(c,"one","close");check(shown==null&&a.closed&&a.publisher.dismissals==1&&c.starts.isEmpty(),"Close only dismisses its prompt");
  act(c,"one","retry");check(c.starts.isEmpty()&&a.publisher.dismissals==1,"Closed prompt cannot retry");
  a=fresh("two");act(c,"two","open");check(shown==a&&!a.closed&&c.starts.size()==1&&c.starts.get(0).target==TryOnActivity.class,"Body/header open only opens details");
  check(c.starts.get(0).action==null,"Open cannot carry retry action");
  c.starts.clear();act(c,"two","retry");check(shown==a&&a.retry.sends==1&&!a.closed,"Explicit Retry sends only direct Activity pending intent");
  act(c,"two","retry");check(a.retry.sends==1,"Double tap cannot launch twice");
  check(claimRetry("two")&&a.closed&&shown==null&&a.publisher.dismissals==1,"Visible Retry Activity atomically claims its prompt");
  check(!claimRetry("two"),"Replayed Activity cannot claim twice");
  a=fresh("three");c.starts.clear();SystemClock.now=121000;act(c,"three","retry");check(c.starts.isEmpty()&&!a.closed&&a.retry.sends==0&&!claimRetry("three"),"Expiry rejects retry at exact boundary");
  a=fresh("four");Looper.main=false;act(c,"four","retry");check(c.starts.isEmpty()&&MAIN.queue.size()==1,"Binder-style caller queues UI handler without immediate mutation");
  shown=new TryOnResultWindow("new",121000);MAIN.drain();check(c.starts.isEmpty()&&a.retry.sends==0&&"new".equals(shown.token),"Queued old action cannot retry a newer result");
  a=fresh("five");Looper.main=false;act(c,"five","retry");SystemClock.now=121000;MAIN.drain();check(c.starts.isEmpty()&&a.retry.sends==0,"Queued action revalidates expiry on main");
  a=fresh("six");Looper.main=false;dismissCurrent();check(!a.closed,"Off-main dismiss queues");MAIN.drain();check(a.closed&&shown==null,"Queued stop closes actual result on main");
  a=fresh("activity");TryOnRetryActivity activity=new TryOnRetryActivity();Intent accepted=new Intent(c,TryOnRetryActivity.class).setAction("retry").putExtra("resultToken","activity");activity.setIntent(accepted);activity.onCreate(new Bundle());
  check(activity.models==1&&a.closed,"Actual Retry Activity claims valid result before base retry");activity.onNewIntent(accepted);check(activity.models==1,"Replayed Activity Intent cannot reach base retry");
  fresh("different");activity=new TryOnRetryActivity();activity.setIntent(accepted);activity.onCreate(new Bundle());check(activity.models==0&&activity.finished,"Actual stale Activity rejects result before initialization");
  shown=null;activity=new TryOnRetryActivity();activity.setIntent(accepted);activity.onCreate(new Bundle());check(activity.models==0&&activity.finished,"Process-death stale Retry cannot manufacture previous request");
  System.out.println("Result-island action admission checks: "+checks);
 }
}
class Looper{static boolean main=true;static final Looper MAIN=new Looper();static Looper getMainLooper(){return MAIN;}static Looper myLooper(){return main?MAIN:null;}}
class Handler{final List<Runnable> queue=new ArrayList<>();void post(Runnable r){queue.add(r);}void removeCallbacks(Runnable r){queue.removeIf(x->x==r);}void drain(){Looper.main=true;while(!queue.isEmpty())queue.remove(0).run();}}
class SystemClock{static long now;static long elapsedRealtime(){return now;}}
class PendingIntent{int sends;void send()throws CanceledException{sends++;}static class CanceledException extends Exception{}}
class Publisher{int dismissals;void dismiss(String id){dismissals++;}}
class Context{final List<Intent> starts=new ArrayList<>();void startActivity(Intent i){starts.add(i);}}
class Intent{static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_SINGLE_TOP=2;Class<?> target;String action;Intent(Context c,Class<?> target){this.target=target;}String token;Intent setAction(String a){action=a;return this;}Intent addFlags(int flags){return this;}Intent putExtra(String k,String v){token=v;return this;}boolean hasExtra(String k){return token!=null;}String getStringExtra(String k){return token;}}
class Bundle{}
class TryOnActivity extends Context{Intent intent;int models;boolean finished;Intent getIntent(){return intent;}void setIntent(Intent i){intent=i;}protected boolean allowResultIntent(Intent i){return true;}public void onCreate(Bundle b){if(!allowResultIntent(intent)){finish();return;}if(intent!=null&&"retry".equals(intent.action))models++;}protected void onNewIntent(Intent i){if(!allowResultIntent(i))return;intent=i;if("retry".equals(i.action))models++;}void finish(){finished=true;}}
'''
retry_source=(module/'app/src/org/taku/tryon/realtime/TryOnRetryActivity.java').read_text()
fixture+='\n'+retry_source[retry_source.index('public final class'):].replace('public final class','final class',1)
home=os.environ.get('JAVA_HOME');javac=str(Path(home)/'bin/javac') if home else 'javac';java=str(Path(home)/'bin/java') if home else 'java'
with tempfile.TemporaryDirectory(prefix='taku-result-island-') as td:
    f=Path(td)/'TryOnResultWindow.java';f.write_text(fixture)
    subprocess.run([javac,'--release','17','-d',td,str(f)],check=True)
    subprocess.run([java,'-cp',td,'org.taku.tryon.realtime.TryOnResultWindow'],check=True)
# The actual design icon is reused without changing its paths or natural 20dp size.
repo=module.parents[1];svg=ET.parse(repo/'system/island/design/assistant-card/expand.svg').getroot()
vector=ET.parse(module/'app/res/drawable/tryon_island_expand.xml').getroot();ns='{http://schemas.android.com/apk/res/android}'
assert vector.get(ns+'width')=='20dp' and vector.get(ns+'height')=='20dp'
assert [n.get('d') for n in svg.iter() if n.tag.endswith('path')]==[n.get(ns+'pathData') for n in vector if n.tag.endswith('path')]
print('Result-island design asset provenance: exact paths and natural size preserved')

activity=(module/"app/src/org/taku/tryon/realtime/TryOnActivity.java").read_text()
assert activity.index("if(!allowResultIntent(getIntent()))") < activity.index("ui=new Ui(this)")
assert "super.onNewIntent(intent);if(!allowResultIntent(intent))return;setIntent(intent);handle(intent);" in activity
