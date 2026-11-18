package org.agentos.memory;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.*;

/** Versioned signature-protected API. No observation-injection endpoint. */
public final class MemoryProvider extends ContentProvider {
 public boolean onCreate(){MemoryMaintenance.schedule(getContext());AnalysisJob.schedule(getContext());return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  try{
   MemoryStore s=MemoryStore.get(getContext());JSONObject p=arg==null||arg.isEmpty()?new JSONObject():new JSONObject(arg),r;
   boolean own=Binder.getCallingUid()==android.os.Process.myUid(),root=Binder.getCallingUid()==0;
   if(!own&&!root)getContext().enforceCallingPermission("org.agentos.memory.ACCESS_MEMORY","Memory access denied");
   switch(method){
    case "status":r=s.status();break;
    case "search":case "get":case "board":case "profile":case "agenda":case "nudges":
     r=s.query(method,p,!own,own||root);break;
    default:
     if(!own&&!root)throw new SecurityException("Memory management is limited to this app");
     switch(method){
      case "batch":if(!root)throw new SecurityException("Only the runtime may read reserved analysis batches");r=s.batchEvidence(p.getString("token"),p.getLong("generation"),p.getString("model"));break;
      case "evidence":if(!root)throw new SecurityException("Only the runtime may read current captured evidence");r=s.read(p.getString("id"),false);break;
      case "configure":if(p.has("testNow")&&!root)throw new SecurityException("Test time is available only for development diagnostics");r=s.configure(p);break;
      case "systemChanged":if(!root)throw new SecurityException("Only system AI may notify configuration changes");s.systemChanged();r=s.config();break;
      case "buildProfile":if(!BuildFlags.DEBUG||!root)throw new SecurityException("Available only in debug builds");r=new JSONObject().put("started",Organizer.startProfile(getContext(),p.optLong("now",0)));break;
      case "proactive":{   // Replay evaluation: evaluate a bedtime brief or message trigger at a given time, optionally calling the model; debug builds and root only.
       if(!BuildFlags.DEBUG||!root)throw new SecurityException("Available only in debug builds");long at=p.optLong("now",s.now());
       r=p.optString("kind").equals("evening")?Proactive.evening(s,at,p.optBoolean("run",false)):Proactive.message(s,p.optString("title",""),p.getString("text"),at,p.optBoolean("run",false));
       if(p.optBoolean("deliver",false)){JSONObject o=r.optJSONObject("result");if(o!=null&&(o.optBoolean("say")||o.optBoolean("speak"))&&(r.optJSONObject("check")==null||!r.getJSONObject("check").optBoolean("mistake")))ProactiveNotice.deliver(getContext(),s,p.optString("kind").equals("evening")?"evening":"message",p.optString("kind").equals("evening")?o.optString("title"):p.optString("title",""),p.optString("kind").equals("evening")?String.join("\n",Proactive.strings(o.optJSONArray("items"))):o.optString("text"),r.optString("anchor"),r.optString("input"),at,new JSONObject().put("message",p.optString("text")).put("conflict",r.optBoolean("conflict")).put("package",p.optString("package")));}
       if(p.optBoolean("card",false)){JSONObject o=r.optJSONObject("result");   // Replay evaluation: include the Home card this result would produce, without publishing it.
        if(o!=null&&o.optBoolean("say"))r.put("card",FeedCards.evening(getContext(),"memory.brief.preview",o.optString("title"),Proactive.strings(o.optJSONArray("items")),at));
        else if(o!=null&&o.optBoolean("speak")&&!o.optString("text").isBlank())r.put("card",FeedCards.message(getContext(),"memory.nudge.preview",p.optString("title",""),p.optString("text"),o.getString("text"),r.optBoolean("conflict"),p.optString("package"),at));}
       break;}
      case "deliverNudge":{   // Debug: publish supplied reminder content through the real notification/Home paths without a model call, for end-to-end card checks.
       if(!BuildFlags.DEBUG||!root)throw new SecurityException("Available only in debug builds");long at=p.optLong("now",s.now());String kind=p.optString("kind").equals("evening")?"evening":"message";
       long id=ProactiveNotice.deliver(getContext(),s,kind,p.optString("title"),kind.equals("evening")?String.join("\n",Proactive.strings(p.optJSONArray("items"))):p.getString("say"),"","",at,new JSONObject().put("message",p.optString("text")).put("conflict",p.optBoolean("conflict")).put("package",p.optString("package")));
       r=new JSONObject().put("id",id).put("nudge",s.nudge(id));break;}
      case "feedTidy":{   // Debug: run nightly cleanup immediately on the Home-card thread: read reactions and retract expired/deleted cards; withdraw retracts all.
       if(!BuildFlags.DEBUG||!root)throw new SecurityException("Available only in debug builds");long at=p.optLong("now",s.now());boolean all=p.optBoolean("withdraw");
       JSONObject out=ProactiveNotice.FEED.submit(()->{JSONObject o=new JSONObject();if(all)o.put("withdrawn",ProactiveNotice.withdraw(getContext(),s));else o.put("read",ProactiveNotice.readBack(getContext(),s,at)).put("swept",ProactiveNotice.sweep(getContext(),s,at));return o.put("feedExpiring",ProactiveNotice.expiringCards(s));}).get(150,java.util.concurrent.TimeUnit.SECONDS);
       r=out;break;}
      case "feedPreview":{   // Debug: construct a Home card or its AI description from supplied reminder content, without model calls/publication, to compare Home validation.
       if(!BuildFlags.DEBUG||!root)throw new SecurityException("Available only in debug builds");long at=p.optLong("now",s.now());boolean evening=p.optString("kind").equals("evening");
       if(p.optBoolean("describe")){r=(evening?FeedCards.describeEvening(getContext(),p.optString("title"),Proactive.strings(p.optJSONArray("items")),at)
        :FeedCards.describeMessage(getContext(),p.optString("title"),p.optString("text"),p.getString("say"),p.optBoolean("conflict"),p.optString("package"),at)).json();break;}
       r=new JSONObject().put("card",evening?FeedCards.evening(getContext(),p.optString("id","memory.brief.preview"),p.optString("title"),Proactive.strings(p.optJSONArray("items")),at)
        :FeedCards.message(getContext(),p.optString("id","memory.nudge.preview"),p.optString("title"),p.optString("text"),p.getString("say"),p.optBoolean("conflict"),p.optString("package"),at));break;}
      case "proactiveReplay":{   // Replay evaluation: process each record chronologically as "Just received" through local trigger checks, without models, writing lines to an app-private file.
       if(!BuildFlags.DEBUG||!root)throw new SecurityException("Available only in debug builds");
       java.io.File f=new java.io.File(getContext().getFilesDir(),"proactive-replay.jsonl");if(p.optInt("offset")==0)f.delete();int n=0,w=0;
       try(java.io.Writer out=new java.io.OutputStreamWriter(new java.io.FileOutputStream(f,true),java.nio.charset.StandardCharsets.UTF_8);android.database.Cursor c=s.getReadableDatabase().rawQuery("SELECT rowid,first_seen,page,title,fresh,body FROM memories ORDER BY first_seen LIMIT ? OFFSET ?",new String[]{""+p.optInt("limit",200),""+p.optInt("offset")})){
        while(c.moveToNext()){String text=c.getString(2).equals("notification")?c.getString(5):c.getString(4);if(text==null||text.trim().length()<6)continue;
         JSONObject x=Proactive.message(s,c.getString(3),text.length()>400?text.substring(0,400):text,c.getLong(1),false);n++;if(x.optBoolean("wake"))w++;
         out.write(x.put("row",c.getLong(0)).put("at",c.getLong(1)).put("title",c.getString(3)).put("text",text.length()>400?text.substring(0,400):text).toString());out.write("\n");}
       }
       r=new JSONObject().put("checked",n).put("woke",w);break;}
      case "nudge":if(!root)throw new SecurityException("Only TaskBroker may read reminders");r=s.nudge(p.getLong("id"));break;
      case "nudgeTask":if(!root)throw new SecurityException("Only TaskBroker may record reminder tasks");s.nudgeTask(p.getLong("id"),p.getString("taskId"));r=new JSONObject();break;
      case "maintain":if(p.has("now")&&(!BuildFlags.DEBUG||!root))throw new SecurityException("A specified organization time is available only in debug builds");r=new JSONObject().put("started",Organizer.start(getContext(),p.optLong("now",0),BuildFlags.DEBUG&&root&&p.optBoolean("graphOnly")));break;
      case "clearDerived":s.clearDerived();r=new JSONObject().put("cleared",true);break;
      case "deleteBoardItem":s.deleteBoardItem(p.getString("part"),p.getString("name"));r=new JSONObject();break;
      case "deleteProfileItem":s.deleteProfileItem(p.getString("kind"),p.getString("item"),p.optString("note",""));r=new JSONObject();break;
      case "genInput":if(!root)throw new SecurityException("Only the runtime may read organization batches");r=s.jobInput(p.getString("token"));break;
      case "genResult":if(!root)throw new SecurityException("Only the runtime may return organization results");s.jobResult(p.getString("token"),p.getJSONObject("result"));r=new JSONObject();break;
      case "update":r=s.update(p.getString("id"),p.getInt("revision"),p.getString("title"),p.getString("body"));break;
      case "forget":s.forget(p.getString("id"));r=new JSONObject().put("forgotten",true);break;
      case "reserve":if(!root)throw new SecurityException("Only TaskBroker may reserve tasks");r=s.reserve(p.getString("id"),p.getInt("revision"),p.getString("taskId"));break;
      case "task":if(!root)throw new SecurityException("Only TaskBroker may update execution receipts");s.task(p.getString("id"),p.getString("taskId"),p.getString("state"));r=new JSONObject();break;
      default:throw new IllegalArgumentException("Unknown memory method");
     }
   }
   Bundle b=new Bundle();b.putString("result",new JSONObject().put("ok",true).put("result",r).toString());return b;
  }catch(Exception e){Bundle b=new Bundle();try{b.putString("result",new JSONObject().put("ok",false).put("message",e instanceof SecurityException?"Memory access is not authorized":e.getMessage()==null?"Memory request failed":e.getMessage()).toString());}catch(Exception impossible){throw new IllegalStateException(impossible);}return b;}
 }
 public Cursor query(Uri u,String[] a,String b,String[] c,String d){throw new UnsupportedOperationException("Use versioned calls");}
 public String getType(Uri u){return "application/vnd.taku.memory+json";}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int delete(Uri u,String w,String[] a){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String w,String[] a){throw new UnsupportedOperationException();}
}
