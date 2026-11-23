#!/usr/bin/env python3
"""Compile actual Direct/Controller/Importer and configure method with shared host doubles.

Exercise the worker-to-main handoff and the single Controller monitor. No Android
runtime, device, camera, transport or model is used.
"""
from pathlib import Path
import ast, os, re, subprocess, tempfile

HERE = Path(__file__).resolve().parent
MODULE = HERE.parents[1]


def doubles(name):
    # Read fixture data without executing either fixture's compiler/test entry point.
    tree = ast.parse((HERE / name).read_text())
    return next(ast.literal_eval(node.value) for node in tree.body
                if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == 'files'
                                                       for target in node.targets))


def method(source, name):
    match = re.search(r'^    private static Bundle ' + name + r'\([^\n]*\)\s*\{', source, re.M)
    if not match:
        raise AssertionError(name)
    end = source.index('{', match.start()) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]


files = doubles('test-reference-deadline.py')
presentation = doubles('test-direct-cosplay-presentation.py')
for name in ['android/R.java', 'android/app/Activity.java', 'android/app/AlertDialog.java',
             'android/app/KeyguardManager.java', 'android/content/DialogInterface.java',
             'android/os/Handler.java', 'android/os/PowerManager.java', 'android/view/Gravity.java',
             'android/view/View.java', 'android/view/Window.java', 'android/view/WindowManager.java',
             'org/taku/tryon/realtime/CosplayRequestStore.java']:
    files[name] = presentation[name]
files.pop('org/taku/tryon/realtime/ReferenceDeadlineTest.java')
files['android/os/Bundle.java'] = files['android/os/Bundle.java'].replace(
    'public Object get(String k)',
    'public int size(){return values.size();}public void remove(String k){values.remove(k);}'
    'public boolean containsKey(String k){return values.containsKey(k);}public Object get(String k)')
files['android/content/SharedPreferences.java'] = files['android/content/SharedPreferences.java'].replace(
    'long getLong(String key,long d);', 'long getLong(String key,long d);boolean getBoolean(String key,boolean d);').replace(
    'Editor putLong(String k,long v);', 'Editor putLong(String k,long v);Editor putBoolean(String k,boolean v);')
files['android/content/Context.java'] = files['android/content/Context.java'].replace(
    'public final MemoryPrefs prefs=new MemoryPrefs();public int starts;',
    'public final MemoryPrefs prefs=new MemoryPrefs(),settings=new MemoryPrefs();public int starts;'
    'public Intent lastStart;public volatile CountDownLatch startEntered,startRelease;'
    'public Context(){this(temporary());}private static File temporary(){try{return java.nio.file.Files.createTempDirectory("binding-").toFile();}catch(IOException e){throw new RuntimeException(e);}}'
    'public <T>T getSystemService(Class<T> c){try{return c.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}').replace(
    'return prefs;}public int checkSelfPermission', 'return n.equals("cosplay-settings")?settings:prefs;}public int checkSelfPermission').replace(
    'public void startForegroundService(Intent i){starts++;}',
    'public void startForegroundService(Intent i){starts++;lastStart=i;if(startEntered!=null)startEntered.countDown();'
    'if(startRelease!=null)try{if(!startRelease.await(2,TimeUnit.SECONDS))throw new AssertionError("blocked start timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}').replace(
    'public Editor edit(){', 'public synchronized boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public Editor edit(){').replace(
    'public boolean commit(){', 'public Editor putBoolean(String k,boolean v){update.put(k,v);return this;}public boolean commit(){')
files['android/content/Intent.java'] = '''package android.content;import android.os.Bundle;public class Intent {public Bundle extras;public Object data,clip,type,selector;public Intent(){}public Intent(Context c,Class<?> type){}public Intent putExtras(Bundle b){extras=b;return this;}public Intent putExtra(String k,long v){if(extras==null)extras=new Bundle();extras.putLong(k,v);return this;}public Bundle getExtras(){return extras;}public Object getData(){return data;}public Object getClipData(){return clip;}public Object getType(){return type;}public Object getSelector(){return selector;}public void removeExtra(String key){if(extras!=null)extras.remove(key);}}'''
files['org/taku/tryon/realtime/CallTryOnService.java'] = '''package org.taku.tryon.realtime;public class CallTryOnService {public static boolean busy;public static boolean busy(){return busy;}public static long reserve(){busy=true;return 1;}public static void releaseReservation(long n){busy=false;}public static boolean control(String v){if(v.equals("stop"))busy=false;return true;}public static boolean move(int x,int y){return false;}public static android.os.Bundle position(){return new android.os.Bundle();}public static Snapshot snapshot(){return new Snapshot();}public static final class Snapshot {public String state="starting",detail="",code="none",presentation="closed";public boolean active=true;public int remainingSeconds,sourceFrames,returnedFrames;}}'''
files['org/taku/tryon/realtime/RealtimeImage.java'] = files['org/taku/tryon/realtime/RealtimeImage.java'].replace(
    'public static long expireOnDecode;', 'public static boolean failDecode;public static long expireOnDecode;').replace(
    'public static RealtimeImage decode(byte[] b){', 'public static RealtimeImage decode(byte[] b){if(failDecode)throw new IllegalArgumentException("invalid image");')
gateway = (MODULE / 'app/src/org/taku/tryon/realtime/LegacyCosplayGateway.java').read_text()
files['org/taku/tryon/realtime/LegacyCosplayGateway.java'] = '''package org.taku.tryon.realtime;
import android.content.Context;import android.os.Bundle;import java.util.Set;import org.taku.cosplay.api.CosplayContract;
public class LegacyCosplayGateway {
 static boolean available=true,configurationReady=true;
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("cosplay-settings",Context.MODE_PRIVATE);}
 static boolean ready(Context c){return available&&configurationReady&&prefs(c).getBoolean("enabled",false);}
 static boolean voiceOnly(Context c){return prefs(c).getBoolean("voiceOnly",true);}
 static Bundle response(boolean ok,String message){return TryOnController.result(ok,message);}
 static Bundle state(Context c,boolean metadata){return TryOnController.result(true,"configured");}
 static Bundle configureTest(Context c,Bundle values){return configure(c,values);}
''' + method(gateway, 'configure') + '\n}'
files['org/taku/tryon/realtime/CharacterImportBindingTest.java'] = r'''package org.taku.tryon.realtime;
import android.app.*;import android.content.*;import android.os.*;import android.util.AtomicFile;import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import org.taku.cosplay.api.CosplayContract;
public class CharacterImportBindingTest {
 static int checks;static final byte[] A={1,2,3},B={4,5,6};static final long DEADLINE=120900;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static Handler handler(DirectCosplayActivity a)throws Exception{Field f=DirectCosplayActivity.class.getDeclaredField("main");f.setAccessible(true);return (Handler)f.get(a);}
 static void waitPosted(DirectCosplayActivity a)throws Exception{long until=System.nanoTime()+2_000_000_000L;while(handler(a).size()==0&&System.nanoTime()<until)Thread.sleep(1);check(handler(a).size()>0,"actual worker posts bounded import result");}
 static DirectCosplayActivity fresh(){AlertDialog.reset();CosplayRequestStore.reset();ReferenceImport.abort();SystemClock.now=1000;CallTryOnService.busy=false;RealtimeImage.failDecode=false;RealtimeImage.expireOnDecode=0;AtomicFile.reset();LegacyCosplayGateway.available=LegacyCosplayGateway.configurationReady=true;KeyguardManager.locked=false;PowerManager.interactive=true;DirectCosplayActivity a=new DirectCosplayActivity();a.settings.edit().putBoolean("enabled",true).putBoolean("voiceOnly",false).commit();a.intent.extras=new Bundle();a.intent.extras.putString(CosplayContract.EXTRA_TICKET,"a".repeat(32));CosplayRequestStore.request=new CosplayRequestStore.Request(A.clone(),900,950);return a;}
 static DirectCosplayActivity importedA()throws Exception{DirectCosplayActivity a=fresh();a.onCreate(null);a.onResume();waitPosted(a);check(a.starts==0&&!ReferenceImport.writing(),"import A releases worker gate before any main start");Bundle d=TryOnController.draft(a,null);check(d.getLong("referenceExpires")==DEADLINE&&d.getLong("revision")==1,"A actual import result carries deadline and revision");return a;}
 static Bundle replace(DirectCosplayActivity a,long expiry){check(ReferenceImport.begin(),"Agent replacement import admitted");Bundle b=ReferenceImport.write(a,new ByteArrayInputStream(B),expiry);check(b.getBoolean("ok"),"Agent replacement actual import succeeds");return b;}
 static void closeFailure(DirectCosplayActivity a){check(a.starts==0&&AlertDialog.creates==1&&AlertDialog.last.shown,"superseded invocation shows failure without starting camera/model");check(AlertDialog.last.message.contains("reference image changed"),"superseded reference has actionable English explanation");AlertDialog.last.clickClose();check(a.finishes==1,"superseded request Close finishes bridge");a.onPause();check(!AlertDialog.last.shown,"failure dialog is dismissed when paused");}
 static void cleanup(DirectCosplayActivity a){a.onPause();a.onDestroy();}
 static Bundle flags(boolean voiceOnly){Bundle b=new Bundle();b.putString("schema",CosplayContract.SCHEMA);b.putBoolean("voiceOnly",voiceOnly);return b;}
 public static void main(String[] args)throws Exception{
  DirectCosplayActivity a=importedA();try{Bundle before=TryOnController.draft(a,null);check(TryOnController.draft(a,new Bundle()).getLong("revision")==before.getLong("revision"),"ordinary unchanged draft still leaves revision alone");handler(a).next();check(a.starts==1&&AlertDialog.creates==0,"unchanged A starts normally");check(a.lastStart.extras.getLong("referenceExpires")==DEADLINE&&a.lastStart.extras.getString("mode").equals("character"),"normal start uses original bounded character reference");}finally{cleanup(a);}
  for(String changed:new String[]{"default-import","same-deadline-import","revision","uri","expiry"}){
   a=importedA();try{
    if(changed.equals("default-import")){check(ReferenceImport.begin(),"ordinary Agent B admitted");ReferenceImport.write(a,new ByteArrayInputStream(B));check(TryOnController.draft(a,null).getLong("referenceExpires")==0,"ordinary B remains persistent");}
    if(changed.equals("same-deadline-import")){Bundle b=replace(a,DEADLINE);check(b.getLong("revision")==2,"same URI/expiry image replacement increments actual draft revision");}
    if(changed.equals("revision")){Bundle p=new Bundle();p.putString("prompt","Agent changed draft");TryOnController.draft(a,p);}
    if(changed.equals("uri"))a.prefs.edit().putString("referenceUri","content://other/reference").commit();
    if(changed.equals("expiry"))a.prefs.edit().putLong("referenceExpires",0).commit();
    check(ReferenceImport.status().optString("state").equals("ready"),"global ready remains true for superseded A");handler(a).next();closeFailure(a);
    if(changed.endsWith("import"))check(Arrays.equals(Files.readAllBytes(new File(a.getFilesDir(),"imported-reference.jpg").toPath()),B),"rejecting A preserves authorized Agent B image");
   }finally{cleanup(a);}
  }
  // An unrelated successful import cannot turn A's failed worker result into permission to start.
  a=fresh();try{RealtimeImage.failDecode=true;a.onCreate(null);a.onResume();waitPosted(a);RealtimeImage.failDecode=false;replace(a,0);check(ReferenceImport.status().optString("state").equals("ready"),"B makes global state ready after failed A");handler(a).next();check(a.starts==0&&AlertDialog.last.shown&&AlertDialog.last.message.contains("Could not prepare"),"actual failed A result remains failed despite later global ready");}finally{cleanup(a);}
  for(String changed:new String[]{"disabled","voice-only","unavailable","config-not-ready"}){
   a=importedA();try{if(changed.equals("disabled"))a.settings.edit().putBoolean("enabled",false).commit();if(changed.equals("voice-only"))check(LegacyCosplayGateway.configureTest(a,flags(true)).getBoolean("ok"),"real configure method commits preview-off");if(changed.equals("unavailable"))LegacyCosplayGateway.available=false;if(changed.equals("config-not-ready"))LegacyCosplayGateway.configurationReady=false;handler(a).next();check(a.starts==0&&AlertDialog.last.shown,"late readiness recheck rejects before start: "+changed);}finally{cleanup(a);}
  }
  // Configure admitted first: block its flags commit while it owns the Controller monitor.
  a=importedA();ExecutorService pool=Executors.newFixedThreadPool(2);final DirectCosplayActivity configuredFirst=a;
  try{
   a.settings.entered=new CountDownLatch(1);a.settings.release=new CountDownLatch(1);Future<Bundle> configure=pool.submit(()->LegacyCosplayGateway.configureTest(configuredFirst,flags(true)));check(a.settings.entered.await(2,TimeUnit.SECONDS),"production configure reached flags commit");CountDownLatch mainEntered=new CountDownLatch(1);Future<?> main=pool.submit(()->{mainEntered.countDown();try{handler(configuredFirst).next();}catch(Exception e){throw new RuntimeException(e);}});check(mainEntered.await(2,TimeUnit.SECONDS),"main continuation attempts start while configure owns monitor");Thread.sleep(30);check(!main.isDone()&&a.starts==0,"Direct cannot pass ready check between provider and flags commit");a.settings.release.countDown();check(configure.get(2,TimeUnit.SECONDS).getBoolean("ok"),"configure completes");main.get(2,TimeUnit.SECONDS);check(a.starts==0&&AlertDialog.last.shown,"committed preview-off prevents A start");
  }finally{a.settings.release.countDown();pool.shutdownNow();cleanup(a);}
  // Start admitted first: configure cannot publish flags after Direct's final check but before start.
  a=importedA();pool=Executors.newFixedThreadPool(2);final DirectCosplayActivity startedFirst=a;
  try{
   a.startEntered=new CountDownLatch(1);a.startRelease=new CountDownLatch(1);Future<?> main=pool.submit(()->{try{handler(startedFirst).next();}catch(Exception e){throw new RuntimeException(e);}});check(a.startEntered.await(2,TimeUnit.SECONDS),"actual Controller reached foreground service start");CountDownLatch configEntered=new CountDownLatch(1);Future<Bundle> configure=pool.submit(()->{configEntered.countDown();return LegacyCosplayGateway.configureTest(startedFirst,flags(true));});check(configEntered.await(2,TimeUnit.SECONDS),"configure attempts while Direct owns monitor");Thread.sleep(30);check(!configure.isDone()&&!a.settings.getBoolean("voiceOnly",true),"flags cannot interleave with admitted Direct start");a.startRelease.countDown();main.get(2,TimeUnit.SECONDS);check(!configure.get(2,TimeUnit.SECONDS).getBoolean("ok")&&a.starts==1&&!a.settings.getBoolean("voiceOnly",true),"later configure observes busy and preserves current session settings");
  }finally{a.startRelease.countDown();pool.shutdownNow();cleanup(a);}
  System.out.println("Production character import/result binding checks: "+checks);
 }
}'''

jdk = os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@23/libexec/openjdk.jdk/Contents/Home')
with tempfile.TemporaryDirectory(prefix='taku-character-binding-') as temporary:
    root = Path(temporary)
    inputs = []
    for name, content in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        inputs.append(path)
    inputs += [MODULE / 'app/src/org/taku/tryon/realtime' / (name + '.java')
               for name in ['TryOnController', 'ReferenceImport', 'DirectCosplayActivity', 'CosplayPolicy']]
    inputs.append(MODULE / 'contract/org/taku/cosplay/api/CosplayContract.java')
    subprocess.run([jdk + '/bin/javac', '--release', '17', '-encoding', 'UTF-8', '-d', temporary, *map(str, inputs)], check=True)
    subprocess.run([jdk + '/bin/java', '-Djava.io.tmpdir=' + temporary, '-cp', temporary,
                    'org.taku.tryon.realtime.CharacterImportBindingTest'], check=True, timeout=30)
