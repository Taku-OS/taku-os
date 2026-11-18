package org.agentos.memory;

import android.content.*;
import android.database.Cursor;
import android.database.ContentObserver;
import android.database.sqlite.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * App-private page and notification memories. 0.4: stored DeltaKV-style (references to held lines plus the new
 * residual lines), never evicted by default, with on-device vectors, a versioned memory graph and a "current
 * work" board. Captured evidence and user corrections remain distinguishable; user deletion removes content.
 */
final class MemoryStore extends SQLiteOpenHelper {
 static final Uri URI=Uri.parse("content://org.agentos.memory");
 static MemoryStore instance;
 static synchronized MemoryStore get(Context c){if(instance==null){instance=new MemoryStore(c.getApplicationContext());instance.startSystemSettings();}return instance;}
 final Context context; final SharedPreferences prefs; final Runnable notification;
 final java.util.concurrent.ExecutorService systemSettingsWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"memory-system-settings");t.setDaemon(true);return t;});
 ContentObserver systemSettingsObserver;
 MemoryStore(Context c){this(c,()->c.getContentResolver().notifyChange(URI,null));}
 MemoryStore(Context c,Runnable notification){super(c,"memories.db",null,3);context=c;this.notification=notification;prefs=c.getSharedPreferences("memory",0);setWriteAheadLoggingEnabled(true);}
 void startSystemSettings(){observeSystemSettings();systemChanged();}
 void observeSystemSettings(){
  if(systemSettingsObserver!=null)return;
  systemSettingsObserver=new ContentObserver(new Handler(Looper.getMainLooper())){
   @Override public void onChange(boolean selfChange){onChange(selfChange,null);}
   @Override public void onChange(boolean selfChange,Uri uri){
    // Some providers send only the parent URI; in that case conservatively invalidate.
    String key=uri==null?null:uri.getLastPathSegment();
    if(uri!=null&&!uri.equals(Settings.Global.CONTENT_URI)&&key!=null&&!key.startsWith("taku_"))return;
    systemSettingsWorker.execute(()->systemChanged());
   }
  };
  context.getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI,true,systemSettingsObserver);
 }
 boolean followsSystem(){return prefs.getBoolean("followSystem",prefs.getString("model","").isEmpty());}
 String effectiveModel(){return followsSystem()?prefs.getString("systemModel",""):prefs.getString("model","");}
 synchronized void syncSystemModel(JSONObject value)throws Exception{
  if(!followsSystem())return;String model=value.getString("id");long revision=value.getLong("revision");
  if(model.equals(prefs.getString("systemModel",""))&&revision==prefs.getLong("systemRevision",-1))return;
  prefs.edit().putString("systemModel",model).putLong("systemRevision",revision).putLong("analysisGeneration",prefs.getLong("analysisGeneration",0)+1).commit();
  getWritableDatabase().execSQL("UPDATE analysis_queue SET lease='',lease_until=0");changed();requestAnalysis();
 }
 synchronized void systemChanged(){prefs.edit().remove("systemModel").putLong("systemRevision",-1).putLong("analysisGeneration",prefs.getLong("analysisGeneration",0)+1).commit();getWritableDatabase().execSQL("UPDATE analysis_queue SET lease='',lease_until=0");changed();requestAnalysis();}
 @Override public void onCreate(SQLiteDatabase db){
  db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY,source TEXT NOT NULL,page TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,digest TEXT UNIQUE NOT NULL,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,category TEXT NOT NULL DEFAULT '未分类',analysis TEXT NOT NULL DEFAULT 'pending',model TEXT NOT NULL DEFAULT '',score REAL NOT NULL DEFAULT 0,decision TEXT NOT NULL DEFAULT 'none',confidence REAL NOT NULL DEFAULT 0,suggested_at INTEGER NOT NULL DEFAULT 0,task_id TEXT NOT NULL DEFAULT '',task_state TEXT NOT NULL DEFAULT '')");
  db.execSQL("CREATE TABLE revisions(id TEXT NOT NULL,revision INTEGER NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(id,revision))");
  db.execSQL("CREATE TABLE forgotten(digest TEXT PRIMARY KEY,until_at INTEGER NOT NULL)");
  db.execSQL("CREATE INDEX memory_recent ON memories(last_seen DESC)");
  createQueue(db);v3(db);
 }
 /** Idempotent: safe to run again on a database that already has some of it. */
 static void v3(SQLiteDatabase d){
  Set<String> cols=new HashSet<>();try(Cursor c=d.rawQuery("PRAGMA table_info(memories)",null)){while(c.moveToNext())cols.add(c.getString(1));}
  boolean fresh=!cols.contains("head");
  for(String col:new String[]{"head","fresh","lines"})if(!cols.contains(col))d.execSQL("ALTER TABLE memories ADD COLUMN "+col+" TEXT NOT NULL DEFAULT ''");
  d.execSQL("CREATE TABLE IF NOT EXISTS line_store(k TEXT PRIMARY KEY,x TEXT NOT NULL)");
  d.execSQL("CREATE TABLE IF NOT EXISTS line_pairs(ck TEXT NOT NULL,k TEXT NOT NULL,PRIMARY KEY(ck,k))");
  d.execSQL("CREATE TABLE IF NOT EXISTS vectors(id TEXT PRIMARY KEY,v BLOB NOT NULL)");
  d.execSQL("CREATE TABLE IF NOT EXISTS nodes(nid TEXT NOT NULL,ver INTEGER NOT NULL,t INTEGER NOT NULL,data TEXT NOT NULL,v BLOB,PRIMARY KEY(nid,ver))");
  d.execSQL("CREATE TABLE IF NOT EXISTS gen_jobs(token TEXT PRIMARY KEY,kind TEXT NOT NULL,input TEXT NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,result TEXT NOT NULL DEFAULT '')");
  d.execSQL("CREATE TABLE IF NOT EXISTS boards(t INTEGER PRIMARY KEY,data TEXT NOT NULL)");
  d.execSQL("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
  d.execSQL("CREATE INDEX IF NOT EXISTS memory_seen ON memories(first_seen)");
  if(!fresh)return;
  // Legacy whole-page records: first line becomes head, the rest incremental content; maintenance computes missing vectors.
  d.execSQL("UPDATE memories SET head=CASE WHEN instr(body,char(10))>0 THEN substr(body,1,instr(body,char(10))-1) ELSE body END,fresh=CASE WHEN instr(body,char(10))>0 THEN substr(body,instr(body,char(10))+1) ELSE '' END");
 }
 @Override public void onOpen(SQLiteDatabase d){super.onOpen(d);if(!d.isReadOnly()){d.execSQL("DROP TABLE IF EXISTS profiles");d.execSQL("CREATE TABLE IF NOT EXISTS profiles2(t INTEGER PRIMARY KEY,data TEXT NOT NULL)");d.execSQL("CREATE TABLE IF NOT EXISTS nudges(id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,anchor TEXT NOT NULL DEFAULT '')");
  Set<String> nc=new HashSet<>();try(Cursor c=d.rawQuery("PRAGMA table_info(nudges)",null)){while(c.moveToNext())nc.add(c.getString(1));}
  for(String col:new String[]{"context","task","card","feedback"})if(!nc.contains(col))d.execSQL("ALTER TABLE nudges ADD COLUMN "+col+" TEXT NOT NULL DEFAULT ''");}}
 static void createQueue(SQLiteDatabase d){d.execSQL("CREATE TABLE analysis_queue(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,queued_at INTEGER NOT NULL,due_at INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,lease TEXT NOT NULL DEFAULT '',lease_until INTEGER NOT NULL DEFAULT 0)");}
 @Override public void onUpgrade(SQLiteDatabase d,int old,int next){if(next!=3||old<1||old>2)throw new IllegalStateException("Unsupported memory schema upgrade");if(old==1){createQueue(d);long now=System.currentTimeMillis();d.execSQL("INSERT INTO analysis_queue(id,revision,queued_at,due_at) SELECT id,revision,?,? FROM memories WHERE analysis IN ('pending','error')",new Object[]{now,now+20000});}v3(d);}
 synchronized JSONObject config()throws Exception{
  JSONArray sources=new JSONArray();for(String s:new TreeSet<>(prefs.getStringSet("sources",Set.of())))sources.put(s);
  return new JSONObject().put("neverObserved",new JSONArray(new TreeSet<>(NEVER_OBSERVED))).put("enabled",prefs.getBoolean("enabled",false)).put("analysisEnabled",prefs.getBoolean("analysisEnabled",false)).put("analysisGeneration",prefs.getLong("analysisGeneration",0)).put("queryEnabled",prefs.getBoolean("queryEnabled",false)).put("proactive",prefs.getBoolean("proactive",true)).put("nudges",prefs.getBoolean("nudges",false)).put("feedCards",prefs.getBoolean("feedCards",false)).put("feedDescribe",prefs.getBoolean("feedDescribe",false)).put("model",effectiveModel()).put("modelOverride",prefs.getString("model","")).put("followsSystem",followsSystem()).put("sources",sources).put("generation",prefs.getLong("generation",0)).put("retentionDays",prefs.getInt("retentionDays",0)).put("userName",userName()).put("autoOrganize",prefs.getBoolean("autoOrganize",true)).put("organizeModel",organizeModel()).put("agentBoard",prefs.getBoolean("agentBoard",true)).put("agentProfile",prefs.getBoolean("agentProfile",true)).put("debugHooks",BuildFlags.DEBUG).put("testNow",BuildFlags.DEBUG?prefs.getLong("testNow",0):0);
 }
 /** Content reads share the same live switch across the signed and open Android APIs. */
 static final Set<String> QUERY_METHODS=Set.of("search","get","board","profile","agenda","nudges","graph","browse");
 synchronized JSONObject query(String method,JSONObject p,boolean requireEnabled,boolean managementView)throws Exception{
  if(requireEnabled&&!prefs.getBoolean("queryEnabled",false))throw new SecurityException("Agent memory queries are disabled");
  switch(method){
   case "search":return new JSONObject().put("items",search(p.optString("query"),p.optInt("limit",10)));
   case "get":return readAny(p.getString("id"),p.optBoolean("history",false));
   case "board":return (managementView&&!p.optBoolean("forAgent")?boardView():agentBoardView()).put("revokedAt",revokedAt());
   case "profile":return (managementView&&!p.optBoolean("forAgent")?profileView():agentProfileFor(p.optString("text",""))).put("revokedAt",revokedAt());
   case "agenda":return new JSONObject().put("items",Recall.agenda(this,Math.max(0,Math.min(14,p.optInt("days",3))),Math.max(1,Math.min(20,p.optInt("limit",15)))));
   case "nudges":return new JSONObject().put("items",nudges(now()-Math.max(1,Math.min(7,p.optInt("days",7)))*86400000L,Math.max(1,Math.min(30,p.optInt("limit",30)))));
   case "browse":return browse(p.optString("query"),p.optString("category"),p.optString("source"),p.optInt("limit",100),p.optInt("offset",0));
   case "graph":{
    JSONArray items=new JSONArray();int limit=Math.max(1,Math.min(100,p.optInt("limit",100))),offset=Math.max(0,p.optInt("offset",0));
    try(Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT nid FROM nodes WHERE t<=? ORDER BY nid LIMIT ? OFFSET ?",new String[]{Long.toString(now()),Integer.toString(limit),Integer.toString(offset)})){
     while(c.moveToNext())items.put(readNode(c.getString(0)));
    }
    return new JSONObject().put("items",items).put("offset",offset);
   }
   default:throw new IllegalArgumentException("Unknown memory query: "+method);
  }
 }
 synchronized JSONObject configure(JSONObject input)throws Exception{
  SharedPreferences.Editor e=prefs.edit();boolean feedWas=feedOn();
  for(String key:new String[]{"enabled","analysisEnabled","queryEnabled","proactive","nudges","feedCards","feedDescribe"})if(input.has(key))e.putBoolean(key,input.getBoolean(key));
  if(input.has("model")){String m=input.getString("model");if(m.length()>512)throw new IllegalArgumentException(context.getString(R.string.error_model_id_too_long));e.putString("model",m);if(!input.has("followSystem"))e.putBoolean("followSystem",m.isEmpty());}
  if(input.has("followSystem"))e.putBoolean("followSystem",input.getBoolean("followSystem"));
  if(input.has("sources")){JSONArray a=input.getJSONArray("sources");if(a.length()>200)throw new IllegalArgumentException(context.getString(R.string.error_too_many_sources));Set<String> p=new HashSet<>();for(int i=0;i<a.length();i++){String s=a.getString(i);if(!s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException(context.getString(R.string.error_invalid_source));p.add(s);}e.putStringSet("sources",p);}
  if(input.has("retentionDays")){int days=input.getInt("retentionDays");if(days<0||days>30)throw new IllegalArgumentException(context.getString(R.string.error_invalid_retention));e.putInt("retentionDays",days);}
  if(input.has("userName")){String n=input.getString("userName").trim();if(n.isEmpty()||n.length()>20)throw new IllegalArgumentException(context.getString(R.string.error_invalid_user_name));e.putString("userName",n);}
  if(input.has("autoOrganize"))e.putBoolean("autoOrganize",input.getBoolean("autoOrganize"));
  if(input.has("organizeModel")){String m=input.getString("organizeModel");if(m.length()>64||!m.matches("[A-Za-z0-9._-]*"))throw new IllegalArgumentException(context.getString(R.string.error_model_id_too_long));e.putString("organizeModel",m);}
  if(input.has("agentBoard"))e.putBoolean("agentBoard",input.getBoolean("agentBoard"));
  if(input.has("agentProfile"))e.putBoolean("agentProfile",input.getBoolean("agentProfile"));
  if(input.has("agendaFields")){if(!BuildFlags.DEBUG)throw new SecurityException(context.getString(R.string.error_debug_only));e.putBoolean("agendaFields",input.getBoolean("agendaFields"));}   // Replay comparison: omit due / stake / related.
  if(input.has("testNow")){if(!BuildFlags.DEBUG)throw new SecurityException(context.getString(R.string.error_debug_only));long t=input.getLong("testNow");if(t<0)throw new IllegalArgumentException(context.getString(R.string.error_invalid_test_time));e.putLong("testNow",t);}
  boolean revoke=input.has("analysisEnabled")||input.has("model")||input.has("followSystem")||input.has("sources");
  if(revoke)e.putLong("analysisGeneration",prefs.getLong("analysisGeneration",0)+1);
  e.putLong("generation",prefs.getLong("generation",0)+1);if(!e.commit())throw new IllegalStateException(context.getString(R.string.error_settings_not_saved));if(revoke)getWritableDatabase().execSQL("UPDATE analysis_queue SET lease='',lease_until=0");cleanQueue();changed();ObservationService.policyChanged();requestAnalysis();
  if(feedWas&&!feedOn())ProactiveNotice.withdrawLater(context,this);   // Home cards or proactive speech disabled: retract Memory's Home cards.
  return config();
 }
 /** Reminders go to the home feed: speaking up first and the feed cards are both on. */
 boolean feedOn(){return prefs.getBoolean("nudges",false)&&prefs.getBoolean("feedCards",false);}
 boolean sourceAllowed(String source){return prefs.getStringSet("sources",Set.of()).contains(source);}
 /**
  * Apps memory never records, whatever the source list says: itself, Taku's agent apps (the assistant and the new home
  * org.agentos.agent, whose feed shows memory's own reminder cards and whose chat holds what Taku said), the system display
  * and System UI. Recording them would feed memory its own output.
  */
 static final Set<String> NEVER_OBSERVED=Set.of("org.agentos.memory","org.agentos.assistant","org.agentos.agent","org.agentos.surfaces","com.android.systemui");
 boolean observing(String source){return prefs.getBoolean("enabled",false)&&sourceAllowed(source)&&!NEVER_OBSERVED.contains(source);}
 static String digest(String text)throws Exception{byte[] b=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v));return s.toString();}
 static String bounded(String s,int n){return s==null?"":s.substring(0,Math.min(n,s.length()));}
 /** The record's text as people see it: its first line (kept as head) and the rest, without repeating the title. */
 static String visible(String title,String head,String body){if(head==null||head.isEmpty()||head.equals(title))return body==null?"":body;if(body==null||body.isEmpty())return head;return body.startsWith(head)?body:head+"\n"+body;}   // Records upgraded from 0.3 already include the first line in body.
 String userName(){return prefs.getString("userName","我");}
 /** The moment memory is viewed at. Always now in release builds; a debug build can pin it (root only) to replay a recorded timeline. */
 boolean agendaFields(){return !BuildFlags.DEBUG||prefs.getBoolean("agendaFields",true);}
 long now(){if(!BuildFlags.DEBUG)return System.currentTimeMillis();long t=prefs.getLong("testNow",0);return t>0?t:System.currentTimeMillis();}
 java.time.LocalDate today(){return java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate();}
 /** With a 1–30 day limit, derived entries last seen before it are dropped too; null when nothing is forgotten. */
 java.time.LocalDate retentionCutoff(){int days=prefs.getInt("retentionDays",0);return days<=0?null:today().minusDays(days);}
 void failed(String what,Exception e){StackTraceElement[] st=e.getStackTrace();String at=st.length>0?st[0].getFileName()+":"+st[0].getLineNumber():"";android.util.Log.w("TakuMemory",what+" write failed",e);prefs.edit().putString("lastError",what+"："+e.getClass().getSimpleName()+" "+e.getMessage()+" @"+at).putLong("errors",prefs.getLong("errors",0)+1).apply();}
 /** Never forgets by default (retentionDays 0). A user-chosen 1–30 day limit still removes old evidence and what depends on it. */
 boolean pruning;   // prune → remove → read must not recurse into prune while retention is enabled.
 synchronized void prune(){
  if(pruning)return;pruning=true;try{pruneOnce();}finally{pruning=false;}
 }
 void pruneOnce(){
  SQLiteDatabase d=getWritableDatabase();d.delete("forgotten","until_at<?",new String[]{""+System.currentTimeMillis()});
  int days=prefs.getInt("retentionDays",0);if(days<=0)return;
  long until=System.currentTimeMillis()-days*86400000L;List<String> old=new ArrayList<>();
  d.delete("boards","t<?",new String[]{""+until});d.delete("profiles2","t<?",new String[]{""+until});
  try(Cursor c=d.rawQuery("SELECT id FROM memories WHERE first_seen<?",new String[]{""+until})){while(c.moveToNext())old.add(c.getString(0));}
  for(String id:old)try{remove(id,false);}catch(Exception ignored){}
 }
 final Map<String,ArrayDeque<String[]>> recentPages=new HashMap<>();final Map<String,Set<String>> pairCache=new HashMap<>();
 final java.util.concurrent.ExecutorService embedWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"memory-embed");t.setDaemon(true);return t;});
 ArrayDeque<String[]> recent(SQLiteDatabase d,String source,String title){
  String key=source+"\n"+title;ArrayDeque<String[]> q=recentPages.get(key);if(q!=null)return q;q=new ArrayDeque<>();
  try(Cursor c=d.rawQuery("SELECT lines FROM (SELECT lines,first_seen,id FROM memories WHERE source=? AND title=? AND lines<>'' ORDER BY first_seen DESC,id DESC LIMIT "+Delta.REFS+") ORDER BY first_seen,id",new String[]{source,title})){while(c.moveToNext())q.addLast(resolve(d,c.getString(0)));}
  recentPages.put(key,q);return q;
 }
 String[] resolve(SQLiteDatabase d,String keys){
  if(keys.isEmpty())return new String[0];String[] k=keys.split(",");String[] out=new String[k.length];
  for(int i=0;i<k.length;i++)try(Cursor c=d.rawQuery("SELECT x FROM line_store WHERE k=?",new String[]{k[i]})){out[i]=c.moveToFirst()?c.getString(0):"";}
  return out;
 }
 Set<String> pairs(SQLiteDatabase d,String ck){Set<String> p=pairCache.get(ck);if(p!=null)return p;p=new HashSet<>();try(Cursor c=d.rawQuery("SELECT k FROM line_pairs WHERE ck=?",new String[]{ck})){while(c.moveToNext())p.add(c.getString(0));}pairCache.put(ck,p);return p;}
 synchronized JSONObject ingest(String source,String page,String title,String body,long generation)throws Exception{
  if(!observing(source)||generation!=prefs.getLong("generation",0))return null;
  boolean note=page.equals("notification");
  body=bounded(body.replaceAll("[ \\t]+"," ").trim(),8000);if(body.length()<(note?8:16))return null;
  prune();SQLiteDatabase d=getWritableDatabase();String hash=digest(source+"\n"+body);
  try(Cursor c=d.rawQuery("SELECT 1 FROM forgotten WHERE digest=?",new String[]{hash})){if(c.moveToFirst())return null;}
  try(Cursor c=d.rawQuery("SELECT id FROM memories WHERE digest=?",new String[]{hash})){if(c.moveToFirst())return null;}
  title=bounded(title,100);String[] lines=body.split("\n",-1);
  // Incremental storage: compare recent pages with the same title; save only new lines, excluding previously seen (previous,current) line pairs.
  ArrayDeque<String[]> recent=recent(d,source,title);String ck=Delta.key(source+"\n"+title+"\n"+lines[0]);Set<String> seen=pairs(d,ck);Set<String> before=new HashSet<>(seen);
  Delta.Result r=Delta.diff(lines,new ArrayList<>(recent),seen);
  recent.addLast(lines);while(recent.size()>Delta.REFS)recent.removeFirst();
  String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
  StringBuilder keys=new StringBuilder();for(String l:lines){if(keys.length()>0)keys.append(',');keys.append(Delta.key(l));}
  d.beginTransaction();try{
   for(String k:seen)if(!before.contains(k))d.execSQL("INSERT OR IGNORE INTO line_pairs(ck,k) VALUES(?,?)",new Object[]{ck,k});
   if(!r.fresh.isEmpty()){
    for(String l:lines)d.execSQL("INSERT OR IGNORE INTO line_store(k,x) VALUES(?,?)",new Object[]{Delta.key(l),l});
    ContentValues v=new ContentValues();v.put("id",id);v.put("source",source);v.put("page",bounded(page,300));v.put("title",title);v.put("body",r.show);v.put("head",r.head);v.put("fresh",r.freshText);v.put("lines",keys.toString());v.put("digest",hash);v.put("first_seen",now);v.put("last_seen",now);
    d.insertOrThrow("memories",null,v);d.execSQL("INSERT INTO analysis_queue(id,revision,queued_at,due_at) VALUES(?,1,?,?)",new Object[]{id,now,now+20000});
   }
   d.setTransactionSuccessful();
  }finally{d.endTransaction();}
  if(r.fresh.isEmpty())return null;   // Every page line is already stored.
  embedLater(id,Recall.evidenceText(title,r.head,r.show),now);changed();requestAnalysis();
  // Proactive speech defaults off and currently uses only notifications with one clear sender. Multi-person page captures caused replay false positives.
  if(note)Proactive.captured(context,this,source,title,body,now());
  return read(id,false);
 }
 void embedLater(String id,String text,long seen){embedWorker.execute(()->{try{embedNow(id,text,seen);}catch(Exception ignored){}});}
 void embedNow(String id,String text,long seen)throws Exception{
  Embedder e=Recall.embedder(context);if(e==null)return;float[] v=e.encode(bounded(text,600),false);
  synchronized(this){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM memories WHERE id=?",new String[]{id})){if(!c.moveToFirst())return;}ContentValues c=new ContentValues();c.put("id",id);c.put("v",Embedder.pack(v));getWritableDatabase().insertWithOnConflict("vectors",null,c,SQLiteDatabase.CONFLICT_REPLACE);}
  Recall.removeVector(id);Recall.addVector(id,seen,v);
 }
 /** Vectors missing after a crash, an upgrade or before the model was ready. */
 int ensureVectors()throws Exception{
  if(!kvGet("vectorModel","").equals(Embedder.MODEL_ID)){
   // Vector model changed (quantized since 0.8): recompute document/node vectors, then record the new model.
   Embedder e=Recall.embedder(context);if(e==null)return 0;List<Object[]> nodesTodo=new ArrayList<>();
   synchronized(this){getWritableDatabase().execSQL("DELETE FROM vectors");try(Cursor c=getReadableDatabase().rawQuery("SELECT nid,ver,data FROM nodes",null)){while(c.moveToNext())nodesTodo.add(new Object[]{c.getString(0),c.getInt(1),c.getString(2)});}}
   Recall.resetVectors();
   for(Object[] x:nodesTodo){byte[] v=Embedder.pack(e.encode(Recall.ntext(new JSONObject((String)x[2])),false));synchronized(this){ContentValues cv=new ContentValues();cv.put("v",v);getWritableDatabase().update("nodes",cv,"nid=? AND ver=?",new String[]{(String)x[0],""+x[1]});}}
   Recall.invalidateNodes();kvSet("vectorModel",Embedder.MODEL_ID);
  }
  List<String[]> todo=new ArrayList<>();
  synchronized(this){try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,head,body,first_seen FROM memories WHERE id NOT IN (SELECT id FROM vectors) ORDER BY first_seen",null)){while(c.moveToNext())todo.add(new String[]{c.getString(0),Recall.evidenceText(c.getString(1),c.getString(2),c.getString(3)),""+c.getLong(4)});}}
  for(String[] t:todo)embedNow(t[0],t[1],Long.parseLong(t[2]));return todo.size();
 }
 synchronized float[] vectorOf(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT v FROM vectors WHERE id=?",new String[]{id})){return c.moveToFirst()?Embedder.unpack(c.getBlob(0)):null;}}
 synchronized String fullText(String keys){return String.join("\n",resolve(getReadableDatabase(),keys));}
 synchronized JSONObject readAny(String id,boolean history)throws Exception{
  if(id.matches("n[0-9]{1,7}"))return readNode(id);
  if(id.matches("e[0-9]{1,9}")){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM memories WHERE rowid=?",new String[]{id.substring(1)})){if(!c.moveToFirst())throw new IllegalArgumentException(context.getString(R.string.error_memory_not_found));id=c.getString(0);}}
  JSONObject r=read(id,history);r.put("fullText",fullText(r.optString("lines")));r.remove("lines");r.remove("fresh");return r;
 }
 synchronized JSONObject read(String id,boolean history)throws Exception{requireId(id);prune();return readRow(id,history);}
 synchronized JSONObject readRow(String id,boolean history)throws Exception{
  requireId(id);JSONObject r;
  try(Cursor c=getReadableDatabase().rawQuery("SELECT rowid AS ref,* FROM memories WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new IllegalArgumentException(context.getString(R.string.error_memory_not_found));r=row(c);r.put("ref","e"+r.getLong("ref"));r.put("text",visible(r.optString("title"),r.optString("head"),r.optString("body")));}
  if(history){JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT revision,title,body,at FROM revisions WHERE id=? ORDER BY revision DESC LIMIT 5",new String[]{id})){while(c.moveToNext())a.put(row(c));}r.put("history",a);}
  return r;
 }
 /** Agent search: hybrid keyword + on-device vector + memory graph (see Recall). */
 JSONArray search(String query,int limit)throws Exception{
  if(query.length()>500)throw new IllegalArgumentException(context.getString(R.string.error_query_too_long));prune();
  return Recall.search(this,query,Math.max(1,Math.min(20,limit)));
 }
 synchronized JSONObject evidenceItem(String id)throws Exception{
  try(Cursor c=getReadableDatabase().rawQuery("SELECT rowid,id,source,title,head,body,first_seen,last_seen,revision,category FROM memories WHERE id=?",new String[]{id})){
   if(!c.moveToFirst())throw new IllegalArgumentException(context.getString(R.string.error_memory_not_found));String title=c.getString(3),body=(c.getString(4).isEmpty()?"":c.getString(4)+"\n")+c.getString(5);
   body=bounded(body,Math.max(0,400-title.length()-1));
   return new JSONObject().put("kind","e").put("id",c.getString(1)).put("ref","e"+c.getLong(0)).put("source",c.getString(2)).put("title",title).put("body",body).put("first_seen",c.getLong(6)).put("last_seen",c.getLong(7)).put("revision",c.getInt(8)).put("category",c.getString(9));
  }
 }
 synchronized List<String> refsToIds(JSONArray refs){List<String> out=new ArrayList<>();for(int i=0;refs!=null&&i<refs.length();i++){String r=refs.optString(i);if(!r.matches("e[0-9]{1,9}"))continue;try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM memories WHERE rowid=?",new String[]{r.substring(1)})){if(c.moveToFirst())out.add(c.getString(0));}}return out;}
 JSONObject nodeItem(String nid,JSONObject v)throws Exception{
  return new JSONObject().put("kind","n").put("id",nid).put("title","["+v.optString("type")+"] "+v.optString("title")).put("body",Recall.nrender(v)).put("when",v.optString("when")).put("status",v.optString("status")).put("evidence",new JSONArray(refsToIds(v.optJSONArray("evidence")))).put("first_seen",v.optLong("t")).put("source","memory-graph");
 }
 synchronized JSONObject readNode(String nid)throws Exception{
  JSONArray versions=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM nodes WHERE nid=? AND t<=? ORDER BY ver",new String[]{nid,""+now()})){while(c.moveToNext())versions.put(new JSONObject(c.getString(0)));}
  if(versions.length()==0)throw new IllegalArgumentException(context.getString(R.string.error_node_not_found));
  JSONObject latest=versions.getJSONObject(versions.length()-1);JSONArray ev=new JSONArray();for(String id:refsToIds(latest.optJSONArray("evidence")))try{ev.put(evidenceItem(id));}catch(Exception gone){}
  return nodeItem(nid,latest).put("versions",versions).put("evidenceItems",ev);
 }
 // Graph: append revisions while retaining old ones.
 void applyGraph(JSONArray ups,List<JSONObject> batch,long t)throws Exception{applyGraph(ups,batch,t,-1);}
 /** gen: the derivedGen read when the batch was prepared; a delete or correction since then makes this run discard its result. */
 void applyGraph(JSONArray ups,List<JSONObject> batch,long t,long gen)throws Exception{
  Set<String> eids=new HashSet<>();for(JSONObject e:batch)eids.add(e.getString("ref"));
  Set<String> types=Set.of("person","event","task","preference","item","place","fact"),states=Set.of("active","done","cancelled","superseded");
  List<JSONObject> clean=new ArrayList<>();
  for(int i=0;i<ups.length();i++){JSONObject u=ups.getJSONObject(i);if(!types.contains(u.optString("type"))||!states.contains(u.optString("status"))||u.optString("title").isEmpty()||u.optString("id").isEmpty())continue;clean.add(u);}
  Map<String,JSONObject> plan=new LinkedHashMap<>();int[] next={0};
  synchronized(this){
   SQLiteDatabase d=getReadableDatabase();Set<String> existing=new HashSet<>();Map<String,JSONObject> latest=new HashMap<>();
   try(Cursor c=d.rawQuery("SELECT nid,data FROM nodes ORDER BY ver",null)){while(c.moveToNext()){existing.add(c.getString(0));latest.put(c.getString(0),new JSONObject(c.getString(1)));}}
   Map<String,String> newmap=new LinkedHashMap<>();int count=0;   // New node IDs follow the maximum; deleted node IDs are never reused.
   try(Cursor c=d.rawQuery("SELECT COALESCE(MAX(CAST(SUBSTR(nid,2) AS INTEGER)),-1)+1 FROM nodes",null)){c.moveToFirst();count=Math.max(c.getInt(0),Integer.parseInt(kvGet("nextNid","0")));}
   for(JSONObject u:clean){String id=u.getString("id");if((id.startsWith("new:")||!existing.contains(id))&&!newmap.containsKey(id))newmap.put(id,"n"+(count+newmap.size()));}
   next[0]=count+newmap.size();
   for(JSONObject u:clean){
    String nid=newmap.getOrDefault(u.getString("id"),u.getString("id"));JSONObject v=new JSONObject();
    for(String k:new String[]{"type","title","summary","when","due","stake","amount","status"})v.put(k,bounded(u.optString(k),k.equals("summary")?600:120));
    if(u.has("attend"))v.put("attend",u.optBoolean("attend"));   // Whether when requires physical attendance; organizer-supplied, absent from old graphs.
    JSONArray people=new JSONArray();for(String p:Recall.strings(u.optJSONArray("people")))if(!p.isEmpty()&&p.length()<=30)people.put(p);v.put("people",people);
    JSONArray rel=new JSONArray();for(String r:Recall.strings(u.optJSONArray("related"))){String m=newmap.getOrDefault(r,r);if((existing.contains(m)||newmap.containsKey(r))&&!m.equals(nid))rel.put(m);}v.put("related",rel);
    LinkedHashSet<String> ev=new LinkedHashSet<>();for(String x:Recall.strings(u.optJSONArray("evidence")))if(eids.contains(x))ev.add(x);
    JSONObject prev=plan.containsKey(nid)?plan.get(nid):latest.get(nid);if(prev!=null)ev.addAll(Recall.strings(prev.optJSONArray("evidence")));
    v.put("evidence",new JSONArray(ev));v.put("t",t);plan.put(nid,v);existing.add(nid);
   }
  }
  Embedder e=Recall.embedder(context);Map<String,byte[]> vec=new HashMap<>();
  for(Map.Entry<String,JSONObject> x:plan.entrySet())if(e!=null)try{vec.put(x.getKey(),Embedder.pack(e.encode(Recall.ntext(x.getValue()),false)));}catch(Exception ignored){}
  synchronized(this){
   if(gen>=0&&gen!=derivedGen())throw new IllegalStateException(context.getString(R.string.error_records_changed_while_organizing));
   SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{
    for(Map.Entry<String,JSONObject> x:plan.entrySet()){int ver=0;try(Cursor c=d.rawQuery("SELECT COALESCE(MAX(ver),0) FROM nodes WHERE nid=?",new String[]{x.getKey()})){c.moveToFirst();ver=c.getInt(0)+1;}
     ContentValues v=new ContentValues();v.put("nid",x.getKey());v.put("ver",ver);v.put("t",t);v.put("data",x.getValue().toString());if(vec.containsKey(x.getKey()))v.put("v",vec.get(x.getKey()));d.insertOrThrow("nodes",null,v);}
    kvSetIn(d,"nextNid",""+next[0]);
    d.setTransactionSuccessful();}finally{d.endTransaction();}
  }
  Recall.invalidateNodes();changed();
 }
 // Board: append nightly revisions chronologically; apply Privacy before saving, displaying, or handing to the assistant.
 synchronized JSONObject board()throws Exception{return boardAt(now());}
 synchronized JSONObject boardAt(long t)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM boards WHERE t<=? ORDER BY t DESC LIMIT 1",new String[]{""+t})){return c.moveToFirst()?new JSONObject(c.getString(0)):new JSONObject().put("active",new JSONArray()).put("done",new JSONArray());}}
 synchronized long boardTime(long t){try(Cursor c=getReadableDatabase().rawQuery("SELECT t FROM boards WHERE t<=? ORDER BY t DESC LIMIT 1",new String[]{""+t})){return c.moveToFirst()?c.getLong(0):0;}}
 synchronized JSONObject boardView()throws Exception{long t=boardTime(now());JSONObject b=Privacy.cleanBoard(Privacy.suppressBoard(board(),suppressed("board")),today(),retentionCutoff());return new JSONObject().put("updated",t).put("text",Organizer.render(b)).put("board",b);}
 /** What the assistant receives: empty when the user turns the board off for the assistant. */
 synchronized JSONObject agentBoardView()throws Exception{return prefs.getBoolean("agentBoard",true)?boardView():new JSONObject().put("updated",0).put("text","");}
 synchronized void saveBoard(long t,JSONObject b)throws Exception{ContentValues v=new ContentValues();v.put("t",t);v.put("data",Privacy.cleanBoard(Privacy.suppressBoard(b,suppressed("board")),today(),null).toString());getWritableDatabase().insertWithOnConflict("boards",null,v,SQLiteDatabase.CONFLICT_REPLACE);changed();}
 // User card: facts about the user and important people, appended chronologically.
 synchronized long profileTime(long t){try(Cursor c=getReadableDatabase().rawQuery("SELECT t FROM profiles2 WHERE t<=? ORDER BY t DESC LIMIT 1",new String[]{""+t})){return c.moveToFirst()?c.getLong(0):0;}}
 synchronized JSONObject profileAt(long t)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM profiles2 WHERE t<=? ORDER BY t DESC LIMIT 1",new String[]{""+t})){return c.moveToFirst()?new JSONObject(c.getString(0)):new JSONObject().put("me",new JSONArray()).put("people",new JSONArray());}}
 synchronized void saveProfile(long t,JSONObject p)throws Exception{ContentValues v=new ContentValues();v.put("t",t);v.put("data",Privacy.cleanProfile(Privacy.suppressProfile(p,suppressed("profile")),today(),null).toString());getWritableDatabase().insertWithOnConflict("profiles2",null,v,SQLiteDatabase.CONFLICT_REPLACE);Recall.clearProfileCache();changed();}
 synchronized JSONObject profile()throws Exception{return Privacy.cleanProfile(Privacy.suppressProfile(profileAt(now()),suppressed("profile")),today(),retentionCutoff());}
 /** The whole card, for the user's own view. */
 synchronized JSONObject profileView()throws Exception{JSONObject p=profile();return new JSONObject().put("updated",profileTime(now())).put("text",Organizer.renderProfile(p)).put("profile",p);}
 /** What the assistant receives for one message: the short card plus what this message touches (see Recall.profileFor). */
 JSONObject agentProfileFor(String text)throws Exception{
  if(!prefs.getBoolean("agentProfile",true))return new JSONObject().put("updated",0).put("text","");
  long started=android.os.SystemClock.elapsedRealtime(),now,t;JSONObject p;
  synchronized(this){now=now();t=profileTime(now);if(t==0)return new JSONObject().put("updated",0).put("text","");p=profile();}
  String card=Recall.profileFor(this,p,text==null?"":text,t,now);
  return new JSONObject().put("updated",t).put("text",card).put("ms",android.os.SystemClock.elapsedRealtime()-started);
 }
 /** The same card at a given moment, for speaking up unprompted (Proactive); independent of the assistant's card switch. */
 JSONObject agentProfileAt(String text,long now)throws Exception{
  long t;JSONObject p;synchronized(this){t=profileTime(now);if(t==0)return new JSONObject().put("updated",0).put("text","");p=Privacy.cleanProfile(Privacy.suppressProfile(profileAt(now),suppressed("profile")),today(),retentionCutoff());}
  return new JSONObject().put("updated",t).put("text",Recall.profileFor(this,p,text==null?"":text,t,now));
 }
 // Previously spoken reminders: settings/suggestions display and duplicate-reminder prevention.
 synchronized long saveNudge(String kind,String title,String body,String anchor,long at){return saveNudge(kind,title,body,anchor,"",at);}
 synchronized long saveNudge(String kind,String title,String body,String anchor,String context,long at){ContentValues v=new ContentValues();v.put("at",at);v.put("kind",kind);v.put("title",bounded(title,120));v.put("body",bounded(body,1200));v.put("anchor",bounded(anchor,120));v.put("context",bounded(context==null?"":context,4000));long id=getWritableDatabase().insert("nudges",null,v);getWritableDatabase().execSQL("DELETE FROM nudges WHERE at<?",new Object[]{at-30L*86400000L});changed();return id;}
 /** The home-feed card a reminder was published as; `nudge` returns it with the reminder. */
 synchronized void nudgeCard(long id,String card){getWritableDatabase().execSQL("UPDATE nudges SET card=? WHERE id=?",new Object[]{card,id});}
 synchronized JSONArray nudges(long since,int limit)throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,at,kind,title,body,anchor,task,card,feedback FROM nudges WHERE at>=? ORDER BY at DESC LIMIT ?",new String[]{""+since,""+limit})){while(c.moveToNext()){JSONArray f=FeedFeedback.parse(c.getString(8));a.put(new JSONObject().put("id",c.getLong(0)).put("at",c.getLong(1)).put("kind",c.getString(2)).put("title",c.getString(3)).put("body",c.getString(4)).put("anchor",c.getString(5)).put("task",c.getString(6)).put("card",c.getString(7)).put("feedback",f).put("reactions",new JSONArray(FeedFeedback.reactions(f))));}}return a;}
 /** Reminders since then that have a home feed card: {id, card, feedback}, for reading the user's reactions back. */
 synchronized JSONArray nudgeCards(long since)throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,card,feedback FROM nudges WHERE at>=? AND card!='' ORDER BY at",new String[]{""+since})){while(c.moveToNext())a.put(new JSONObject().put("id",c.getLong(0)).put("card",c.getString(1)).put("feedback",FeedFeedback.parse(c.getString(2))));}return a;}
 synchronized void nudgeFeedback(long id,JSONArray events){getWritableDatabase().execSQL("UPDATE nudges SET feedback=? WHERE id=?",new Object[]{events.toString(),id});changed();}
 /** One reminder with what the model saw, for handing it to the assistant (Broker only). */
 synchronized JSONObject nudge(long id)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT id,at,kind,title,body,context,task,card,feedback FROM nudges WHERE id=?",new String[]{""+id})){if(!c.moveToFirst())throw new IllegalArgumentException(context.getString(R.string.nudge_gone));return new JSONObject().put("id",c.getLong(0)).put("at",c.getLong(1)).put("kind",c.getString(2)).put("title",c.getString(3)).put("body",c.getString(4)).put("context",c.getString(5)).put("task",c.getString(6)).put("card",c.getString(7)).put("feedback",FeedFeedback.parse(c.getString(8)));}}
 synchronized void nudgeTask(long id,String task){if(!task.matches("[0-9]{13,17}"))throw new IllegalArgumentException(context.getString(R.string.error_invalid_task_id));getWritableDatabase().execSQL("UPDATE nudges SET task=? WHERE id=? AND task=''",new Object[]{task,id});changed();}
 synchronized int nudgesSince(long since,String kind){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM nudges WHERE at>=? AND kind=?",new String[]{""+since,kind})){return c.moveToFirst()?c.getInt(0):0;}}
 synchronized void addProactiveUsage(String kind,JSONObject r){JSONObject u=r.optJSONObject("usage");long n=0;if(u!=null){JSONObject t=u.optJSONObject("total");n=t!=null?t.optLong("totalTokens"):u.optLong("totalTokens");}prefs.edit().putLong("usage_"+kind,prefs.getLong("usage_"+kind,0)+n).putLong("runs_"+kind,prefs.getLong("runs_"+kind,0)+1).apply();}
 // User deletes a board/card entry: create a revision and record that it must not be restored.
 static final int SUPPRESS_LIMIT=60;
 synchronized JSONArray suppressed(String kind)throws Exception{return new JSONArray(kvGet("suppress_"+kind,"[]"));}
 /** entry: {kind: board|me|person|note, name?, item?, note?}; newest last, at most SUPPRESS_LIMIT kept. */
 synchronized void suppress(String kind,JSONObject entry)throws Exception{JSONArray a=suppressed(kind),o=new JSONArray();String key=entry.toString();for(int i=Math.max(0,a.length()-SUPPRESS_LIMIT+1);i<a.length();i++)if(!String.valueOf(a.opt(i)).equals(key))o.put(a.opt(i));o.put(entry);kvSet("suppress_"+kind,o.toString());}
 synchronized void deleteBoardItem(String part,String name)throws Exception{
  JSONObject b=board();JSONArray a=b.optJSONArray(part),o=new JSONArray();boolean hit=false;
  for(int i=0;a!=null&&i<a.length();i++){JSONObject x=a.getJSONObject(i);if(!hit&&x.optString("name").equals(name)){hit=true;continue;}o.put(x);}
  if(!hit)throw new IllegalArgumentException(context.getString(R.string.error_board_changed));b.put(part,o);suppress("board",new JSONObject().put("kind","board").put("name",bounded(name,200)));saveBoard(Math.max(now(),boardTime(Long.MAX_VALUE)+1),b);bumpDerived();markRevoked();
 }
 /** kind: me (item is the entry), person (item is the name), or note (item is the name; note is the note/date). */
 synchronized void deleteProfileItem(String kind,String item,String note)throws Exception{
  JSONObject p=profileAt(now());boolean hit=false;
  if(kind.equals("me")){JSONArray a=p.optJSONArray("me"),o=new JSONArray();for(int i=0;a!=null&&i<a.length();i++){JSONObject x=a.getJSONObject(i);if(!hit&&x.optString("item").equals(item)){hit=true;continue;}o.put(x);}p.put("me",o);if(hit)suppress("profile",new JSONObject().put("kind","me").put("item",bounded(item,200)));}
  else{JSONArray a=p.optJSONArray("people"),o=new JSONArray();
   for(int i=0;a!=null&&i<a.length();i++){JSONObject x=a.getJSONObject(i);if(hit||!x.optString("name").equals(item)){o.put(x);continue;}
    if(kind.equals("person")){hit=true;continue;}
    JSONArray notes=x.optJSONArray("notes"),keep=new JSONArray();for(int k=0;notes!=null&&k<notes.length();k++){if(!hit&&notes.optString(k).equals(note)){hit=true;continue;}keep.put(notes.optString(k));}
    JSONArray dates=x.optJSONArray("dates"),keepD=new JSONArray();for(int k=0;dates!=null&&k<dates.length();k++){if(!hit&&dates.optString(k).equals(note)){hit=true;continue;}keepD.put(dates.optString(k));}
    o.put(new JSONObject(x.toString()).put("notes",keep).put("dates",keepD));}
   p.put("people",o);if(hit)suppress("profile",kind.equals("person")?new JSONObject().put("kind","person").put("name",bounded(item,60)):new JSONObject().put("kind","note").put("name",bounded(item,60)).put("note",bounded(note,200)));}
  if(!hit)throw new IllegalArgumentException(context.getString(R.string.error_card_changed));saveProfile(Math.max(now(),profileTime(Long.MAX_VALUE)+1),p);bumpDerived();markRevoked();
 }
 /** Forgets every board and user-card version and rebuilds both from the remaining records at the next organize. */
 synchronized void clearDerived()throws Exception{invalidateDerived(0);markRevoked();}
 /** The user removed or corrected something the assistant may already have seen; the assistant is told on its next turn (HarnessContext). */
 void markRevoked(){kvSet("revokedAt",""+System.currentTimeMillis());}
 long revokedAt(){try{return Long.parseLong(kvGet("revokedAt","0"));}catch(NumberFormatException bad){return 0;}}
 /** Board and card versions written at or after `since` may contain what was just deleted or corrected: drop them, rewind the organizer to the newest version before it, and ask for a rebuild. */
 synchronized void invalidateDerived(long since)throws Exception{
  SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{
   d.delete("boards","t>=?",new String[]{""+since});d.delete("profiles2","t>=?",new String[]{""+since});
   d.execSQL("DELETE FROM gen_jobs WHERE state NOT IN ('queued','running')");
   long b=through(d,"boards"),p=through(d,"profiles2");
   kvSetIn(d,"boardCursor",""+b);kvSetIn(d,"profile2Cursor",""+p);kvSetIn(d,"rebuildPending","1");kvSetIn(d,"derivedGen",""+(derivedGen()+1));
   d.setTransactionSuccessful();}finally{d.endTransaction();}
  Recall.clearProfileCache();changed();
 }
 /** How far the newest remaining version had read (the "through" it was saved with; older versions: their time). */
 static long through(SQLiteDatabase d,String table){try(Cursor c=d.rawQuery("SELECT t,data FROM "+table+" ORDER BY t DESC LIMIT 1",null)){if(!c.moveToFirst())return 0;try{return new JSONObject(c.getString(1)).optLong("through",c.getLong(0));}catch(Exception bad){return c.getLong(0);}}}
 /** Bumped whenever the user deletes or corrects something the board or card may hold; an organize run that started before it does not save. */
 synchronized long derivedGen(){return Long.parseLong(kvGet("derivedGen","0"));}
 synchronized void bumpDerived(){kvSet("derivedGen",""+(derivedGen()+1));}
 synchronized void commitDerived(String kind,long gen,long t,JSONObject data,long through)throws Exception{
  if(gen!=derivedGen())throw new IllegalStateException(context.getString(R.string.error_derived_changed_while_organizing));
  if(data!=null){if(kind.equals("board"))saveBoard(t,data);else saveProfile(t,data);}
  kvSet(kind.equals("board")?"boardCursor":"profile2Cursor",""+through);
 }
 synchronized void organized(long gen){prefs.edit().putLong("organizedAt",System.currentTimeMillis()).putLong("organizeFailedAt",0).apply();if(gen==derivedGen())kvSet("rebuildPending","0");}
 static void kvSetIn(SQLiteDatabase d,String k,String v){d.execSQL("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)",new Object[]{k,v});}
 // Organization jobs: token plus readback; model requests go only through Assistant Broker.
 synchronized String kvGet(String k,String def){try(Cursor c=getReadableDatabase().rawQuery("SELECT v FROM kv WHERE k=?",new String[]{k})){return c.moveToFirst()?c.getString(0):def;}}
 synchronized void kvSet(String k,String v){getWritableDatabase().execSQL("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)",new Object[]{k,v});}
 JSONObject generate(String kind,String input)throws Exception{
  String token=UUID.randomUUID().toString();
  synchronized(this){getWritableDatabase().execSQL("DELETE FROM gen_jobs WHERE created<?",new Object[]{System.currentTimeMillis()-86400000L});ContentValues v=new ContentValues();v.put("token",token);v.put("kind",kind);v.put("input",input);v.put("created",System.currentTimeMillis());v.put("state","queued");getWritableDatabase().insertOrThrow("gen_jobs",null,v);}
  BrokerClient.call("generate",new JSONObject().put("token",token).put("kind",kind));
  long deadline=android.os.SystemClock.elapsedRealtime()+8*60000L;   // Simulated clocks can jump; use elapsed boot time for timeouts.
  while(android.os.SystemClock.elapsedRealtime()<deadline){
   Thread.sleep(800);String st,res;
   synchronized(this){try(Cursor c=getReadableDatabase().rawQuery("SELECT state,result FROM gen_jobs WHERE token=?",new String[]{token})){if(!c.moveToFirst())throw new IllegalStateException(context.getString(R.string.error_organize_batch_lost));st=c.getString(0);res=c.getString(1);}}
   if(st.equals("done")){synchronized(this){getWritableDatabase().execSQL("DELETE FROM gen_jobs WHERE token=?",new Object[]{token});}JSONObject r=new JSONObject(res);if(!r.optBoolean("ok"))throw new IllegalStateException(r.optString("message",context.getString(R.string.error_organize_failed)));return r;}
  }
  synchronized(this){getWritableDatabase().execSQL("DELETE FROM gen_jobs WHERE token=?",new Object[]{token});}
  throw new IllegalStateException(context.getString(R.string.error_organize_timeout));
 }
 synchronized JSONObject jobInput(String token)throws Exception{
  try(Cursor c=getReadableDatabase().rawQuery("SELECT kind,input,state FROM gen_jobs WHERE token=?",new String[]{token})){if(!c.moveToFirst()||!(c.getString(2).equals("queued")||c.getString(2).equals("running")))throw new IllegalStateException(context.getString(R.string.error_organize_batch_expired));getWritableDatabase().execSQL("UPDATE gen_jobs SET state='running' WHERE token=?",new Object[]{token});return new JSONObject().put("kind",c.getString(0)).put("input",c.getString(1)).put("model",organizeModel());}
 }
 synchronized void jobResult(String token,JSONObject result){getWritableDatabase().execSQL("UPDATE gen_jobs SET state='done',result=? WHERE token=? AND state IN ('queued','running')",new Object[]{result.toString(),token});}
 /** "" recommended models per task, "system" the Taku AI text model, or a model id. */
 String organizeModel(){return prefs.getString("organizeModel","");}
 long usageTotal(){return prefs.getLong("usage_graph",0)+prefs.getLong("usage_board",0)+prefs.getLong("usage_profile2",0);}
 /** One finished organize run: tokens it used and the models that served it, for the settings page. */
 synchronized void organizeRun(long tokensBefore,long at){
  java.util.Set<String> m=new java.util.LinkedHashSet<>();for(String k:new String[]{"graph","board","profile2"}){String x=prefs.getString("runModel_"+k,"");if(!x.isEmpty())m.add(x);}
  if(m.isEmpty())return;   // A run without new content or model calls must not replace the previous record.
  prefs.edit().putLong("lastOrganizeTokens",Math.max(0,usageTotal()-tokensBefore)).putLong("lastOrganizeAt",at).putString("lastOrganizeModels",String.join(" / ",m)).putString("lastOrganizeSource",prefs.getString("runSource","")).remove("runModel_graph").remove("runModel_board").remove("runModel_profile2").remove("runSource").apply();
 }
 synchronized void addUsage(String kind,JSONObject r){
  JSONObject u=r.optJSONObject("usage");long n=0;if(u!=null){JSONObject t=u.optJSONObject("total");n=t!=null?t.optLong("totalTokens"):u.optLong("totalTokens");}
  prefs.edit().putLong("usage_"+kind,prefs.getLong("usage_"+kind,0)+n).putLong("runs_"+kind,prefs.getLong("runs_"+kind,0)+1).putString("model_"+kind,r.optString("model")).putString("runModel_"+kind,r.optString("model")).putString("runSource",r.optString("modelSource",prefs.getString("runSource",""))).apply();
 }
 /** Internal library browsing only. The signed Agent API continues to cap search at 20. */
 synchronized JSONObject browse(String query,String category,String source,int limit,int offset)throws Exception{
  if(query.length()>500)throw new IllegalArgumentException(context.getString(R.string.error_query_too_long));prune();List<String> args=new ArrayList<>();StringBuilder where=new StringBuilder("1=1");
  for(String token:query.trim().split("\\s+")){if(token.isEmpty())continue;where.append(" AND (body LIKE ? ESCAPE '\\' OR head LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\')");String t="%"+token.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";args.add(t);args.add(t);args.add(t);args.add(t);}
  if(!category.isEmpty()){where.append(" AND category=?");args.add(category);}
  if(!source.isEmpty()){where.append(" AND source=?");args.add(source);}
  String[] selection=args.toArray(new String[0]);int count;
  try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories WHERE "+where,selection)){c.moveToFirst();count=c.getInt(0);}
  JSONArray a=new JSONArray();String page=Math.max(0,offset)+","+Math.max(1,Math.min(100,limit));
  try(Cursor c=getReadableDatabase().query("memories",null,where.toString(),selection,null,null,"last_seen DESC,id DESC",page)){while(c.moveToNext()){JSONObject o=row(c);o.put("text",visible(o.optString("title"),o.optString("head"),o.optString("body")));a.put(o);}}
  return new JSONObject().put("items",a).put("total",count);
 }
 synchronized List<String> recordedSources(){prune();List<String> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT source FROM memories ORDER BY source",null)){while(c.moveToNext())result.add(c.getString(0));}return result;}
 synchronized JSONArray suggestionRecords()throws Exception{prune();JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM memories WHERE task_id<>'' OR suggested_at>0 ORDER BY last_seen DESC,id DESC LIMIT 1000",null)){while(c.moveToNext())a.put(row(c));}return a;}
 synchronized JSONObject update(String id,int revision,String title,String body)throws Exception{
  if(body.isBlank()||body.length()>8000||title.isBlank()||title.length()>100)throw new IllegalArgumentException(context.getString(R.string.error_invalid_content));JSONObject old=read(id,false);if(old.getInt("revision")!=revision)throw new IllegalStateException(context.getString(R.string.error_memory_changed));SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{
   ContentValues h=new ContentValues();h.put("id",id);h.put("revision",revision);h.put("title",old.getString("title"));h.put("body",old.getString("text"));h.put("at",System.currentTimeMillis());d.insertOrThrow("revisions",null,h);
   StringBuilder keys=new StringBuilder();for(String l:body.split("\n",-1)){if(keys.length()>0)keys.append(',');keys.append(Delta.key(l));d.execSQL("INSERT OR IGNORE INTO line_store(k,x) VALUES(?,?)",new Object[]{Delta.key(l),l});}
   ContentValues v=new ContentValues();v.put("title",title);v.put("body",body);v.put("head","");v.put("fresh",body);v.put("lines",keys.toString());v.put("revision",revision+1);v.put("last_seen",System.currentTimeMillis());v.put("analysis","corrected");v.put("decision","none");v.put("suggested_at",0);d.update("memories",v,"id=?",new String[]{id});d.execSQL("DELETE FROM revisions WHERE id=? AND revision<?",new Object[]{id,revision-4});d.delete("analysis_queue","id=?",new String[]{id});d.setTransactionSuccessful();
  }finally{d.endTransaction();}
  d.beginTransaction();try{
   for(String k:old.optString("lines").split(",")){if(k.isEmpty())continue;try(Cursor c=d.rawQuery("SELECT 1 FROM memories WHERE lines LIKE ? LIMIT 1",new String[]{"%"+k+"%"})){if(!c.moveToFirst())d.delete("line_store","k=?",new String[]{k});}}
   d.delete("vectors","id=?",new String[]{id});purgeNodes(d,old.getString("ref"),id);d.setTransactionSuccessful();}finally{d.endTransaction();}
  Recall.removeVector(id);Recall.invalidateNodes();recentPages.clear();embedLater(id,Recall.evidenceText(title,"",body),old.getLong("first_seen"));
  invalidateDerived(old.getLong("first_seen"));markRevoked();changed();ObservationService.withdraw(id);return read(id,true);
 }
 /** An explicit edit remains available while queries are off, but cannot return old evidence. */
 synchronized JSONObject updateFromOpenAccess(JSONObject p)throws Exception{
  JSONObject result=update(p.getString("id"),p.getInt("revision"),p.getString("title"),p.getString("body"));
  return prefs.getBoolean("queryEnabled",false)?result:new JSONObject().put("id",result.getString("id")).put("revision",result.getInt("revision"));
 }
 synchronized void forget(String id)throws Exception{remove(id,true);}
 /**
  * A deleted or corrected record may live on in graph node text. Every node version that cites it is dropped (evidence lists
  * accumulate, so that is exactly the versions written after it was organized); what those versions also cited goes back to the
  * next graph run, which rebuilds the nodes from the remaining records — and from the corrected text when `corrected` is given.
  */
 /** Before any node is deleted: remember the next free id, so a later node never takes a deleted node's id. */
 void reserveNodeIds(SQLiteDatabase d){try(Cursor c=d.rawQuery("SELECT COALESCE(MAX(CAST(SUBSTR(nid,2) AS INTEGER)),-1)+1 FROM nodes",null)){c.moveToFirst();int n=c.getInt(0);if(n>Integer.parseInt(kvGet("nextNid","0")))kvSetIn(d,"nextNid",""+n);}}
 void purgeNodes(SQLiteDatabase d,String ref,String corrected)throws Exception{
  reserveNodeIds(d);
  Map<String,List<Object[]>> byNode=new LinkedHashMap<>();
  try(Cursor c=d.rawQuery("SELECT nid,ver,data FROM nodes ORDER BY ver",null)){while(c.moveToNext())byNode.computeIfAbsent(c.getString(0),k->new ArrayList<>()).add(new Object[]{c.getInt(1),new JSONObject(c.getString(2))});}
  LinkedHashSet<String> redo=new LinkedHashSet<>();
  for(Map.Entry<String,List<Object[]>> n:byNode.entrySet()){
   Set<String> kept=new HashSet<>(),dropped=new LinkedHashSet<>();boolean hit=false;
   for(Object[] v:n.getValue()){Set<String> ev=Recall.strings(((JSONObject)v[1]).optJSONArray("evidence"));if(ev.contains(ref)){hit=true;dropped.addAll(ev);d.delete("nodes","nid=? AND ver=?",new String[]{n.getKey(),""+v[0]});}else kept.addAll(ev);}
   if(!hit)continue;dropped.removeAll(kept);dropped.remove(ref);redo.addAll(dropped);
  }
  JSONArray ids=new JSONArray(kvGet("graphRedo","[]"));Set<String> have=new HashSet<>();for(int i=0;i<ids.length();i++)have.add(ids.optString(i));
  for(String r:redo){if(!r.matches("e[0-9]{1,9}"))continue;try(Cursor c=d.rawQuery("SELECT id FROM memories WHERE rowid=?",new String[]{r.substring(1)})){if(c.moveToFirst()&&have.add(c.getString(0)))ids.put(c.getString(0));}}
  if(corrected!=null&&have.add(corrected))ids.put(corrected);
  kvSetIn(d,"graphRedo",ids.toString());
 }
 synchronized JSONArray graphRedo()throws Exception{return new JSONArray(kvGet("graphRedo","[]"));}
 synchronized void graphRedone(Collection<String> ids)throws Exception{JSONArray a=graphRedo(),o=new JSONArray();for(int i=0;i<a.length();i++)if(!ids.contains(a.optString(i)))o.put(a.optString(i));kvSet("graphRedo",o.toString());}
 /** Removes a record and everything derived only from it: its vector, lines no other record holds, and graph nodes it alone supported. */
 synchronized void remove(String id,boolean tombstone)throws Exception{
  JSONObject old=readRow(id,false);String ref=old.getString("ref");SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{
   if(tombstone){ContentValues v=new ContentValues();v.put("digest",old.getString("digest"));v.put("until_at",System.currentTimeMillis()+30*86400000L);d.insertWithOnConflict("forgotten",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
   d.delete("revisions","id=?",new String[]{id});d.delete("memories","id=?",new String[]{id});d.delete("analysis_queue","id=?",new String[]{id});d.delete("vectors","id=?",new String[]{id});
   for(String k:old.optString("lines").split(",")){if(k.isEmpty())continue;try(Cursor c=d.rawQuery("SELECT 1 FROM memories WHERE lines LIKE ? LIMIT 1",new String[]{"%"+k+"%"})){if(!c.moveToFirst())d.delete("line_store","k=?",new String[]{k});}}
   if(tombstone)purgeNodes(d,ref,null);
   else{   // Retention expiry removes references and nodes supported only by this record, without daily graph rebuilds.
    List<String[]> touched=new ArrayList<>();try(Cursor c=d.rawQuery("SELECT nid,ver,data FROM nodes",null)){while(c.moveToNext()){JSONObject v=new JSONObject(c.getString(2));if(Recall.strings(v.optJSONArray("evidence")).contains(ref))touched.add(new String[]{c.getString(0),""+c.getInt(1),c.getString(2)});}}
    Set<String> drop=new HashSet<>();
    for(String[] n:touched){JSONObject v=new JSONObject(n[2]);LinkedHashSet<String> ev=new LinkedHashSet<>(Recall.strings(v.optJSONArray("evidence")));ev.remove(ref);if(ev.isEmpty())drop.add(n[0]);else{v.put("evidence",new JSONArray(ev));d.execSQL("UPDATE nodes SET data=? WHERE nid=? AND ver=?",new Object[]{v.toString(),n[0],n[1]});}}
    if(!drop.isEmpty())reserveNodeIds(d);for(String nid:drop)d.delete("nodes","nid=?",new String[]{nid});
   }
   d.setTransactionSuccessful();}finally{d.endTransaction();}
  recentPages.clear();Recall.removeVector(id);Recall.invalidateNodes();
  // User deletion also removes derived board/card content. Retention pruning instead filters last-seen time through retentionCutoff without rebuilding.
  if(tombstone){invalidateDerived(old.getLong("first_seen"));markRevoked();}
  changed();ObservationService.withdraw(id);
 }
 synchronized boolean analyzed(String id,int revision,long generation,String model,String category,double score,String decision,double confidence,String error)throws Exception{
  if(!prefs.getBoolean("analysisEnabled",false)||generation!=prefs.getLong("generation",0))return false;
  JSONObject r;try{r=read(id,false);}catch(IllegalArgumentException gone){return false;}
  if(r.getInt("revision")!=revision||!sourceAllowed(r.getString("source")))return false;
  ContentValues v=new ContentValues();v.put("analysis",error.isEmpty()?"ready":"error");v.put("model",model);v.put("category",category);v.put("score",score);v.put("decision",decision);v.put("confidence",confidence);
  boolean offer=error.isEmpty()&&actionable(decision)&&confidence>=.75&&prefs.getBoolean("proactive",true)&&prefs.getBoolean("queryEnabled",false);
  v.put("suggested_at",offer?System.currentTimeMillis():0);getWritableDatabase().update("memories",v,"id=? AND revision=?",new String[]{id,""+revision});changed();return offer;
 }
 synchronized JSONObject reserve(String id,int revision,String task)throws Exception{
  JSONObject r=read(id,false);if(!r.getString("task_id").isEmpty())return r;
  if(!task.matches("[0-9]{13,17}"))throw new IllegalArgumentException(context.getString(R.string.error_invalid_task_id));
  if(!sourceAllowed(r.getString("source"))||!prefs.getBoolean("analysisEnabled",false)||!prefs.getBoolean("proactive",true)||!prefs.getBoolean("queryEnabled",false)||!r.getString("model").equals(effectiveModel())||r.getInt("revision")!=revision||!actionable(r.getString("decision"))||r.getDouble("confidence")<.75||r.getLong("suggested_at")==0||System.currentTimeMillis()-r.getLong("suggested_at")>300000)throw new IllegalStateException(context.getString(R.string.error_suggestion_expired));
  ContentValues v=new ContentValues();v.put("task_id",task);v.put("task_state","reserved");getWritableDatabase().update("memories",v,"id=? AND task_id=''",new String[]{id});changed();return read(id,false);
 }
 synchronized void task(String id,String task,String state)throws Exception{requireId(id);ContentValues v=new ContentValues();v.put("task_state",bounded(state,80));if(getWritableDatabase().update("memories",v,"id=? AND task_id=? AND task_state<>?",new String[]{id,task,bounded(state,80)})>0)changed();}
 synchronized boolean acquireAnalysisBudget(){
  long now=System.currentTimeMillis(),start=prefs.getLong("analysisHourStart",0),last=prefs.getLong("analysisLast",0);int count=prefs.getInt("analysisCount",0);
  if(now-start>=3600000||start==0){start=now;count=0;}
  if(now-last<15000||count>=60)return false;
  return prefs.edit().putLong("analysisHourStart",start).putLong("analysisLast",now).putInt("analysisCount",count+1).commit();
 }
 synchronized JSONObject status()throws Exception{JSONObject r=config();prune();try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories",null)){c.moveToFirst();r.put("count",c.getInt(0));}cleanQueue();try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN lease<>'' THEN 1 ELSE 0 END),0) FROM analysis_queue",null)){c.moveToFirst();r.put("analysisQueued",c.getInt(0)).put("analysisInFlight",c.getInt(1));}int vectors=0,nodes=0,versions=0,lines=0;SQLiteDatabase rd=getReadableDatabase();try(Cursor c=rd.rawQuery("SELECT (SELECT COUNT(*) FROM vectors),(SELECT COUNT(DISTINCT nid) FROM nodes),(SELECT COUNT(*) FROM nodes),(SELECT COUNT(*) FROM line_store)",null)){c.moveToFirst();vectors=c.getInt(0);nodes=c.getInt(1);versions=c.getInt(2);lines=c.getInt(3);}
  r.put("vectors",vectors).put("graphNodes",nodes).put("graphVersions",versions).put("lines",lines).put("lastError",prefs.getString("lastError","")).put("errors",prefs.getLong("errors",0)).put("organizing",Organizer.running).put("organizerState",Organizer.state).put("embedder",Recall.embedder!=null?"ready":Recall.embedderError.isEmpty()?"loading":Recall.embedderError).put("boardUpdated",boardTime(now())).put("profileUpdated",profileTime(now())).put("rebuildPending",kvGet("rebuildPending","0").equals("1")).put("organizedAt",prefs.getLong("organizedAt",0))
   .put("usage",new JSONObject().put("graph",prefs.getLong("usage_graph",0)).put("board",prefs.getLong("usage_board",0)).put("profile",prefs.getLong("usage_profile2",0)).put("graphRuns",prefs.getLong("runs_graph",0)).put("boardRuns",prefs.getLong("runs_board",0)).put("profileRuns",prefs.getLong("runs_profile2",0)).put("graphModel",prefs.getString("model_graph","")).put("boardModel",prefs.getString("model_board","")).put("profileModel",prefs.getString("model_profile2",""))).put("lastOrganize",new JSONObject().put("tokens",prefs.getLong("lastOrganizeTokens",0)).put("at",prefs.getLong("lastOrganizeAt",0)).put("models",prefs.getString("lastOrganizeModels","")).put("source",prefs.getString("lastOrganizeSource","")));
  return r.put("feedFeedbackCursor",kvGet("feedFeedbackCursor","0")).put("analysisBatchAttempts",prefs.getLong("batchAttempts",0)).put("lastBatchSize",prefs.getInt("lastBatchSize",0)).put("schema","taku.memory/3").put("observationConnected",ObservationService.current!=null);}

 static final int BATCH_SIZE=5;static final long MERGE_MS=20000,LEASE_MS=120000;
 synchronized void cleanQueue(){
  SQLiteDatabase d=getWritableDatabase();d.execSQL("DELETE FROM analysis_queue WHERE id NOT IN (SELECT id FROM memories WHERE analysis<>'corrected')");
  try(Cursor c=d.rawQuery("SELECT q.id,m.source FROM analysis_queue q JOIN memories m ON q.id=m.id",null)){while(c.moveToNext())if(!sourceAllowed(c.getString(1)))d.delete("analysis_queue","id=?",new String[]{c.getString(0)});}
 }
 synchronized long budgetDelay(long now){long start=prefs.getLong("analysisHourStart",0),last=prefs.getLong("analysisLast",0);long wait=Math.max(0,last+15000-now);if(start>0&&now-start<3600000&&prefs.getInt("analysisCount",0)>=60)wait=Math.max(wait,start+3600000-now);return wait;}
 synchronized long queueDelay(long now){
  prune();cleanQueue();if(!prefs.getBoolean("analysisEnabled",false)||(effectiveModel().isEmpty()&&!followsSystem()))return -1;
  long due=Long.MAX_VALUE;try(Cursor c=getReadableDatabase().rawQuery("SELECT due_at,lease,lease_until FROM analysis_queue",null)){while(c.moveToNext())due=Math.min(due,c.getString(1).isEmpty()?c.getLong(0):Math.max(c.getLong(0),c.getLong(2)));}
  return due==Long.MAX_VALUE?-1:Math.max(Math.max(0,due-now),budgetDelay(now));
 }
 synchronized JSONObject claimBatch(long now)throws Exception{
  if(effectiveModel().isEmpty()||queueDelay(now)!=0)return null;SQLiteDatabase d=getWritableDatabase();String token=UUID.randomUUID().toString();JSONArray items=new JSONArray();
  d.execSQL("UPDATE analysis_queue SET lease='',lease_until=0 WHERE lease_until<=?",new Object[]{now});
  try(Cursor c=d.rawQuery("SELECT m.* FROM analysis_queue q JOIN memories m ON q.id=m.id WHERE q.lease='' AND ((q.attempts=0 AND q.queued_at<=?) OR q.due_at<=?) ORDER BY q.queued_at,m.id LIMIT 5",new String[]{""+now,""+now})){while(c.moveToNext())items.put(row(c));}
  if(items.length()==0)return null;
  long start=prefs.getLong("analysisHourStart",0);int count=prefs.getInt("analysisCount",0);if(start==0||now-start>=3600000){start=now;count=0;}
  if(!prefs.edit().putLong("analysisHourStart",start).putLong("analysisLast",now).putInt("analysisCount",count+1).putLong("batchAttempts",prefs.getLong("batchAttempts",0)+1).putInt("lastBatchSize",items.length()).commit())return null;
  d.beginTransaction();try{for(int i=0;i<items.length();i++)d.execSQL("UPDATE analysis_queue SET lease=?,lease_until=?,attempts=attempts+1 WHERE id=?",new Object[]{token,now+LEASE_MS,items.getJSONObject(i).getString("id")});d.setTransactionSuccessful();}finally{d.endTransaction();}
  changed();return new JSONObject().put("token",token).put("generation",prefs.getLong("analysisGeneration",0)).put("model",effectiveModel()).put("modelOverride",prefs.getString("model","")).put("followsSystem",followsSystem()).put("items",items);
 }
 synchronized JSONObject batchEvidence(String token,long generation,String model)throws Exception{
  requireId(token);if(!prefs.getBoolean("analysisEnabled",false)||generation!=prefs.getLong("analysisGeneration",0)||!model.equals(effectiveModel()))throw new IllegalStateException(context.getString(R.string.error_analysis_changed));
  cleanQueue();JSONArray items=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT m.* FROM analysis_queue q JOIN memories m ON q.id=m.id WHERE q.lease=? AND q.lease_until>? AND q.revision=m.revision ORDER BY q.queued_at,m.id LIMIT 5",new String[]{token,""+System.currentTimeMillis()})){while(c.moveToNext())items.put(row(c));}
  if(items.length()==0)throw new IllegalStateException(context.getString(R.string.error_batch_expired));return new JSONObject().put("items",items).put("followsSystem",followsSystem()).put("modelOverride",prefs.getString("model",""));
 }
 synchronized JSONArray completeBatch(JSONObject batch,JSONObject answers)throws Exception{
  JSONArray items=batch.getJSONArray("items"),offers=new JSONArray();long generation=batch.getLong("generation");String token=batch.getString("token"),model=batch.getString("model");
  if(!prefs.getBoolean("analysisEnabled",false)||generation!=prefs.getLong("analysisGeneration",0)||!model.equals(effectiveModel()))return offers;
  // Validate the entire response before applying any member; IDs are mapped by the immutable claimed order.
  for(int i=0;i<items.length();i++){String p="r"+i+"_";JSONObject cat=answers.getJSONObject(p+"category"),next=answers.getJSONObject(p+"next_step");if(!Set.of("work","life","travel","learn","other").contains(cat.getString("choice"))||!Set.of("organize","summarize","none").contains(next.getString("choice")))throw new IllegalArgumentException(context.getString(R.string.error_invalid_answer));double n=answers.getJSONObject(p+"importance").getDouble("noul"),v=next.getDouble("confidence");if(!Double.isFinite(n)||!Double.isFinite(v)||n<0||n>1||v<0||v>1)throw new IllegalArgumentException(context.getString(R.string.error_invalid_probability));}
  SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{for(int i=0;i<items.length();i++){
   JSONObject item=items.getJSONObject(i);String id=item.getString("id"),p="r"+i+"_";
   try(Cursor c=d.rawQuery("SELECT revision FROM analysis_queue WHERE id=? AND lease=?",new String[]{id,token})){if(!c.moveToFirst()||c.getInt(0)!=item.getInt("revision"))continue;}
   String category=switch(answers.getJSONObject(p+"category").getString("choice")){case "work"->"工作";case "life"->"生活";case "travel"->"出行";case "learn"->"学习";default->"其他";};JSONObject next=answers.getJSONObject(p+"next_step");
   if(analyzed(id,item.getInt("revision"),prefs.getLong("generation",0),model,category,answers.getJSONObject(p+"importance").getDouble("noul"),next.getString("choice"),next.getDouble("confidence"),""))offers.put(id);
   d.delete("analysis_queue","id=? AND lease=?",new String[]{id,token});
  }d.setTransactionSuccessful();}finally{d.endTransaction();}changed();return offers;
 }
 synchronized void retryBatch(String token,long now){SQLiteDatabase d=getWritableDatabase();try(Cursor c=d.rawQuery("SELECT id,attempts FROM analysis_queue WHERE lease=?",new String[]{token})){while(c.moveToNext()){long delay=Math.min(1800000L,30000L*(1L<<Math.min(6,Math.max(0,c.getInt(1)-1))));d.execSQL("UPDATE analysis_queue SET lease='',lease_until=0,due_at=? WHERE id=? AND lease=?",new Object[]{now+delay,c.getString(0),token});}}changed();}
 static boolean actionable(String kind){return kind.equals("organize")||kind.equals("summarize");}
 void requireId(String id){if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException(context.getString(R.string.error_invalid_memory_id));}
 static JSONObject row(Cursor c)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++){switch(c.getType(i)){case Cursor.FIELD_TYPE_INTEGER:o.put(c.getColumnName(i),c.getLong(i));break;case Cursor.FIELD_TYPE_FLOAT:o.put(c.getColumnName(i),c.getDouble(i));break;default:o.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));}}return o;}
 void requestAnalysis(){new android.os.Handler(android.os.Looper.getMainLooper()).post(()->AnalysisJob.schedule(context));}
 void changed(){notification.run();}
}
