package org.agentos.lab;

import org.json.*;
import java.util.Set;

/** Credential-free system defaults. Empty application overrides always resolve here. */
final class AiPolicy {
 static java.util.Set<String> keys(JSONObject o){java.util.Set<String> keys=new java.util.LinkedHashSet<>();for(java.util.Iterator<String> it=o.keys();it.hasNext();)keys.add(it.next());return keys;}
 static JSONArray mergeDecisionModels(JSONArray catalog,JSONArray decisions)throws Exception{
  JSONArray result=new JSONArray();java.util.Set<String> decisionIds=new java.util.HashSet<>();
  for(int i=0;i<decisions.length();i++)decisionIds.add(decisions.getJSONObject(i).getString("id"));
  for(int i=0;i<catalog.length();i++){JSONObject item=catalog.getJSONObject(i);if(!decisionIds.contains(item.getString("id")))result.put(item);}
  for(int i=0;i<decisions.length();i++){JSONObject item=new JSONObject(decisions.getJSONObject(i).toString());result.put(item.put("kind","decision"));}
  return result;
 }
 static JSONObject defaults()throws Exception{return new JSONObject().put("version",1).put("revision",0).put("enabled",true).put("audioEnabled",true).put("apps",new JSONObject().put("ime",true).put("memory",true).put("agent-ui",true));}
 static JSONObject update(JSONObject old,JSONObject patch,JSONArray catalog)throws Exception{
  JSONObject next=new JSONObject(old.toString());
  for(String key:keys(patch)){
   if(!Set.of("enabled","audioEnabled","apps").contains(key))throw new IllegalArgumentException("Unknown system AI setting");
   switch(key){
    case "enabled":case "audioEnabled":next.put(key,patch.getBoolean(key));break;
    case "apps":JSONObject apps=patch.getJSONObject(key);for(String app:keys(apps)){requireApp(app);next.getJSONObject("apps").put(app,apps.getBoolean(app));}break;
   }
  }
  return next.put("revision",old.getLong("revision")+1);
 }
 static void requireApp(String app){if(!Set.of("ime","memory","agent-ui").contains(app)&&!app.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new SecurityException("This app is not integrated with system AI");}
 // agent-ui is first-party; settings saved before it existed keep it on until the user turns it off.
 static void allowed(JSONObject config,String app){requireApp(app);if(!config.optBoolean("enabled")||!config.optJSONObject("apps").optBoolean(app,app.equals("agent-ui")))throw new SecurityException("System AI has paused this feature");}
 static JSONObject resolve(JSONObject config,String app,String role,String override,JSONArray catalog)throws Exception{
  allowed(config,app);if(!Set.of("text","image").contains(role))throw new IllegalArgumentException("Unknown AI capability");
  String id=override;
  return new JSONObject(find(catalog,id,role.equals("image"),app.equals("memory")).toString()).put("revision",config.getLong("revision")).put("followsSystem",override.isEmpty());
 }
 static JSONObject find(JSONArray catalog,String id,boolean image,boolean decision)throws Exception{
  if(id.isBlank()||id.length()>512)throw new IllegalArgumentException("Select an available model");
  for(int i=0;i<catalog.length();i++){JSONObject m=catalog.getJSONObject(i);if(m.getString("id").equals(id)){
   if((m.optString("kind").equals("decision")&&!decision)||!(image?m.optBoolean("imageInput"):m.optBoolean("textInput")))throw new IllegalArgumentException("The model does not support the required input capability");return m;
  }}throw new IllegalArgumentException("This model is no longer available. Select another in Taku AI");
 }
}
