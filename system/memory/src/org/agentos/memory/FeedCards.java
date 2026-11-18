package org.agentos.memory;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import android.os.ParcelFileDescriptor;
import java.time.*;
import java.util.*;

/**
 * Proactive reminders as Taku Home feed cards: `taku.feed-card/1` with a `taku-feed-1` json-render body, published through
 * `content://org.agentos.agent.home-feed` (system/agent-app/modules/taku-home/CONTRACT.md). Cards are built from what the
 * reminder already says; nothing is rewritten by another model. Ids are unique per reminder because a card the user removed
 * stays removed under its id.
 */
final class FeedCards {
 static final String AUTHORITY="org.agentos.agent.home-feed",SCHEMA="taku.feed-card/1",CATALOG="taku-feed-1";
 static final String ICON="asset:feed/icons/sparkle-8.svg";
 /**
  * Ranks above every design sample on the home feed (samples use 0.70–0.99 and repeat lower down), so a reminder is not
  * interleaved with them; a clash first, then a message, then the bedtime brief. Cards the home feed's AI makes are placed
  * above the highest loaded card by the home feed itself.
  */
 static final double RANK_BRIEF=1.1,RANK_MESSAGE=1.2,RANK_CLASH=1.3;
 /**
  * The home feed puts equal ranks in publishing order, oldest first, so a fixed rank would leave today's reminder under
  * yesterday's. A small share of the time keeps the newest of a kind on top: about 0.018 now, growing 0.0003 a year, far
  * inside the 0.1 between kinds.
  */
 static double rank(double kind,long now){return kind+now*1e-14;}
 /** Components and props this class emits; StoreTests checks every card against it. */
 static final Set<String> TYPES=Set.of("Column","Row","AppHeader","Inset","Text","Tags","Pager","Dots","Button");

 static boolean available(Context c){try{return c.getPackageManager().resolveContentProvider(AUTHORITY,0)!=null;}catch(RuntimeException e){return false;}}

 /** Bedtime brief: what tomorrow needs, three items a page (a feed card shows one line per checklist row, too short for these sentences). */
 static JSONObject evening(Context c,String id,String title,List<String> items,long now)throws Exception{
  List<String> shown=briefItems(items);
  String heading=c.getString(R.string.nudge_evening_title);
  JSONObject header=new JSONObject().put("icon",ICON).put("title",heading);
  if(title!=null&&!title.isBlank())header.put("trailing",cut(title,16));else header.put("count",""+shown.size());
  int pages=(shown.size()+2)/3;
  JSONObject el=new JSONObject().put("header",node("AppHeader",header))
   .put("buttons",node("Row",new JSONObject().put("gap",8),"hide","plan"))
   .put("hide",button(c.getString(R.string.feed_hide),"secondary",false,"hide"))
   .put("plan",button(c.getString(R.string.feed_plan),"primary",true,"plan"));
  String[] pageIds=new String[pages];
  for(int p=0;p<pages;p++){
   List<String> rows=new ArrayList<>();
   for(int i=p*3;i<Math.min(shown.size(),p*3+3);i++){el.put("item"+i,node("Text",new JSONObject().put("text",cut(shown.get(i),90)).put("variant","body-md").put("tone","primary").put("lines",2)));rows.add("item"+i);}
   pageIds[p]="page"+p;el.put(pageIds[p],node("Column",new JSONObject().put("gap",10),rows.toArray(new String[0])));
  }
  List<String> kids=new ArrayList<>(List.of("header"));JSONObject state=new JSONObject();
  if(pages==1)kids.add("page0");
  else{el.put("pager",node("Pager",new JSONObject().put("index",new JSONObject().put("$bindState","/ui/page")),pageIds))
    .put("dots",node("Dots",new JSONObject().put("count",pages).put("index",new JSONObject().put("$state","/ui/page"))));
   kids.add("pager");kids.add("dots");state.put("ui",new JSONObject().put("page",0));}
  kids.add("buttons");el.put("card",node("Column",new JSONObject().put("gap",12),kids.toArray(new String[0])));
  JSONObject actions=eveningActions(c,shown);
  String summary=briefSummary(c,title,shown);
  return card(c,id,rank(RANK_BRIEF,now),now,expiresTomorrow(now),summary,new JSONObject().put("label",cut(heading,24)).put("detail",cut(shown.get(0),32)).put("icon",new JSONObject().put("kind","pixel").put("glyph","moon")),el,state,actions);
 }

 /** A new message touched something remembered: the message, what Taku remembers about it, and a reply or hand-off. */
 static JSONObject message(Context c,String id,String sender,String message,String say,boolean conflict,String pkg,long now)throws Exception{
  String who=who(c,sender);
  JSONObject actions=messageActions(c,who,message,say,conflict,pkg);
  boolean open=actions.has("open");String primary=actions.getJSONObject("go").getString("label");
  JSONObject el=new JSONObject()
   .put("header",node("AppHeader",new JSONObject().put("icon",ICON).put("title",c.getString(R.string.app_name)).put("trailing",cut(c.getString(R.string.nudge_message_title,who),16))))
   .put("quote",node("Inset",new JSONObject(),"quoted"))
   .put("quoted",node("Column",new JSONObject().put("gap",4).put("padding",12),"from","incoming"))
   .put("from",node("Text",new JSONObject().put("text",cut(c.getString(R.string.feed_from,who),24)).put("variant","body-sm-medium").put("tone","tertiary")))
   .put("incoming",node("Text",new JSONObject().put("text",cut(message==null?"":message.trim(),200)).put("tone","secondary").put("lines",4).put("variant","body-md")))
   .put("say",node("Text",new JSONObject().put("text",cut(say.trim(),160)).put("variant","body-lg-medium").put("tone","primary")))
   .put("buttons",node("Row",new JSONObject().put("gap",8),open?"open":"hide","go"))
   .put("go",button(primary,"primary",true,"go"));
  List<String> kids=new ArrayList<>(List.of("header","quote","say"));
  if(conflict){el.put("tags",node("Tags",new JSONObject().put("items",new JSONArray().put(cut(c.getString(R.string.feed_conflict),24)))));kids.add("tags");}
  kids.add("buttons");el.put("card",node("Column",new JSONObject().put("gap",12),kids.toArray(new String[0])));
  if(open)el.put("open",button(c.getString(R.string.feed_open),"secondary",false,"open"));
  else el.put("hide",button(c.getString(R.string.feed_hide),"secondary",false,"hide"));
  return card(c,id,rank(conflict?RANK_CLASH:RANK_MESSAGE,now),now,now+24L*3600000L,say.trim(),new JSONObject().put("label",cut(who,24)).put("detail",cut(say.trim(),32)).put("icon",new JSONObject().put("kind","pixel").put("glyph","chat")),
   el,new JSONObject(),actions);
 }

 static List<String> briefItems(List<String> items){
  List<String> shown=new ArrayList<>();for(String x:items)if(x!=null&&!x.isBlank()&&shown.size()<9)shown.add(x.trim());
  if(shown.isEmpty())throw new IllegalArgumentException("empty brief");
  return shown;
 }
 static String who(Context c,String sender){return sender==null||sender.isBlank()?c.getString(R.string.nudge_kind_message):sender.trim();}
 /** Buttons of a bedtime brief, in display order: hide, then hand the items to Taku to plan. */
 static JSONObject eveningActions(Context c,List<String> shown)throws Exception{
  StringBuilder all=new StringBuilder();for(String x:shown)all.append("\n· ").append(x);
  return new JSONObject()
   .put("hide",action(c.getString(R.string.feed_hide),new JSONObject().put("type","dismiss")))
   .put("plan",action(c.getString(R.string.feed_plan),new JSONObject().put("type","ask-taku").put("prompt",c.getString(R.string.feed_plan_prompt)+all)));
 }
 /** Buttons of a message reminder, in display order: open the source app (or hide), then draft a reply or ask Taku. */
 static JSONObject messageActions(Context c,String who,String message,String say,boolean conflict,String pkg)throws Exception{
  boolean reply=conflict||Proactive.REQUEST.matcher(message==null?"":message).find();
  String primary=c.getString(reply?R.string.feed_reply:R.string.feed_ask);
  boolean open=pkg!=null&&!pkg.isBlank()&&!pkg.equals(c.getPackageName())&&pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
  String m=cut(message==null?"":message.trim(),300);
  String prompt=c.getString(reply?R.string.feed_reply_prompt:R.string.feed_ask_prompt,who,m,say.trim());
  JSONObject actions=new JSONObject();
  if(open)actions.put("open",action(c.getString(R.string.feed_open),new JSONObject().put("type","open-app").put("package",pkg)));
  else actions.put("hide",action(c.getString(R.string.feed_hide),new JSONObject().put("type","dismiss")));
  return actions.put("go",action(primary,new JSONObject().put("type","ask-taku").put("prompt",prompt)));
 }

 /** A description for the home feed's AI (`feed/generate`), with what its card must keep (see {@link FeedCheck}). */
 static final class Ask{
  final String prompt;final JSONObject actions;final List<String> texts;final long expires;
  Ask(String prompt,JSONObject actions,List<String> texts,long expires){this.prompt=prompt;this.actions=actions;this.texts=texts;this.expires=expires;}
  JSONObject json()throws JSONException{return new JSONObject().put("prompt",prompt).put("actions",actions).put("texts",new JSONArray(texts)).put("expiresAt",expires);}
 }
 static Ask describeEvening(Context c,String title,List<String> items,long now)throws Exception{
  List<String> shown=briefItems(items);
  String heading=c.getString(R.string.nudge_evening_title);
  StringBuilder list=new StringBuilder();for(String x:shown)list.append("· ").append(x).append('\n');   // Do not number these: numbering would be copied into the card.
  String head=title==null||title.isBlank()?c.getString(R.string.feed_describe_head,heading):c.getString(R.string.feed_describe_head_titled,heading,cut(title,16));
  JSONObject actions=eveningActions(c,shown);
  String prompt=c.getString(R.string.feed_describe_evening,head,shown.size(),list.toString().trim())+"\n\n"+rules(c,R.string.feed_describe_tree_brief,actions,briefSummary(c,title,shown));
  return new Ask(prompt,actions,shown,expiresTomorrow(now));
 }
 static Ask describeMessage(Context c,String sender,String message,String say,boolean conflict,String pkg,long now)throws Exception{
  String who=who(c,sender),m=cut(message==null?"":message.trim(),200),said=cut(say.trim(),160);
  String head=c.getString(R.string.feed_describe_head_titled,c.getString(R.string.app_name),cut(c.getString(R.string.nudge_message_title,who),16));
  JSONObject actions=messageActions(c,who,message,say,conflict,pkg);
  StringBuilder p=new StringBuilder(c.getString(R.string.feed_describe_message,who,head,cut(c.getString(R.string.feed_from,who),24),m,said));
  if(conflict)p.append('\n').append(c.getString(R.string.feed_describe_conflict,cut(c.getString(R.string.feed_conflict),24)));
  p.append("\n\n").append(rules(c,R.string.feed_describe_tree_message,actions,said));
  List<String> texts=new ArrayList<>();if(!m.isEmpty())texts.add(m);texts.add(said);
  return new Ask(p.toString(),actions,texts,now+24L*3600000L);
 }
 /**
  * What every description shares: the element tree, the buttons, the actions to copy (indented, so the model closes the
  * object where it ends) and the summary to use as written (the home feed takes its first 24 characters as the pin).
  */
 static String rules(Context c,int tree,JSONObject actions,String summary)throws JSONException{
  String s=summary.trim();while(s.endsWith("。")||s.endsWith("."))s=s.substring(0,s.length()-1);
  // Android escapes slashes in JSON; models may double-escape them when copying, adding a stray backslash to Taku's prompt.
  return c.getString(R.string.feed_describe_rules,c.getString(tree),actions.toString(1).replace("\\/","/"),cut(s,200));
 }
 /** Title and first item, the same summary as the fixed-layout brief card. */
 static String briefSummary(Context c,String title,List<String> shown){
  return (title==null||title.isBlank()?"":title.trim()+"：")+shown.get(0)+(shown.size()>1?c.getString(R.string.feed_more,shown.size()-1):"");
 }
 static long expiresTomorrow(long now){
  LocalDate tomorrow=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1);
  return tomorrow.atTime(23,59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
 }

 static JSONObject card(Context c,String id,double rank,long now,long expires,String summary,JSONObject pin,JSONObject elements,JSONObject state,JSONObject actions)throws Exception{
  return new JSONObject().put("schema",SCHEMA).put("id",id)
   .put("source",new JSONObject().put("id","agent").put("app",c.getString(R.string.app_name)).put("package",c.getPackageName()).put("icon",ICON))
   .put("layout",new JSONObject().put("span","full").put("surface","card")).put("rank",rank).put("createdAt",now).put("expiresAt",expires).put("pinned",false)
   .put("summary",cut(summary,200)).put("pin",pin)
   .put("ui",new JSONObject().put("catalog",CATALOG).put("spec",new JSONObject().put("root","card").put("elements",elements)).put("state",state))
   .put("actions",actions);
 }
 static JSONObject node(String type,JSONObject props,String... children){JSONArray k=new JSONArray();for(String x:children)k.put(x);try{return new JSONObject().put("type",type).put("props",props).put("children",k);}catch(JSONException e){throw new IllegalStateException(e);}}
 static JSONObject button(String label,String variant,boolean sparkle,String actionId)throws Exception{
  JSONObject p=new JSONObject().put("label",cut(label,32)).put("variant",variant).put("flex",1);if(sparkle)p.put("icon","sparkle");
  return node("Button",p).put("on",new JSONObject().put("press",dispatch(actionId)));
 }
 static JSONObject dispatch(String actionId,String... select)throws Exception{
  JSONObject params=new JSONObject().put("actionId",actionId);if(select.length>0)params.put("select",new JSONArray(Arrays.asList(select)));
  return new JSONObject().put("action","dispatch").put("params",params);
 }
 static JSONObject action(String label,JSONObject intent)throws Exception{return new JSONObject().put("label",cut(label,32)).put("intent",intent);}
 /**
  * Collapses whitespace and keeps at most n UTF-16 units, ending in "…" when cut. The home feed counts its limits (pin label
  * 24, detail 32, button label 32, tag 24) in UTF-16 units, where an emoji takes two; a cut never splits one.
  */
 static String cut(String s,int n){
  s=s==null?"":s.replaceAll("\\s+"," ").trim();if(s.length()<=n)return s;
  int end=n-1;if(end>0&&Character.isHighSurrogate(s.charAt(end-1)))end--;
  return s.substring(0,end)+"…";
 }

 /** Publishes or replaces a card; returns the provider result. Throws when the home feed is not installed or rejects the card. */
 static JSONObject publish(Context c,JSONObject card)throws Exception{return call(c,"feed/publish",new JSONObject().put("card",card));}
 static JSONObject retract(Context c,String id)throws Exception{return call(c,"feed/retract",new JSONObject().put("id",id));}
 /** Asks the home feed's AI for a new card from a description; blocks up to two minutes and must run off the main thread. Returns {id, model}. */
 static JSONObject generate(Context c,String prompt)throws Exception{return call(c,"feed/generate",new JSONObject().put("prompt",prompt));}
 static JSONObject get(Context c,String id)throws Exception{return call(c,"feed/get",new JSONObject().put("id",id)).getJSONObject("card");}
 /**
  * The home feed's own view: visible cards and pins as summaries ({id, summary, actions:[{id,label}], …}), the ids the user
  * removed, and eventCursor. Feedback uses the durable /events stream, not a truncated snapshot log.
  */
 static JSONObject list(Context c)throws Exception{return call(c,"feed/list",new JSONObject());}
 /** Finite durable replay. A checkpoint is acknowledged only after every event is consumed successfully. */
 interface EventConsumer { void accept(JSONObject event)throws Exception; }
 static String events(Context c,String after,EventConsumer consume)throws Exception{
  Uri uri=Uri.parse("content://"+AUTHORITY+"/events").buildUpon()
   .appendQueryParameter("after",after).appendQueryParameter("follow","0").build();
  try(ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"r")){
   if(fd==null)throw new IOException("home feed event stream did not open");
   try(BufferedReader input=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(fd),StandardCharsets.UTF_8))){
    String line=input.readLine();if(line==null)throw new IOException("missing Feed ready frame");
    JSONObject ready=new JSONObject(line);
    if(!ready.optString("type").equals("ready")||!ready.optString("schema").equals("taku.feed-event-stream/1")||ready.optBoolean("follow"))throw new IOException("invalid Feed ready frame");
    String through=ready.getString("through"),checkpoint=null;
    while((line=input.readLine())!=null){
     JSONObject frame=new JSONObject(line);String type=frame.optString("type");
     if(type.equals("event")&&checkpoint==null)consume.accept(frame);
     else if(type.equals("checkpoint")&&checkpoint==null&&through.equals(frame.optString("through")))checkpoint=through;
     else throw new IOException("invalid Feed event frame");
    }
    fd.checkError();
    if(checkpoint==null)throw new IOException("Feed replay disconnected before checkpoint");
    return checkpoint;
   }
  }
 }
 static JSONObject call(Context c,String method,JSONObject arg)throws Exception{
  Bundle b;try{b=c.getContentResolver().call(Uri.parse("content://"+AUTHORITY),method,arg.toString(),null);}catch(IllegalArgumentException missing){throw new IOException("home feed not installed");}
  if(b==null)throw new IOException("home feed did not answer");
  JSONObject r=new JSONObject(b.getString("result","{}"));
  if(!r.optBoolean("ok"))throw new Refused(r.optString("code","ERROR"),r.optString("message"));
  return r.optJSONObject("result")==null?new JSONObject():r.getJSONObject("result");
 }
 /** Refusal codes meaning the card is not there (any more) or is not one an agent published. */
 static boolean gone(Refused r){return r.code.equals("NOT_FOUND")||r.code.equals("NOT_AGENT_CARD");}
 /** The home feed answered with an error code; it did not add or change a card. */
 static final class Refused extends IOException{final String code;Refused(String code,String message){super(code+": "+message);this.code=code;}}
}
