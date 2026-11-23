#!/usr/bin/env python3
"""Compile the real Controller/importer with deterministic storage, image and clock doubles.

Checks process-local failure rollback and original deadline, not cross-file crash atomicity.
No device, camera or model.
"""
from pathlib import Path
import os, subprocess, tempfile

MODULE = Path(__file__).resolve().parents[2]
files = {
    'android/Manifest.java': '''package android;public final class Manifest {public static final class permission {public static final String CAMERA="camera";}}''',
    'android/app/Activity.java': '''package android.app;public class Activity {}''',
    'android/content/pm/PackageManager.java': '''package android.content.pm;public class PackageManager {public static final int PERMISSION_GRANTED=0;}''',
    'android/net/Uri.java': '''package android.net;public class Uri {final String value;private Uri(String v){value=v;}public static Uri parse(String v){return new Uri(v);}public String getScheme(){int i=value.indexOf(':');return i<0?null:value.substring(0,i);}public String toString(){return value;}}''',
    'android/os/SystemClock.java': '''package android.os;public class SystemClock {public static volatile long now=1000;public static long elapsedRealtime(){return now;}}''',
    'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle {final Map<String,Object> values=new HashMap<>();public void putString(String k,String v){values.put(k,v);}public String getString(String k){return (String)values.get(k);}public String getString(String k,String d){return (String)values.getOrDefault(k,d);}public void putLong(String k,long v){values.put(k,v);}public void putInt(String k,int v){values.put(k,v);}public void putObject(String k,Object v){values.put(k,v);}public long getLong(String k){return getLong(k,0);}public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}public void putBoolean(String k,boolean v){values.put(k,v);}public boolean getBoolean(String k){return Boolean.TRUE.equals(values.get(k));}public Set<String> keySet(){return values.keySet();}public Object get(String k){return values.get(k);}public void putAll(Bundle b){values.putAll(b.values);}}''',
    'android/os/Looper.java': '''package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}''',
    'android/os/Handler.java': '''package android.os;public class Handler {public Handler(Looper l){}public boolean post(Runnable r){r.run();return true;}}''',
    'android/content/Intent.java': '''package android.content;import android.os.Bundle;public class Intent {public Intent(Context c,Class<?> type){}public Intent putExtras(Bundle b){return this;}public Intent putExtra(String k,long v){return this;}}''',
    'android/provider/Settings.java': '''package android.provider;import android.content.Context;public class Settings {public static boolean canDrawOverlays(Context c){return true;}}''',
    'android/content/SharedPreferences.java': '''package android.content;public interface SharedPreferences {String getString(String key,String d);long getLong(String key,long d);Editor edit();interface Editor {Editor putString(String k,String v);Editor putLong(String k,long v);boolean commit();}}''',
    'android/content/Context.java': r'''package android.content;
import java.io.*;import java.util.*;import java.util.concurrent.*;
public class Context {
 public static final int MODE_PRIVATE=0;public final File directory;public final MemoryPrefs prefs=new MemoryPrefs();public int starts;
 public Context(File dir){directory=dir;dir.mkdirs();}public File getFilesDir(){return directory;}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}public int checkSelfPermission(String p){return 0;}public void startForegroundService(Intent i){starts++;}
 public static final class MemoryPrefs implements SharedPreferences {
  final Map<String,Object> values=new HashMap<>(),disk=new HashMap<>();public boolean failCommit,throwCommit;public volatile CountDownLatch entered,release;
  public synchronized String getString(String k,String d){return (String)values.getOrDefault(k,d);}public synchronized long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
  public synchronized void reloadFromDisk(){values.clear();values.putAll(disk);}
  public Editor edit(){return new Editor(){final Map<String,Object> update=new HashMap<>();public Editor putString(String k,String v){update.put(k,v);return this;}public Editor putLong(String k,long v){update.put(k,v);return this;}public boolean commit(){
   CountDownLatch wait=release;if(entered!=null)entered.countDown();if(wait!=null)try{if(!wait.await(2,TimeUnit.SECONDS))throw new AssertionError("blocked commit timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
   synchronized(MemoryPrefs.this){values.putAll(update);if(throwCommit)throw new IllegalStateException("store failed");if(failCommit)return false;disk.clear();disk.putAll(values);return true;}
  }};}
 }
}''',
    'android/util/AtomicFile.java': r'''package android.util;
import java.io.*;import java.nio.file.*;import android.os.SystemClock;
public class AtomicFile {
 public static int starts,finishes,rollbacks;public static boolean failWrite;public static long expireOnWrite;final File base,fresh;
 public AtomicFile(File f){base=f;fresh=new File(f.getPath()+".new");}
 public FileOutputStream startWrite()throws IOException{starts++;return new FileOutputStream(fresh){@Override public void write(byte[] data)throws IOException{super.write(data);if(expireOnWrite!=0)SystemClock.now=expireOnWrite;if(failWrite)throw new IOException("write failed");}};}
 public void finishWrite(FileOutputStream out){try{out.close();Files.move(fresh.toPath(),base.toPath(),StandardCopyOption.REPLACE_EXISTING);finishes++;}catch(IOException e){throw new IllegalStateException(e);}}
 public void failWrite(FileOutputStream out){try{out.close();Files.deleteIfExists(fresh.toPath());rollbacks++;}catch(IOException e){throw new IllegalStateException(e);}}
 public static void reset(){starts=finishes=rollbacks=0;failWrite=false;expireOnWrite=0;}
}''',
    'android/graphics/Bitmap.java': '''package android.graphics;import java.io.*;public class Bitmap {public static final class CompressFormat {public static final CompressFormat JPEG=new CompressFormat();}final byte[] bytes;public Bitmap(byte[] b){bytes=b;}public boolean compress(CompressFormat f,int quality,OutputStream out){try{out.write(bytes);return true;}catch(IOException e){throw new RuntimeException(e);}}}''',
    'org/json/JSONObject.java': '''package org.json;import java.util.*;public class JSONObject {final Map<String,Object> values=new HashMap<>();public JSONObject put(String k,Object v){values.put(k,v);return this;}public String optString(String k){return (String)values.getOrDefault(k,"");}public long optLong(String k){return ((Number)values.getOrDefault(k,0L)).longValue();}public String toString(){return values.toString();}}''',
    'org/taku/tryon/data/FalKeyStore.java': '''package org.taku.tryon.data;import android.content.Context;public class FalKeyStore {public FalKeyStore(Context c){}public boolean hasKey(){return true;}public void clear(){}public void set(String k){}}''',
    'org/taku/tryon/data/XmaxKeyStore.java': '''package org.taku.tryon.data;import android.content.Context;public class XmaxKeyStore {public XmaxKeyStore(Context c){}public boolean hasKey(){return true;}public void clear(){}public void set(String k){}}''',
    'org/taku/tryon/realtime/RealtimeCallConfiguration.java': '''package org.taku.tryon.realtime;import android.content.Context;public class RealtimeCallConfiguration {public enum Provider {LUCY,XMAX}public final String environment;public RealtimeCallConfiguration(Provider p,String region){environment=region;}public static RealtimeCallConfiguration loadCosplay(Context c){return new RealtimeCallConfiguration(Provider.LUCY,"global");}public static RealtimeCallConfiguration loadShopping(Context c){return loadCosplay(c);}public String providerId(){return "lucy";}public boolean hasKey(Context c){return true;}public void saveCosplay(Context c){}public void saveShopping(Context c){}}''',
    'org/taku/tryon/realtime/CallTryOnService.java': '''package org.taku.tryon.realtime;public class CallTryOnService {public static boolean busy;public static boolean busy(){return busy;}public static long reserve(){return 1;}public static void releaseReservation(long n){}public static boolean control(String v){return true;}public static int moves,px,py,positionReads;public static boolean movable=true;public static boolean move(int x,int y){moves++;if(!movable)return false;px=x;py=y;return true;}public static android.os.Bundle position(){positionReads++;android.os.Bundle b=new android.os.Bundle();b.putInt("x",px);b.putInt("y",py);b.putInt("width",360);b.putInt("height",432);b.putBoolean("canMove",movable);return b;}public static Snapshot snapshot(){return new Snapshot();}public static final class Snapshot {public String state="idle",detail="",code="none",presentation="closed";public boolean active;public int remainingSeconds,sourceFrames,returnedFrames;}}''',
    'org/taku/tryon/realtime/TryOnResultWindow.java': '''package org.taku.tryon.realtime;public class TryOnResultWindow {public static void dismissCurrent(){}}''',
    'org/taku/tryon/realtime/CallTryOnDiagnosticStore.java': '''package org.taku.tryon.realtime;import android.content.Context;import org.json.JSONObject;public class CallTryOnDiagnosticStore {public CallTryOnDiagnosticStore(Context c){}public JSONObject read(){return new JSONObject();}}''',
    'org/taku/tryon/realtime/RealtimeImage.java': '''package org.taku.tryon.realtime;import android.graphics.Bitmap;import android.os.SystemClock;public class RealtimeImage {public static long expireOnDecode;public static int closes;public final Bitmap preview;private RealtimeImage(byte[] b){preview=new Bitmap(b);}public static RealtimeImage decode(byte[] b){if(expireOnDecode!=0)SystemClock.now=expireOnDecode;return new RealtimeImage(b);}public void close(){closes++;}}''',
    'org/taku/tryon/realtime/ReferenceDeadlineTest.java': r'''package org.taku.tryon.realtime;
import android.content.*;import android.os.*;import android.util.AtomicFile;import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
public class ReferenceDeadlineTest {
 static int checks;static final byte[] OLD={1,2,3},NEW={4,5,6};
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static Context fresh()throws Exception{SystemClock.now=1000;CallTryOnService.busy=false;TryOnController.visible(true);ReferenceImport.abort();RealtimeImage.expireOnDecode=0;RealtimeImage.closes=0;AtomicFile.reset();Context c=new Context(Files.createTempDirectory("draft-").toFile());check(TryOnController.importedReference(c,OLD).getBoolean("ok"),"seed persistent reference");AtomicFile.reset();return c;}
 static Bundle draft(Context c){return TryOnController.draft(c,null);}
 static boolean bytes(Context c,byte[] expected)throws Exception{return Arrays.equals(Files.readAllBytes(new File(c.getFilesDir(),"imported-reference.jpg").toPath()),expected);}
 static void oldUnchanged(Context c,long revision)throws Exception{check(bytes(c,OLD),"failed import retains previous JPEG");Bundle old=draft(c);check(old.getLong("referenceExpires")==0&&old.getLong("revision")==revision&&old.getString("mode").equals("outfit")&&old.getString("prompt").isEmpty()&&old.getString("referenceUri").equals("content://org.taku.tryon.api/reference/current"),"failed import restores five previous draft fields");check(!new File(c.getFilesDir(),"imported-reference.jpg.new").exists(),"failed import leaves no pending JPEG");}
 static void importImage(Context c,long expiry){check(ReferenceImport.begin(),"import admitted");ReferenceImport.write(c,new ByteArrayInputStream(NEW),expiry);check(!ReferenceImport.writing(),"import always releases writing gate");}
 static void status(String state,String code){check(ReferenceImport.status().optString("state").equals(state)&&ReferenceImport.status().optString("code").equals(code),"fixed import outcome: "+state+"/"+code);}
 static void noStartAfterExpiry(Context c,long expiry){SystemClock.now=expiry;Bundle started=TryOnController.start(c,new Bundle());check(!started.getBoolean("ok")&&started.getString("code").equals("reference_expired")&&c.starts==0,"expired retained reference cannot start camera/model later");}
 public static void main(String[] args)throws Exception{
  Bundle point=new Bundle();point.putInt("x",Integer.MIN_VALUE);point.putLong("y",Integer.MAX_VALUE);
  check(TryOnController.move(point).getBoolean("ok")&&CallTryOnService.moves==1&&CallTryOnService.px==Integer.MIN_VALUE&&CallTryOnService.py==Integer.MAX_VALUE,"extras integers and JSON longs use the same Controller move without coercion");
  int moved=CallTryOnService.moves;
  for(Object invalid:new Object[]{null,"2",2.0,2.0f,true,Short.valueOf((short)2),Long.valueOf(2147483648L),Long.valueOf(-2147483649L)}){
   Bundle malformed=new Bundle();malformed.putObject("x",invalid);malformed.putInt("y",1);
   check(!TryOnController.move(malformed).getBoolean("ok")&&CallTryOnService.moves==moved,"invalid coordinate cannot reach service: "+invalid);
  }
  Bundle missing=new Bundle();missing.putInt("x",1);check(!TryOnController.move(missing).getBoolean("ok"),"both coordinates required");
  Bundle extra=new Bundle();extra.putInt("x",1);extra.putInt("y",2);extra.putString("mode","character");
  check(!TryOnController.move(extra).getBoolean("ok")&&!TryOnController.move(null).getBoolean("ok")&&CallTryOnService.moves==moved,"extra fields and null request cannot mutate");
  CallTryOnService.movable=false;check(TryOnController.move(point).getString("code").equals("presentation_unavailable"),"nonmovable presentation reports fixed error");CallTryOnService.movable=true;
  Context geometry=fresh();CallTryOnService.px=101;CallTryOnService.py=202;
  String first=TryOnController.status(geometry).getString("json");
  check(first.contains("position=")&&first.contains("x=101")&&first.contains("y=202")&&first.contains("canMove=true"),"status projects the live geometry and eligibility");
  CallTryOnService.px=303;String next=TryOnController.status(geometry).getString("json");
  check(next.contains("x=303")&&CallTryOnService.positionReads==2&&geometry.starts==0,"GUI movement is read fresh without starting a camera or model");
  Context c=fresh();check(ReferenceImport.begin(),"ordinary Agent image pipe admitted");ReferenceImport.write(c,new ByteArrayInputStream(NEW));status("ready","draft");check(bytes(c,NEW)&&draft(c).getLong("referenceExpires")==0,"ordinary image import keeps persistent zero deadline");check(!ReferenceImport.writing(),"ordinary import releases gate");
  c=fresh();long expiry=120900;SystemClock.now=80000;importImage(c,expiry);status("ready","draft");check(bytes(c,NEW)&&draft(c).getLong("referenceExpires")==expiry,"voice import immediately publishes original deadline without main callback");check(RealtimeImage.closes==1&&AtomicFile.finishes==1,"normalized image and atomic file close on success");noStartAfterExpiry(c,expiry);
  for(long invalid:new long[]{-1,1000,999}){
   c=fresh();long revision=draft(c).getLong("revision");final int[] reads={0};check(ReferenceImport.begin(),"invalid deadline import owns a gate");ReferenceImport.write(c,new ByteArrayInputStream(NEW){@Override public byte[] readNBytes(int n){reads[0]++;return NEW;}},invalid);
   status("failed",invalid<0?"invalid_request":"reference_expired");check(reads[0]==0&&AtomicFile.starts==0&&!ReferenceImport.writing(),"invalid deadline rejected before reading or writing and releases gate");oldUnchanged(c,revision);
   Bundle direct=TryOnController.importedReference(c,NEW,invalid);check(!direct.getBoolean("ok")&&direct.getString("code").equals(invalid<0?"invalid_request":"reference_expired"),"Controller rejects invalid/expired deadline independently");oldUnchanged(c,revision);
  }
  c=fresh();long revision=draft(c).getLong("revision");RealtimeImage.expireOnDecode=2000;importImage(c,2000);status("failed","reference_expired");check(RealtimeImage.closes==1&&AtomicFile.starts==0,"expiry during decode rejects before file mutation and closes image");oldUnchanged(c,revision);
  c=fresh();revision=draft(c).getLong("revision");AtomicFile.expireOnWrite=2000;importImage(c,2000);status("failed","reference_expired");check(AtomicFile.rollbacks==1&&AtomicFile.finishes==0,"expiry during JPEG write rolls back transaction");oldUnchanged(c,revision);
  for(boolean throwsFailure:new boolean[]{false,true}){
   c=fresh();revision=draft(c).getLong("revision");c.prefs.edit().putString("unrelated","keep").commit();c.prefs.failCommit=!throwsFailure;c.prefs.throwCommit=throwsFailure;importImage(c,2000);status("failed","storage_failed");check(AtomicFile.rollbacks==1&&AtomicFile.finishes==0,"draft storage failure rolls back new screenshot");oldUnchanged(c,revision);check(c.prefs.getString("unrelated","").equals("keep"),"draft rollback never clears unrelated preferences");
  }
  c=fresh();revision=draft(c).getLong("revision");c.prefs.failCommit=true;importImage(c,2000);status("failed","storage_failed");oldUnchanged(c,revision);
  c.prefs.failCommit=false;importImage(c,2000);status("ready","draft");c.prefs.reloadFromDisk();check(bytes(c,NEW)&&draft(c).getLong("referenceExpires")==2000&&draft(c).getLong("revision")==revision+1,"same-deadline retry persists expiry instead of accepting failed in-memory draft as unchanged");noStartAfterExpiry(c,2000);
  c=fresh();revision=draft(c).getLong("revision");AtomicFile.failWrite=true;importImage(c,2000);status("failed","storage_failed");check(AtomicFile.rollbacks==1&&AtomicFile.finishes==0,"JPEG write failure rolls back");oldUnchanged(c,revision);
  c=fresh();revision=draft(c).getLong("revision");CallTryOnService.busy=true;Bundle busy=TryOnController.importedReference(c,NEW,2000);check(!busy.getBoolean("ok")&&busy.getString("code").equals("busy")&&AtomicFile.starts==0,"busy attempt cannot mutate imported reference");oldUnchanged(c,revision);CallTryOnService.busy=false;
  // Hold the real Controller's draft commit while a second caller tries to read/start.
  c=fresh();final Context shared=c;c.prefs.entered=new CountDownLatch(1);c.prefs.release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
  try{
   Future<Bundle> imported=pool.submit(()->TryOnController.importedReference(shared,NEW,2000));check(c.prefs.entered.await(2,TimeUnit.SECONDS),"import reached draft transaction");check(bytes(c,OLD),"base JPEG stays old until deadline draft commits");
   CountDownLatch readerEntered=new CountDownLatch(1);Future<Bundle> reader=pool.submit(()->{readerEntered.countDown();return draft(shared);});check(readerEntered.await(2,TimeUnit.SECONDS),"concurrent draft reader entered");Thread.sleep(30);check(!reader.isDone(),"same Controller lock hides intermediate reference/deadline state");
   c.prefs.release.countDown();check(imported.get(2,TimeUnit.SECONDS).getBoolean("ok"),"blocked import succeeds");check(reader.get(2,TimeUnit.SECONDS).getLong("referenceExpires")==2000&&bytes(c,NEW),"reader sees new JPEG with original expiry together");
  }finally{c.prefs.release.countDown();pool.shutdownNow();}
  noStartAfterExpiry(c,2000);
  // A later ordinary image replaces a bounded voice reference with persistent semantics.
  SystemClock.now=3000;check(ReferenceImport.begin(),"later ordinary import admitted");ReferenceImport.write(c,new ByteArrayInputStream(OLD));status("ready","draft");check(bytes(c,OLD)&&draft(c).getLong("referenceExpires")==0,"ordinary replacement still intentionally clears voice expiry");
  System.out.println("Production reference deadline/process-local rollback checks: "+checks);
 }
}''',
}

jdk = os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@23/libexec/openjdk.jdk/Contents/Home')
with tempfile.TemporaryDirectory(prefix='taku-reference-deadline-') as temporary:
    root = Path(temporary)
    paths = []
    for name, content in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        paths.append(path)
    sources = [*paths, *[MODULE / 'app/src/org/taku/tryon/realtime' / (name + '.java')
                         for name in ['TryOnController', 'ReferenceImport']]]
    subprocess.run([jdk + '/bin/javac', '--release', '17', '-encoding', 'UTF-8', '-d', temporary, *map(str, sources)], check=True)
    subprocess.run([jdk + '/bin/java', '-Djava.io.tmpdir=' + temporary, '-cp', temporary, 'org.taku.tryon.realtime.ReferenceDeadlineTest'], check=True, timeout=20)
