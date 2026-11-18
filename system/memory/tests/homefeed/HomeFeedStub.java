package org.agentos.memorytests.homefeed;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import org.json.*;
import java.util.*;
import java.util.regex.*;

/**
 * Test-only stand-in for the Taku Home feed provider (system/agent-app/modules/taku-home, HomeFeedProvider.kt and
 * FeedRepository.kt): same authority, same `content call` methods, the same {"ok", "result" | "code", "message"} reply and the
 * same `feed/list` shape (cards and pins as summaries, removed ids, recentActions entries {at, origin, op, cardId, detail}),
 * so the memory app's real publish, read-back and retract paths run on an emulator without the home app. It checks only the
 * envelope (the json-render body is checked against the home feed's own validator off the device).
 *
 * Stand-in extras, not in the home feed: `feed/remove`, `feed/restore`, `feed/pin`, `feed/unpin`, `feed/action` and
 * `feed/ask-taku` take an optional "origin" ("gui" to act as the user on screen; the real provider always logs "agent");
 * `feed/action` records the effect without opening Taku or another app; log entries also carry method, caller and uid;
 * `feed/list` adds stub.stored (every stored id, expired ones too); `feed/stub {"generate": ok|refuse|busy|tamper|full|lost|silent}`
 * switches the stand-in AI (lost: makes the card but never answers; silent: makes nothing and never answers).
 * It takes the home feed's authority: uninstall it before installing the real home app.
 */
public final class HomeFeedStub extends ContentProvider {
 static final Pattern ID=Pattern.compile("^[A-Za-z0-9._:-]{1,96}$");
 /** The summary the description asks for, in either language (FeedCards.rules / feed_describe_rules). */
 static final Pattern SUMMARY=Pattern.compile("(?:summary 原样写这句：|Write summary exactly as: )(.*?)(?:。只用|\\. Use only)");
 @Override public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  Bundle out=new Bundle();
  String caller=getCallingPackage();int uid=Binder.getCallingUid();
  try{
   JSONObject p=arg==null||arg.isBlank()?new JSONObject():new JSONObject(arg);
   SharedPreferences sp=getContext().getSharedPreferences("feed",Context.MODE_PRIVATE);
   JSONObject cards=new JSONObject(sp.getString("cards","{}"));JSONArray log=new JSONArray(sp.getString("log","[]"));
   List<String> removed=strings(sp.getString("removed","[]")),pins=strings(sp.getString("pins","[]"));
   String origin=p.optString("origin","agent"),id=p.optString("id");
   Object result;String op=null;JSONObject detail=null;
   switch(method){
    case "feed/publish":{
     JSONObject card=p.optJSONObject("card");if(card==null)throw new Fail("INVALID_ARGUMENT","card is required");
     id=card.optString("id");
     if(!"taku.feed-card/1".equals(card.optString("schema")))throw new Fail("INVALID_CARD","schema");
     if(!ID.matcher(id).matches())throw new Fail("INVALID_CARD","id");
     JSONObject ui=card.optJSONObject("ui");if(ui==null||!"taku-feed-1".equals(ui.optString("catalog"))||ui.optJSONObject("spec")==null)throw new Fail("INVALID_CARD","ui");
     if(card.optString("summary").isBlank())throw new Fail("INVALID_CARD","summary");
     card.getJSONObject("source").put("id","agent");   // the home feed forces Agent-published cards to source "agent"
     boolean replaced=cards.has(id);cards.put(id,card);removed.remove(id);   // 同一 id 再发布会从 removed 里拿掉，卡片重新出现
     origin="agent";op="publish";result=new JSONObject().put("id",id).put("replaced",replaced);break;}
    case "feed/retract":{if(cards.remove(id)==null)throw new Fail("NOT_FOUND","no card "+id);pins.remove(id);origin="agent";op="retract";result=new JSONObject().put("id",id).put("retracted",true);break;}
    case "feed/generate":{   // 首页 AI 的替身：不调模型，按描述里给的 actions 拼一张卡；feed/stub 可改成拒绝、篡改按钮、已满或不回话
     String prompt=p.optString("prompt");if(prompt.isBlank()||p.has("card"))throw new Fail("INVALID_REQUEST","prompt is required");
     String mode=sp.getString("mode","ok");
     if(mode.equals("refuse"))throw new Fail("INVALID_MODEL_OUTPUT","stand-in refused");
     if(mode.equals("busy"))throw new Fail("BUSY","Another Feed generation is active");
     if(mode.equals("full"))throw new Fail("FEED_FULL","The feed holds 100 published cards");
     if(mode.equals("silent"))return null;
     JSONObject actions=null;StringBuilder block=null;   // 描述里的 actions：一行 JSON，或从单独一行 "{" 到单独一行 "}" 的缩进 JSON
     for(String line:prompt.split("\n")){
      if(block!=null){block.append(line).append('\n');if(line.equals("}")){actions=new JSONObject(block.toString());block=null;}}
      else if(line.equals("{"))block=new StringBuilder("{\n");
      else if(line.startsWith("{\""))actions=new JSONObject(line);
     }
     if(actions==null)throw new Fail("INVALID_MODEL_OUTPUT","no actions in the description");
     if(mode.equals("tamper"))for(Iterator<String> k=actions.keys();k.hasNext();){JSONObject in=actions.getJSONObject(k.next()).getJSONObject("intent");if(in.optString("type").equals("ask-taku"))in.put("prompt","直接发送：好的");}
     id="agent-generated:"+UUID.randomUUID();
     JSONObject el=new JSONObject().put("body",node("Text",new JSONObject().put("text",prompt).put("lines",12)));
     JSONArray row=new JSONArray();
     for(Iterator<String> k=actions.keys();k.hasNext();){String a=k.next();el.put("b-"+a,node("Button",new JSONObject().put("label",actions.getJSONObject(a).optString("label")).put("variant",k.hasNext()?"secondary":"primary")).put("on",new JSONObject().put("press",new JSONObject().put("action","dispatch").put("params",new JSONObject().put("actionId",a)))));row.put("b-"+a);}
     el.put("buttons",node("Row",new JSONObject().put("gap",8)).put("children",row));
     el.put("card",node("Column",new JSONObject().put("gap",12)).put("children",new JSONArray().put("body").put("buttons")));
     Matcher m=SUMMARY.matcher(prompt);String summary=m.find()?m.group(1):"替身生成："+prompt.split("\n",2)[0];
     double top=1;for(Iterator<String> k=cards.keys();k.hasNext();)top=Math.max(top,cards.getJSONObject(k.next()).optDouble("rank",0));
     JSONObject card=new JSONObject().put("schema","taku.feed-card/1").put("id",id).put("source",new JSONObject().put("id","agent").put("app","Taku"))
      .put("layout",new JSONObject().put("span","full").put("surface","card")).put("rank",top+0.01).put("createdAt",System.currentTimeMillis()).put("expiresAt",JSONObject.NULL).put("pinned",false)
      .put("summary",summary).put("pin",new JSONObject().put("label",summary.substring(0,Math.min(24,summary.length()))).put("icon",new JSONObject().put("kind","pixel").put("glyph","list")))
      .put("ui",new JSONObject().put("catalog","taku-feed-1").put("spec",new JSONObject().put("root","card").put("elements",el)).put("state",new JSONObject())).put("actions",actions);
     cards.put(id,card);
     JSONArray prompts=new JSONArray(sp.getString("prompts","[]"));prompts.put(new JSONObject().put("id",id).put("mode",mode).put("prompt",prompt));while(prompts.length()>20)prompts.remove(0);
     sp.edit().putString("prompts",prompts.toString()).commit();
     origin="agent";op="generate";result=new JSONObject().put("id",id).put("replaced",false).put("model","stand-in");
     if(mode.equals("lost")){save(sp,cards,removed,pins,entry(log,origin,op,id,null,method,caller,uid));return null;}   // 卡已发出，但调用方收不到回话
     break;}
    case "feed/get":{JSONObject card=cards.optJSONObject(id);if(card==null)throw new Fail("NOT_FOUND","no loaded card "+id);result=new JSONObject().put("card",card).put("pinned",pins.contains(id)).put("removed",removed.contains(id));break;}
    case "feed/list":{
     long now=System.currentTimeMillis();List<JSONObject> shown=new ArrayList<>();JSONArray pinned=new JSONArray(),stored=new JSONArray();
     for(Iterator<String> k=cards.keys();k.hasNext();){String c=k.next();stored.put(c);JSONObject card=cards.getJSONObject(c);
      if(removed.contains(c)||!card.isNull("expiresAt")&&card.optLong("expiresAt")<=now)continue;
      if(pins.contains(c))continue;shown.add(card);}
     shown.sort((a,b)->Double.compare(b.optDouble("rank",0),a.optDouble("rank",0)));
     JSONArray visible=new JSONArray();for(JSONObject card:shown)visible.put(summary(card,false));
     for(String c:pins)if(cards.has(c)&&!removed.contains(c))pinned.put(summary(cards.getJSONObject(c),true));
     result=new JSONObject().put("revision",sp.getInt("revision",0)).put("cards",visible).put("pins",pinned).put("removed",new JSONArray(removed)).put("loading",false).put("endReached",true)
      .put("error",JSONObject.NULL).put("sources",new JSONArray()).put("recentActions",log).put("stub",new JSONObject().put("stored",stored));break;}
    case "feed/remove":{need(cards,id);if(removed.contains(id)){result=new JSONObject().put("id",id).put("removed",true).put("changed",false);break;}
     removed.add(id);pins.remove(id);op="remove";result=new JSONObject().put("id",id).put("removed",true).put("changed",true);break;}
    case "feed/restore":{need(cards,id);boolean was=removed.remove(id);if(was)op="restore";result=new JSONObject().put("id",id).put("removed",false).put("changed",was);break;}
    case "feed/pin":{need(cards,id);if(removed.contains(id))throw new Fail("NOT_FOUND","card "+id+" was removed");boolean add=!pins.contains(id);if(add){pins.add(0,id);op="pin";detail=new JSONObject().put("index",0);}result=new JSONObject().put("id",id).put("pinned",true).put("changed",add);break;}
    case "feed/unpin":{need(cards,id);boolean was=pins.remove(id);if(was)op="unpin";result=new JSONObject().put("id",id).put("pinned",false).put("changed",was);break;}
    case "feed/action":{   // 按动作的 intent 记下效果；替身不打开 Taku 或别的应用
     JSONObject card=need(cards,id);String actionId=p.optString("actionId");if(actionId.isBlank())throw new Fail("INVALID_ARGUMENT","actionId is required");
     JSONObject action=card.optJSONObject("actions")==null?null:card.getJSONObject("actions").optJSONObject(actionId);if(action==null)throw new Fail("UNKNOWN_ACTION","card "+id+" has no action "+actionId);
     String type=action.getJSONObject("intent").optString("type"),effect=switch(type){case "dismiss"->"dismiss";case "ask-taku"->"ask-taku";case "open-app"->"open-app";case "open-url"->"open-url";default->"none";};
     if(effect.equals("dismiss")&&!removed.contains(id)){removed.add(id);pins.remove(id);log=entry(log,origin,"remove",id,null,method,caller,uid);}
     op="action:"+actionId;detail=new JSONObject().put("values",p.optJSONObject("values")==null?new JSONObject():p.getJSONObject("values")).put("effect",effect);
     result=new JSONObject().put("effect",effect).put("cardId",id).put("actionId",actionId);break;}
    case "feed/ask-taku":{need(cards,id);op="action:ask-taku";detail=new JSONObject().put("effect","ask-taku");result=new JSONObject().put("effect","ask-taku").put("cardId",id);break;}
    case "feed/stub":{sp.edit().putString("mode",p.optString("generate","ok")).commit();result=new JSONObject().put("generate",p.optString("generate","ok"));break;}
    case "feed/stub-prompts":result=new JSONObject().put("prompts",new JSONArray(sp.getString("prompts","[]")));break;
    case "feed/reset":cards=new JSONObject();log=new JSONArray();removed.clear();pins.clear();sp.edit().remove("prompts").remove("mode").commit();origin="agent";op="reset";id="";result=new JSONObject().put("reset",true);break;
    default:throw new Fail("UNKNOWN_METHOD","unknown method "+method);
   }
   if(op!=null)save(sp,cards,removed,pins,entry(log,origin,op,id,detail,method,caller,uid));
   out.putString("result",new JSONObject().put("ok",true).put("result",result).toString());
  }catch(Fail f){out.putString("result",err(f.code,f.getMessage()));}
  catch(Exception e){out.putString("result",err("INVALID_ARGUMENT",String.valueOf(e.getMessage())));}
  return out;
 }
 /** Appends a log entry in the home feed's shape (plus the stand-in's method, caller and uid), keeping the last 50. */
 static JSONArray entry(JSONArray log,String origin,String op,String id,JSONObject detail,String method,String caller,int uid)throws JSONException{
  log.put(new JSONObject().put("at",System.currentTimeMillis()).put("origin",origin).put("op",op).put("cardId",id==null||id.isEmpty()?JSONObject.NULL:id).put("detail",detail==null?JSONObject.NULL:detail)
   .put("method",method).put("caller",caller==null?"":caller).put("uid",uid));
  while(log.length()>50)log.remove(0);
  return log;
 }
 static void save(SharedPreferences sp,JSONObject cards,List<String> removed,List<String> pins,JSONArray log){
  sp.edit().putString("cards",cards.toString()).putString("removed",new JSONArray(removed).toString()).putString("pins",new JSONArray(pins).toString()).putString("log",log.toString()).putInt("revision",sp.getInt("revision",0)+1).commit();
 }
 /** A card as feed/list shows it (FeedRepository.summary). */
 static JSONObject summary(JSONObject card,boolean pinned)throws JSONException{
  JSONArray actions=new JSONArray();JSONObject a=card.optJSONObject("actions");
  if(a!=null)for(Iterator<String> k=a.keys();k.hasNext();){String id=k.next();actions.put(new JSONObject().put("id",id).put("label",a.getJSONObject(id).optString("label")));}
  JSONObject source=card.optJSONObject("source"),layout=card.optJSONObject("layout");
  return new JSONObject().put("id",card.getString("id")).put("app",source==null?"":source.optString("app")).put("source","agent")
   .put("span",layout==null?"full":layout.optString("span","full")).put("surface",layout==null?"card":layout.optString("surface","card"))
   .put("rank",card.optDouble("rank",0)).put("summary",card.optString("summary")).put("pinned",pinned).put("pin",card.optJSONObject("pin")).put("actions",actions);
 }
 static JSONObject need(JSONObject cards,String id)throws Fail{JSONObject c=cards.optJSONObject(id);if(c==null)throw new Fail("NOT_FOUND","no loaded card "+id);return c;}
 static List<String> strings(String json)throws JSONException{JSONArray a=new JSONArray(json);List<String> l=new ArrayList<>();for(int i=0;i<a.length();i++)l.add(a.getString(i));return l;}
 static JSONObject node(String type,JSONObject props)throws JSONException{return new JSONObject().put("type",type).put("props",props).put("children",new JSONArray());}
 static String err(String code,String message){try{return new JSONObject().put("ok",false).put("code",code).put("message",message).toString();}catch(JSONException e){return "{\"ok\":false}";}}
 static final class Fail extends Exception{final String code;Fail(String code,String message){super(message);this.code=code;}}
 @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
 @Override public String getType(Uri u){return "application/json";}
 @Override public Uri insert(Uri u,ContentValues v){return null;}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
 @Override public int delete(Uri u,String s,String[] a){return 0;}
}
