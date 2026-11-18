package org.agentos.assistant;

import android.content.Context;
import android.provider.Settings;
import org.json.*;

/** Scene bindings are the shared routing selection; group membership stays in Magpie settings. */
final class SceneRoutes {
 static String get(Context context,String scene)throws Exception{
  String config=Settings.Global.getString(context.getContentResolver(),"taku_system_ai_config");
  if(config!=null)return new JSONObject(config).getJSONObject("scenes").optString(scene,"");
  String raw=Settings.Global.getString(context.getContentResolver(),"taku_magpie_scene_bindings");
  return new JSONObject(raw==null?"{}":raw).optString(scene,"");
 }
 static String bind(Context context,String scene,String model,boolean overwrite)throws Exception{
  String rawConfig=Settings.Global.getString(context.getContentResolver(),"taku_system_ai_config");
  if(rawConfig!=null){
   JSONObject config=new JSONObject(rawConfig);String result=bindUnified(config,scene,model,overwrite);
   if(!rawConfig.equals(Settings.Global.getString(context.getContentResolver(),"taku_system_ai_config")))throw new IllegalStateException("Configuration changed. Retry");
   config.put("updated_at",System.currentTimeMillis()).put("updated_by","taku");
   if(!Settings.Global.putString(context.getContentResolver(),"taku_system_ai_config",config.toString()))throw new IllegalStateException("Could not save scene binding");
   return result;
  }
  String raw=Settings.Global.getString(context.getContentResolver(),"taku_magpie_scene_bindings");
  JSONObject bindings=new JSONObject(raw==null?"{}":raw);
  if(!overwrite&&bindings.has(scene))return bindings.getString(scene);
  String target=model;
  if(!model.startsWith("group/")){
   raw=Settings.Global.getString(context.getContentResolver(),"taku_magpie_groups");JSONArray groups=new JSONArray(raw==null?"[]":raw);
   String base="route-"+scene.replaceAll("[^a-zA-Z0-9_-]","-"),id=base;int suffix=2;
   for(;;){JSONObject found=null;for(int i=0;i<groups.length();i++){JSONObject g=groups.getJSONObject(i);if(id.equals(g.optString("id"))){found=g;break;}}
    if(found==null){groups.put(new JSONObject().put("id",id).put("name",scene.equals("jev")?"Jev route":id).put("members",new JSONArray().put(model)).put("routing","order"));break;}
    JSONArray members=found.optJSONArray("members");if(members!=null&&members.length()==1&&model.equals(members.optString(0)))break;id=base+"-"+suffix++;
   }
   if(!Settings.Global.putString(context.getContentResolver(),"taku_magpie_groups",groups.toString()))throw new IllegalStateException("Could not save routing group");
   target="group/"+id;
  }
  raw=Settings.Global.getString(context.getContentResolver(),"taku_magpie_scene_bindings");bindings=new JSONObject(raw==null?"{}":raw);
  if(overwrite||!bindings.has(scene)){bindings.put(scene,target);if(!Settings.Global.putString(context.getContentResolver(),"taku_magpie_scene_bindings",bindings.toString()))throw new IllegalStateException("Could not save scene binding");}
  return bindings.getString(scene);
 }
 static String bindUnified(JSONObject config,String scene,String model,boolean overwrite)throws Exception{
  JSONObject bindings=config.getJSONObject("scenes");JSONArray groups=config.getJSONArray("groups");
  if(!overwrite&&bindings.has(scene))return bindings.getString(scene);
  String target=model;
  if(!model.startsWith("group/")){
   String base="route-"+scene.replaceAll("[^a-zA-Z0-9_-]","-"),id=base;int suffix=2;
   for(;;){JSONObject found=null;for(int i=0;i<groups.length();i++){JSONObject g=groups.getJSONObject(i);if(id.equals(g.optString("id"))){found=g;break;}}
    if(found==null){groups.put(new JSONObject().put("id",id).put("name",scene.equals("jev")?"Jev route":id).put("members",new JSONArray().put(model)).put("routing","order"));break;}
    JSONArray members=found.optJSONArray("members");if(members!=null&&members.length()==1&&model.equals(members.optString(0)))break;id=base+"-"+suffix++;
   }
   target="group/"+id;
  }
  bindings.put(scene,target);return target;
 }
}
