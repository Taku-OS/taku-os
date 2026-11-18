package org.agentos.memory;

import org.json.*;
import java.util.*;

/** Durable Feed selections and mutations, deduplicated by cursor; legacy snapshots remain readable during upgrades. */
final class FeedFeedback {
 /** Events kept per reminder. */
 static final int KEEP=20;
 /** Operations on a card that come from the user (or an agent acting for them), not from the card's publisher. */
 static final Set<String> OPS=Set.of("remove","restore","pin","unpin");
 /** Reactions, in the order they are shown. */
 static final List<String> REACTIONS=List.of("selected","asked","opened","pinned","removed");

 /**
  * Merges what the home feed reports about one card into what was recorded before. Each event is {at, op, origin, effect?}:
  * op as the home feed logs it (`remove`, `pin`, `action:<actionId>`, …), origin `gui` (on screen) or `agent`, effect what an
  * action did (`ask-taku`, `open-app`, `dismiss`). Legacy providers without a durable stream still support a removed-state fallback,
  * recorded as a `remove` with an empty origin at the time memory noticed. Accepted actions retain intentType
  * and phase; they do not claim the external side effect completed. Returns null when nothing is new.
  */
 static JSONArray merge(JSONArray known,JSONObject snapshot,String cardId,long now)throws JSONException{
  List<JSONObject> out=new ArrayList<>();Set<String> seen=new HashSet<>();
  if(known!=null)for(int i=0;i<known.length();i++){JSONObject e=known.optJSONObject(i);if(e!=null&&seen.add(key(e)))out.add(e);}
  int before=out.size();
  JSONArray log=snapshot.optJSONArray("events");if(log==null)log=snapshot.optJSONArray("recentActions");
  if(log!=null)for(int i=0;i<log.length();i++){
   JSONObject a=log.optJSONObject(i);if(a==null||!cardId.equals(a.optString("cardId")))continue;
   String op=a.optString("op");if(op.equals("action"))op="action:"+a.optString("actionId");if(!OPS.contains(op)&&!op.startsWith("action:"))continue;
   JSONObject e=new JSONObject().put("at",a.optLong("at")).put("op",op).put("origin",a.optString("origin"));
   for(String field:List.of("cursor","phase","intentType"))if(a.has(field))e.put(field,a.get(field));
   JSONObject d=a.optJSONObject("detail");if(d!=null&&!d.optString("effect").isEmpty())e.put("effect",d.optString("effect"));
   // A migrated event may already exist in feedback recorded by the previous launcher.
   if(a.optString("phase").equals("legacy-recorded")&&seen.contains(legacyKey(e)))continue;
   if(seen.add(key(e)))out.add(e);
  }
  if(!snapshot.has("events"))out.sort(Comparator.comparingLong(e->e.optLong("at")));
  if(has(snapshot.optJSONArray("removed"),cardId)&&!last(out,"remove","restore").equals("remove")){
   JSONObject e=new JSONObject().put("at",now).put("op","remove").put("origin","");if(seen.add(key(e)))out.add(e);   // now is after everything logged
  }
  if(out.size()==before)return null;
  JSONArray kept=new JSONArray();for(JSONObject e:out.subList(Math.max(0,out.size()-KEEP),out.size()))kept.put(e);
  return kept;
 }
 /**
  * The user's reactions to a card, in {@link #REACTIONS} order: selected (durably accepted), asked (a button opened Taku with the card's draft; nothing is
  * sent until the user sends it), opened (a button opened the app the message came from), pinned and removed (as they
  * stand after the last pin/unpin and remove/restore).
  */
 static List<String> reactions(JSONArray events){
  List<JSONObject> list=new ArrayList<>();if(events!=null)for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e!=null)list.add(e);}
  Set<String> r=new HashSet<>();
  for(JSONObject e:list){if(e.optString("phase").equals("accepted"))r.add("selected");String effect=e.optString("effect");if(effect.equals("ask-taku"))r.add("asked");if(effect.equals("open-app"))r.add("opened");}
  if(last(list,"pin","unpin").equals("pin"))r.add("pinned");
  if(last(list,"remove","restore").equals("remove"))r.add("removed");
  List<String> ordered=new ArrayList<>();for(String x:REACTIONS)if(r.contains(x))ordered.add(x);
  return ordered;
 }
 static String key(JSONObject e){return e.has("cursor")?e.optString("cursor"):legacyKey(e);}
 static String legacyKey(JSONObject e){return e.optLong("at")+"|"+e.optString("op")+"|"+e.optString("origin");}
 /** The later of two opposite operations, or "" when neither happened (durable events are in commit order). */
 static String last(List<JSONObject> events,String on,String off){String r="";for(JSONObject e:events){String op=e.optString("op");if(op.equals(on)||op.equals(off))r=op;}return r;}
 static boolean has(JSONArray a,String id){if(a!=null)for(int i=0;i<a.length();i++)if(id.equals(a.optString(i)))return true;return false;}
 static JSONArray parse(String s){try{return s==null||s.isBlank()?new JSONArray():new JSONArray(s);}catch(JSONException e){return new JSONArray();}}
}
