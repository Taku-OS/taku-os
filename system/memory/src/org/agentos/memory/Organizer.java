package org.agentos.memory;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.util.*;

/**
 * Nightly organization of new evidence into the memory graph (versioned nodes, never deleted on rewrite)
 * and the "current work" board. Model work goes through the assistant broker with a reserved batch token;
 * this APK has no network access and holds no credentials. One run at a time; resumable by cursor.
 */
final class Organizer {
 static final int GRAPH_LIMIT=6000,BOARD_LIMIT=15000,CANDIDATES=45;
 static volatile boolean running;static volatile String state;   // UI state is written in the current language; null means organization has never run.
 static final String[] WD={"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};

 /** @param asOf the moment being organized (ms); 0 means now. A test harness that simulates days passes its simulated time. */
 static boolean start(Context c,long asOf){return start(c,asOf,false);}
 /** graphOnly (debug replay): rebuild only the memory graph, keeping the board and card as they are. */
 static synchronized boolean start(Context c,long asOf,boolean graphOnly){
  if(running)return false;running=true;state=c.getString(R.string.organize_busy);
  new Thread(()->{MemoryStore s=MemoryStore.get(c);long now=asOf>0?asOf:System.currentTimeMillis();long gen=s.derivedGen();
   long before=s.usageTotal();
   boolean ok=false;
   try{graph(s,now);if(graphOnly){state=c.getString(R.string.organize_done);return;}board(s,now);profile(s,now);s.organized(gen);s.organizeRun(before,System.currentTimeMillis());state=c.getString(R.string.organize_done);ok=true;}
   catch(Exception e){state=c.getString(R.string.organize_failed,String.valueOf(e.getMessage()));s.prefs.edit().putLong("organizeFailedAt",System.currentTimeMillis()).apply();android.util.Log.w("TakuMemory","Organization interrupted",e);}finally{running=false;s.changed();}
   if(ok)Proactive.afterOrganize(c,s,now);   // After evening organization: bedtime reminder, disabled by default, once nightly.
  },"memory-organizer").start();
  return true;
 }

 static String stamp(long ms){java.time.ZonedDateTime d=java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault());return d.getMonthValue()+"-"+d.getDayOfMonth()+" "+WD[d.getDayOfWeek().getValue()-1]+" "+String.format(Locale.ROOT,"%02d:%02d",d.getHour(),d.getMinute());}

 static List<JSONObject> pending(MemoryStore s,String cursorKey,long now)throws Exception{
  long cursor=Long.parseLong(s.kvGet(cursorKey,"0"));List<JSONObject> out=new ArrayList<>();
  try(Cursor c=s.getReadableDatabase().rawQuery("SELECT rowid,id,title,head,body,first_seen FROM memories WHERE first_seen>? AND first_seen<=? ORDER BY first_seen,id",new String[]{""+cursor,""+now})){
   while(c.moveToNext())out.add(new JSONObject().put("ref","e"+c.getLong(0)).put("id",c.getString(1)).put("show",Recall.evidenceText(c.getString(2),c.getString(3),c.getString(4))).put("t",c.getLong(5)));
  }
  return out;
 }
 static List<List<JSONObject>> split(List<JSONObject> items,int limit)throws Exception{
  List<List<JSONObject>> out=new ArrayList<>();List<JSONObject> cur=new ArrayList<>();int size=0;
  for(JSONObject e:items){int n=e.getString("show").length();if(!cur.isEmpty()&&size+n>limit){out.add(cur);cur=new ArrayList<>();size=0;}cur.add(e);size+=n;}
  if(!cur.isEmpty())out.add(cur);return out;
 }

 static void graph(MemoryStore s,long now)throws Exception{
  // First rebuild nodes affected by deletion/correction using remaining evidence and corrected source text.
  List<JSONObject> redo=redoItems(s,now);
  if(!redo.isEmpty()){List<List<JSONObject>> rb=split(redo,GRAPH_LIMIT);for(int b=0;b<rb.size();b++){state=s.context.getString(R.string.organize_graph_redo,b+1,rb.size());graphBatch(s,now,rb.get(b));List<String> done=new ArrayList<>();for(JSONObject e:rb.get(b))done.add(e.getString("id"));s.graphRedone(done);}}
  List<List<JSONObject>> batches=split(pending(s,"graphCursor",now),GRAPH_LIMIT);
  for(int b=0;b<batches.size();b++){
   List<JSONObject> batch=batches.get(b);state=s.context.getString(R.string.organize_graph,b+1,batches.size());
   graphBatch(s,now,batch);s.kvSet("graphCursor",""+batch.get(batch.size()-1).getLong("t"));
  }
 }
 static void graphBatch(MemoryStore s,long now,List<JSONObject> batch)throws Exception{
  long gen=s.derivedGen();
  StringBuilder in=new StringBuilder("User's name: "+s.userName()+"\nCurrent time: "+stamp(now)+".\n\n# Existing nodes (possibly related)\n");
  List<JSONObject> cands=candidates(s,batch);
  if(cands.isEmpty())in.append("(none)");else for(int i=0;i<cands.size();i++){if(i>0)in.append('\n');in.append(cands.get(i).toString());}
  in.append("\n\n# New evidence\n");
  for(int i=0;i<batch.size();i++){JSONObject e=batch.get(i);if(i>0)in.append("\n\n");in.append('[').append(e.getString("ref")).append("] ").append(stamp(e.getLong("t"))).append('\n').append(e.getString("show"));}
  JSONObject r=s.generate("graph",in.toString());s.addUsage("graph",r);
  s.applyGraph(new JSONObject(r.getString("final")).getJSONArray("upserts"),batch,now,gen);
 }
 /** Records whose graph nodes were dropped by a delete or correction, in time order, as they read now. */
 static List<JSONObject> redoItems(MemoryStore s,long now)throws Exception{
  JSONArray ids=s.graphRedo();List<JSONObject> out=new ArrayList<>();if(ids.length()==0)return out;List<String> gone=new ArrayList<>();
  for(int i=0;i<ids.length();i++){String id=ids.optString(i);
   try(Cursor c=s.getReadableDatabase().rawQuery("SELECT rowid,id,title,head,body,first_seen FROM memories WHERE id=? AND first_seen<=?",new String[]{id,""+now})){
    if(c.moveToFirst())out.add(new JSONObject().put("ref","e"+c.getLong(0)).put("id",c.getString(1)).put("show",Recall.evidenceText(c.getString(2),c.getString(3),c.getString(4))).put("t",c.getLong(5)));else gone.add(id);}}
  if(!gone.isEmpty())s.graphRedone(gone);
  out.sort((a,b)->Long.compare(a.optLong("t"),b.optLong("t")));return out;
 }

 /** Existing nodes that may need rewriting: people or title mentioned in the batch, plus nearest by vector. */
 static List<JSONObject> candidates(MemoryStore s,List<JSONObject> batch)throws Exception{
  SQLiteDatabase d=s.getReadableDatabase();synchronized(Recall.class){Recall.loadNodes(d);}
  List<Object[]> nv=Recall.nodeView(Long.MAX_VALUE);if(nv.isEmpty())return new ArrayList<>();
  StringBuilder text=new StringBuilder();for(JSONObject e:batch)text.append(e.getString("show")).append('\n');String all=text.toString(),me=s.userName();
  Map<String,Double> pick=new LinkedHashMap<>();
  for(Object[] n:nv){JSONObject v=(JSONObject)n[1];if(v.optString("status").equals("superseded"))continue;int hits=0;for(String p:Recall.strings(v.optJSONArray("people")))if(!p.isEmpty()&&!p.equals(me)&&all.contains(p))hits++;if(!v.optString("title").isEmpty()&&all.contains(v.optString("title")))hits++;if(hits>0)pick.put((String)n[0],(double)hits);}
  List<float[]> bv=new ArrayList<>();for(JSONObject e:batch){float[] v=s.vectorOf(e.getString("id"));if(v!=null)bv.add(v);}
  if(!bv.isEmpty()){
   List<Object[]> sim=new ArrayList<>();for(Object[] n:nv){if(n[2]==null)continue;float m=-2;for(float[] v:bv)m=Math.max(m,Embedder.dot((float[])n[2],v));sim.add(new Object[]{n[0],m});}
   sim.sort((a,b)->Float.compare((float)b[1],(float)a[1]));
   for(int i=0;i<Math.min(25,sim.size());i++)pick.merge((String)sim.get(i)[0],(double)(float)sim.get(i)[1],Double::sum);
  }
  List<String> best=new ArrayList<>(pick.keySet());best.sort((a,b)->Double.compare(pick.get(b),pick.get(a)));
  Map<String,JSONObject> latest=new HashMap<>();for(Object[] n:nv)latest.put((String)n[0],(JSONObject)n[1]);
  List<JSONObject> out=new ArrayList<>();
  for(String nid:best.subList(0,Math.min(CANDIDATES,best.size()))){JSONObject v=latest.get(nid);
   out.add(new JSONObject().put("id",nid).put("type",v.optString("type")).put("title",v.optString("title")).put("summary",v.optString("summary")).put("when",v.optString("when")).put("status",v.optString("status")).put("people",v.optJSONArray("people")));}
  return out;
 }

 static void board(MemoryStore s,long now)throws Exception{
  List<List<JSONObject>> segs=split(pending(s,"boardCursor",now),BOARD_LIMIT);
  for(int i=0;i<segs.size();i++){
   List<JSONObject> seg=segs.get(i);state=s.context.getString(R.string.organize_board,i+1,segs.size());
   long gen=s.derivedGen();JSONObject current=s.boardAt(now);
   StringBuilder in=new StringBuilder("User's name: "+s.userName()+"\nCurrent time: "+stamp(now)+".\n\n[Current board]\n"+render(current)+suppressed(s,"board")+"\n\n[New content in this period]\n");
   for(int j=0;j<seg.size();j++){JSONObject e=seg.get(j);if(j>0)in.append("\n\n");in.append('[').append(e.getString("ref")).append("] ").append(stamp(e.getLong("t"))).append('\n').append(e.getString("show"));}
   JSONObject r=s.generate("board",in.toString());s.addUsage("board",r);JSONObject next=new JSONObject(r.getString("final"));
   long through=seg.get(seg.size()-1).getLong("t");next.put("through",through);
   s.commitDerived("board",gen,now,next.getJSONArray("active").length()>0||current.getJSONArray("active").length()==0?next:null,through);
  }
 }
 /** Debug builds only: rebuild just the user card over existing history as of a given time. */
 static synchronized boolean startProfile(Context c,long asOf){
  if(running)return false;running=true;state=c.getString(R.string.organize_card_busy);
  new Thread(()->{MemoryStore s=MemoryStore.get(c);try{profile(s,asOf>0?asOf:System.currentTimeMillis());state=c.getString(R.string.organize_done);}
   catch(Exception e){state=c.getString(R.string.organize_failed,String.valueOf(e.getMessage()));android.util.Log.w("TakuMemory","Organization interrupted",e);}finally{running=false;s.changed();}},"memory-profile").start();
  return true;
 }
 static void profile(MemoryStore s,long now)throws Exception{
  List<List<JSONObject>> segs=split(pending(s,"profile2Cursor",now),BOARD_LIMIT);
  for(int i=0;i<segs.size();i++){
   List<JSONObject> seg=segs.get(i);state=s.context.getString(R.string.organize_card,i+1,segs.size());long gen=s.derivedGen();JSONObject current=s.profileAt(now);current.remove("through");
   StringBuilder in=new StringBuilder("User's name: "+s.userName()+"\nCurrent time: "+stamp(now)+".\n\n[Current user card]\n"+current+suppressed(s,"profile")+"\n\n[New content in this period]\n");
   for(int j=0;j<seg.size();j++){JSONObject e=seg.get(j);if(j>0)in.append("\n\n");in.append('[').append(e.getString("ref")).append("] ").append(stamp(e.getLong("t"))).append('\n').append(e.getString("show"));}
   JSONObject r=s.generate("profile2",in.toString());s.addUsage("profile2",r);JSONObject next=new JSONObject(r.getString("final"));
   long through=seg.get(seg.size()-1).getLong("t");next.put("through",through);
   boolean keep=next.getJSONArray("me").length()+next.getJSONArray("people").length()>0||current.optJSONArray("me").length()+current.optJSONArray("people").length()==0;
   s.commitDerived("profile",gen,now,keep?next:null,through);
  }
 }
 /** What the user deleted from the board or card before; the organizer is told not to write it back. */
 static String suppressed(MemoryStore s,String kind)throws Exception{
  JSONArray a=s.suppressed(kind);if(a.length()==0)return "";StringBuilder b=new StringBuilder("\n\n[Deleted by the user; do not restore]");
  for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);String line;
   if(x==null)line=a.optString(i);
   else switch(x.optString("kind")){case "board"->line=x.optString("name");case "me"->line="About you: "+x.optString("item");case "person"->line="Do not remember this person at all: "+x.optString("name");default->line=x.optString("name")+"："+x.optString("note");}
   b.append("\n- ").append(line);}
  return b.toString();
 }
 static String renderProfile(JSONObject p)throws Exception{
  StringBuilder out=new StringBuilder("About you: ");JSONArray me=p.optJSONArray("me");
  for(int i=0;me!=null&&i<me.length();i++){JSONObject x=me.getJSONObject(i);out.append("\n· ").append(x.optString("item")).append("(context: ").append(x.optString("applies")).append("; latest ").append(x.optString("last")).append("）");}
  out.append("\nImportant people: ");JSONArray ps=p.optJSONArray("people");
  for(int i=0;ps!=null&&i<ps.length();i++){JSONObject x=ps.getJSONObject(i);out.append("\n· ").append(x.optString("name")).append("（").append(x.optString("relation")).append("，").append(x.optString("closeness")).append("）：").append(Recall.join(x.optJSONArray("notes"),"；"));JSONArray d=x.optJSONArray("dates");if(d!=null&&d.length()>0)out.append("｜Important dates: ").append(Recall.join(d,"；"));}
  return out.toString();
 }
 static String render(JSONObject b)throws Exception{
  StringBuilder out=new StringBuilder("Ongoing: ");
  for(String part:new String[]{"active","done"}){
   if(part.equals("done"))out.append("\nRecently completed or set aside: ");
   JSONArray a=b.optJSONArray(part);for(int i=0;a!=null&&i<a.length();i++){JSONObject x=a.getJSONObject(i);out.append("\n· ").append(x.optString("name")).append("(aliases: ").append(Recall.join(x.optJSONArray("aliases")," / ")).append("）｜").append(x.optString("state")).append(part.equals("active")?"｜Latest ":"｜").append(x.optString("last"));}
  }
  return out.toString();
 }
}
