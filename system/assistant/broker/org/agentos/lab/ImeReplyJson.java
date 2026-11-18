package org.agentos.lab;
import org.json.*;
import java.io.IOException;
import java.util.*;
/** Strict text-only result; model output never becomes an executable operation. */
final class ImeReplyJson {
 static JSONObject withoutPrevious(JSONObject parsed,JSONArray previous)throws Exception{
  Set<String> old=new HashSet<>();for(int i=0;i<previous.length();i++)old.add(previous.getString(i).trim());
  JSONArray out=new JSONArray(),all=parsed.getJSONArray("candidates");
  for(int i=0;i<all.length();i++)if(!old.contains(all.getJSONObject(i).getString("text")))out.put(all.getJSONObject(i));
  return new JSONObject().put("version",1).put("candidates",out);
 }
 static JSONObject parse(String raw)throws Exception{
  if(raw==null||raw.length()>6000)throw new IOException("Reply result exceeds the allowed range");
  JSONTokener tokens=new JSONTokener(raw);JSONObject v=new JSONObject(tokens);
  if(tokens.nextClean()!=0)throw new IOException("Invalid reply format");
  Set<String> keys=new HashSet<>();v.keys().forEachRemaining(keys::add);
  if(!keys.equals(Set.of("version","candidates"))||!(v.get("version") instanceof Number)||v.getDouble("version")!=1)throw new IOException("Invalid reply format");
  JSONArray a=v.getJSONArray("candidates"),out=new JSONArray();Set<String> seen=new HashSet<>();
  if(a.length()>3)throw new IOException("Too many candidates");
  for(int i=0;i<a.length();i++){
   JSONObject c=a.getJSONObject(i);if(c.length()!=1||!(c.get("text") instanceof String))throw new IOException("Invalid candidate format");
   String t=c.getString("text").trim();if(t.isEmpty()||t.codePointCount(0,t.length())>48||t.codePoints().anyMatch(n->Character.isISOControl(n)||n==0x202e||n==0x202d||(n>=0x2066&&n<=0x2069)))throw new IOException("Candidate is unavailable");
   if(seen.add(t))out.put(new JSONObject().put("text",t));
  }return new JSONObject().put("version",1).put("candidates",out);
 }
}
