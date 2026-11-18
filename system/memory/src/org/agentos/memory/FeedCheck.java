package org.agentos.memory;

import org.json.*;
import java.util.*;

/**
 * Checks a card the home feed's AI designed from a description (`feed/generate`) before memory keeps it: the actions must be
 * exactly the ones memory asked for (so "draft, never send" prompts and app targets are not rewritten), every action must
 * have a button, and each text memory supplied must appear as written. Only org.json, so the same check runs off the device.
 */
final class FeedCheck {
 private FeedCheck(){}
 /** Characters of each supplied text that must appear as written; the home's AI may let a long line run out of space. */
 static final int KEEP=24;

 /** @return null when the card keeps what memory asked for, otherwise a short reason. */
 static String check(JSONObject card,JSONObject actions,List<String> texts){
  if(card==null)return "no card";
  JSONObject got=card.optJSONObject("actions");if(got==null)got=new JSONObject();
  if(!keys(got).equals(keys(actions)))return "actions "+keys(got)+" instead of "+keys(actions);
  for(String k:keys(actions)){
   JSONObject want=actions.optJSONObject(k),have=got.optJSONObject(k);
   if(have==null||!norm(want.optString("label")).equals(norm(have.optString("label"))))return "action "+k+" label changed";
   if(!same(want.optJSONObject("intent"),have.optJSONObject("intent")))return "action "+k+" intent changed";
  }
  JSONObject ui=card.optJSONObject("ui"),spec=ui==null?null:ui.optJSONObject("spec"),el=spec==null?null:spec.optJSONObject("elements");
  if(el==null)return "no body";
  Set<String> pressed=new TreeSet<>();StringBuilder shown=new StringBuilder();
  collect(ui.opt("state"),shown);   // Text may also reside in state and bind to components.
  for(Iterator<String> i=el.keys();i.hasNext();){
   JSONObject node=el.optJSONObject(i.next());if(node==null)continue;
   collect(node.opt("props"),shown);
   JSONObject on=node.optJSONObject("on");
   if(on!=null)for(Iterator<String> e=on.keys();e.hasNext();){JSONObject b=on.optJSONObject(e.next());JSONObject p=b==null?null:b.optJSONObject("params");if(p!=null&&!p.optString("actionId").isEmpty())pressed.add(p.optString("actionId"));}
  }
  if(!pressed.containsAll(keys(actions)))return "no button for "+diff(keys(actions),pressed);
  String all=norm(shown.toString());
  for(String t:texts){String need=head(norm(t));if(!need.isEmpty()&&!all.contains(need))return "missing text: "+need;}
  return null;
 }
 static Set<String> keys(JSONObject o){Set<String> k=new TreeSet<>();for(Iterator<String> i=o.keys();i.hasNext();)k.add(i.next());return k;}
 static Set<String> diff(Set<String> a,Set<String> b){Set<String> d=new TreeSet<>(a);d.removeAll(b);return d;}
 /** Same keys and the same values up to whitespace (a model may add a trailing line break); intents are flat objects of strings. */
 static boolean same(JSONObject a,JSONObject b){
  if(a==null||b==null)return a==b;
  if(!keys(a).equals(keys(b)))return false;
  for(String k:keys(a))if(!norm(String.valueOf(a.opt(k))).equals(norm(String.valueOf(b.opt(k)))))return false;
  return true;
 }
 /** Every literal string under props (text, title, trailing, label, tag items...). */
 static void collect(Object v,StringBuilder out){
  if(v instanceof String)out.append((String)v).append('\n');
  else if(v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++)collect(a.opt(i),out);}
  else if(v instanceof JSONObject){JSONObject o=(JSONObject)v;for(Iterator<String> i=o.keys();i.hasNext();)collect(o.opt(i.next()),out);}
 }
 static String norm(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
 static String head(String s){return s.codePointCount(0,s.length())<=KEEP?s:s.substring(0,s.offsetByCodePoints(0,KEEP));}
}
