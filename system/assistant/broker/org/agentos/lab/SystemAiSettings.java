package org.agentos.lab;

import org.json.*;
import java.util.*;

/** Independent Settings.Global values. The Settings provider atomically replaces each value. */
final class SystemAiSettings {
 static final String ENABLED="taku_ai_enabled", AUDIO_ENABLED="taku_ai_audio_enabled", TEXT="taku_ai_group_text", IMAGE="taku_ai_group_image", APP="taku_ai_app_";
 interface Store { String get(String key)throws Exception; void put(String key,String value)throws Exception; void delete(String key)throws Exception; Map<String,String> list()throws Exception; }
 static final String CONFIG="taku_system_ai_config";
 static String field(String key){return switch(key){case "taku_magpie_providers"->"providers";case "taku_magpie_models"->"models";case "taku_magpie_groups"->"groups";case "taku_magpie_scene_bindings"->"scenes";default->null;};}
 /** Compatibility view for Broker callers while the only persisted Magpie value is CONFIG. */
 static Store unified(Store raw){return new Store(){
  public String get(String key)throws Exception{String f=field(key),v=raw.get(CONFIG);if(f==null||v==null)return raw.get(key);JSONObject c=new JSONObject(v);return c.has(f)?c.get(f).toString():null;}
  public void put(String key,String value)throws Exception{String f=field(key),v=raw.get(CONFIG);if(f==null||v==null){raw.put(key,value);return;}JSONObject c=new JSONObject(v);c.put(f,f.equals("providers")||f.equals("groups")?new JSONArray(value):new JSONObject(value));c.put("updated_at",System.currentTimeMillis()).put("updated_by","taku");raw.put(CONFIG,c.toString());}
  public void delete(String key)throws Exception{raw.delete(key);}
  public Map<String,String> list()throws Exception{return raw.list();}
 };}
 static void publishConfig(Store store)throws Exception{
  if(store.get(CONFIG)!=null)return;
  JSONObject c=new JSONObject().put("version",1).put("updated_at",System.currentTimeMillis()).put("updated_by","migration")
   .put("providers",new JSONArray(Optional.ofNullable(store.get("taku_magpie_providers")).orElse("[]")))
   .put("models",new JSONObject(Optional.ofNullable(store.get("taku_magpie_models")).orElse("{}")))
   .put("groups",new JSONArray(Optional.ofNullable(store.get(GROUPS)).orElse("[]")))
   .put("scenes",new JSONObject(Optional.ofNullable(store.get(SCENES)).orElse("{}")));
  store.put(CONFIG,c.toString());
  for(String k:new String[]{"taku_magpie_providers","taku_magpie_models",GROUPS,SCENES})store.delete(k);
 }
 static boolean bool(String raw,boolean fallback)throws Exception{
  if(raw==null)return fallback;
  if(raw.equals("1")||raw.equals("true"))return true;
  if(raw.equals("0")||raw.equals("false"))return false;
  throw new IllegalArgumentException("Invalid system AI toggle format");
 }
 static void migrate(Store store,JSONObject old)throws Exception{
  putMissing(store,ENABLED,old.getBoolean("enabled")?"1":"0");
  putMissing(store,TEXT,old.getString("textModel"));putMissing(store,IMAGE,old.getString("imageModel"));
  JSONObject apps=old.getJSONObject("apps");for(String app:AiPolicy.keys(apps)){AiPolicy.requireApp(app);putMissing(store,APP+app,apps.getBoolean(app)?"1":"0");}
 }
 static void putMissing(Store store,String key,String value)throws Exception{if(store.get(key)==null)store.put(key,value);}
 static final String SLOTS_MIGRATED="taku_magpie_slots_migrated_v1", GROUPS="taku_magpie_groups", SCENES="taku_magpie_scene_bindings";
 static void migrateSlots(Store store)throws Exception{
  if("1".equals(store.get(SLOTS_MIGRATED))){if(store.get(TEXT)!=null)store.delete(TEXT);if(store.get(IMAGE)!=null)store.delete(IMAGE);return;}
  // Earlier releases used these values implicitly when the corresponding keys were absent.
  String text=Optional.ofNullable(store.get(TEXT)).orElse("codex/gpt-6-luna"),image=Optional.ofNullable(store.get(IMAGE)).orElse("codex/gpt-6-luna");
  JSONArray groups=new JSONArray(Optional.ofNullable(store.get(GROUPS)).orElse("[]"));
  JSONObject scenes=new JSONObject(Optional.ofNullable(store.get(SCENES)).orElse("{}"));
  String oldText=scenes.optString("text",""),oldImage=scenes.optString("image","");
  String oldIme=scenes.optString("ime.image",oldImage),oldScreen=scenes.optString("org.agentos.assistant.image",oldImage);
  if(!oldText.isEmpty())bindMissing(scenes,"memory.text",oldText);
  if(!oldIme.isEmpty())bindMissing(scenes,"ime.vision",oldIme);
  if(!oldScreen.isEmpty())bindMissing(scenes,"screen",oldScreen);
  for(String key:store.list().keySet())if(key.startsWith(APP)){String app=key.substring(APP.length());
   if(!app.equals("ime")&&!app.equals("memory")){
    if(!oldText.isEmpty())bindMissing(scenes,app+".text",oldText);
    if(!app.equals("org.agentos.assistant")&&!oldImage.isEmpty())bindMissing(scenes,app+".image",oldImage);
   }
  }
  scenes.remove("text");scenes.remove("image");scenes.remove("ime.image");scenes.remove("org.agentos.assistant.image");
  if(text!=null&&!text.isBlank()){
   boolean hadMemoryGroup=hasMemoryGroup(groups);
   String memoryTarget=memoryGroup(groups,scenes.optString("memory.text",text));
   if(!hadMemoryGroup&&memoryTarget.equals("group/taku-memory-jev"))scenes.put("memory.text",memoryTarget);else bindMissing(scenes,"memory.text",memoryTarget);
   String target=legacyGroup(groups,text,"route-memory");
   for(String key:store.list().keySet())if(key.startsWith(APP)){String app=key.substring(APP.length());if(!app.equals("memory")&&!app.equals("ime"))bindMissing(scenes,app+".text",target);}
  }
  if(image!=null&&!image.isBlank()){
   String target=legacyGroup(groups,image,"route-vision");bindMissing(scenes,"ime.vision",target);bindMissing(scenes,"screen",target);
   for(String key:store.list().keySet())if(key.startsWith(APP)){String app=key.substring(APP.length());if(!app.equals("memory")&&!app.equals("ime")&&!app.equals("org.agentos.assistant"))bindMissing(scenes,app+".image",target);}
  }
  store.put(GROUPS,groups.toString());store.put(SCENES,scenes.toString());
  if(!groups.toString().equals(store.get(GROUPS))||!scenes.toString().equals(store.get(SCENES)))throw new IllegalStateException("Scene migration was not saved");
  store.put(SLOTS_MIGRATED,"1");store.delete(TEXT);store.delete(IMAGE);
 }
 static boolean hasMemoryGroup(JSONArray groups)throws Exception{for(int i=0;i<groups.length();i++)if("taku-memory-jev".equals(groups.getJSONObject(i).optString("id")))return true;return false;}
 // Create a separate editable group from the existing Memory route.
 static String memoryGroup(JSONArray groups,String source)throws Exception{
  String id="taku-memory-jev";
  for(int i=0;i<groups.length();i++)if(id.equals(groups.getJSONObject(i).optString("id")))return "group/"+id;
  JSONObject copy=null;
  if(source.startsWith("group/"))for(int i=0;i<groups.length();i++)if(source.substring(6).equals(groups.getJSONObject(i).optString("id"))){copy=new JSONObject(groups.getJSONObject(i).toString());break;}
  if(copy==null){if(source.startsWith("group/"))return source;copy=new JSONObject().put("members",new JSONArray().put(source)).put("routing","order");}
  groups.put(copy.put("id",id).put("name","Taku Memory"));return "group/"+id;
 }
 // Preserve the former shared text default for first-party card/audio consumers, once.
 static void ensurePresentationRoutes(Store store)throws Exception{
  String marker="taku_magpie_presentation_routes_v1";if("1".equals(store.get(marker)))return;
  JSONArray groups=new JSONArray(Optional.ofNullable(store.get(GROUPS)).orElse("[]"));
  JSONObject scenes=new JSONObject(Optional.ofNullable(store.get(SCENES)).orElse("{}"));
  String source=scenes.optString("text",Optional.ofNullable(store.get(TEXT)).orElse(scenes.optString("org.agentos.assistant.text",scenes.optString("assistant","codex/gpt-6-luna"))));
  for(String scene:new String[]{"agent-ui.text","org.agentos.audio.text"})if(!scenes.has(scene)){
   scenes.put(scene,legacyGroup(groups,source,"route-presentation"));
  }
  store.put(GROUPS,groups.toString());store.put(SCENES,scenes.toString());
  if(!groups.toString().equals(store.get(GROUPS))||!scenes.toString().equals(store.get(SCENES)))throw new IllegalStateException("Card and voice scene migration was not saved");
  store.put(marker,"1");
 }
 static void ensureMemoryGroup(Store store)throws Exception{
  String marker="taku_magpie_memory_group_v1";if("1".equals(store.get(marker)))return;
  JSONArray groups=new JSONArray(Optional.ofNullable(store.get(GROUPS)).orElse("[]"));
  JSONObject scenes=new JSONObject(Optional.ofNullable(store.get(SCENES)).orElse("{}"));
  String source=scenes.optString("memory.text","codex/gpt-6-luna");
  // An existing same-ID group may be user-managed and differ from the current route.
  if(hasMemoryGroup(groups)&&!source.equals("group/taku-memory-jev"))return;
  if(!memoryGroup(groups,source).equals("group/taku-memory-jev"))return;
  store.put(GROUPS,groups.toString());if(!groups.toString().equals(store.get(GROUPS)))throw new IllegalStateException("Memory model group was not saved");
  scenes.put("memory.text","group/taku-memory-jev");store.put(SCENES,scenes.toString());if(!scenes.toString().equals(store.get(SCENES)))throw new IllegalStateException("Memory scene was not saved");
  store.put(marker,"1");
 }
 static void bindMissing(JSONObject scenes,String scene,String target)throws Exception{if(!scenes.has(scene))scenes.put(scene,target);}
 static String legacyGroup(JSONArray groups,String model,String base)throws Exception{
  if(model.startsWith("group/"))return model;
  String id=base;int suffix=2;
  for(;;){JSONObject found=null;for(int i=0;i<groups.length();i++){JSONObject g=groups.getJSONObject(i);if(id.equals(g.optString("id"))){found=g;break;}}
   if(found==null){groups.put(new JSONObject().put("id",id).put("name",id.equals("taku-memory-jev")?"Taku Memory":id.equals("route-memory")?"Legacy route · Text":id.equals("route-vision")?"Legacy route · Vision":id.equals("route-assistant")?"Main assistant route":id).put("members",new JSONArray().put(model)).put("routing","order"));return "group/"+id;}
   JSONArray members=found.optJSONArray("members");if(members!=null&&members.length()==1&&model.equals(members.optString(0)))return "group/"+id;
   id=base+"-"+suffix++;
  }
 }
 static JSONObject read(Store store)throws Exception{
  Map<String,String> values=store.list();JSONObject defaults=AiPolicy.defaults(),apps=defaults.getJSONObject("apps");
  for(Map.Entry<String,String> e:values.entrySet())if(e.getKey().startsWith(APP)){
   String app=e.getKey().substring(APP.length());AiPolicy.requireApp(app);apps.put(app,bool(e.getValue(),false));
  }
  JSONObject result=defaults.put("enabled",bool(values.get(ENABLED),true)).put("audioEnabled",bool(values.get(AUDIO_ENABLED),true)).put("apps",apps);
  // Compatibility token for existing in-flight consumers; never stored or used as a write version.
  List<String> keys=new ArrayList<>(AiPolicy.keys(apps));Collections.sort(keys);StringBuilder snapshot=new StringBuilder().append(result.getBoolean("enabled")).append('\0').append("audio=").append(result.getBoolean("audioEnabled"));
  for(String key:keys)snapshot.append('\0').append(key).append('=').append(apps.getBoolean(key));
  byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  return result.put("revision",java.nio.ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE);
 }
 static void writePatch(Store store,JSONObject patch)throws Exception{
  if(patch.has("enabled"))store.put(ENABLED,patch.getBoolean("enabled")?"1":"0");
  if(patch.has("audioEnabled"))store.put(AUDIO_ENABLED,patch.getBoolean("audioEnabled")?"1":"0");
  if(patch.has("apps")){JSONObject apps=patch.getJSONObject("apps");for(String app:AiPolicy.keys(apps)){AiPolicy.requireApp(app);store.put(APP+app,apps.getBoolean(app)?"1":"0");}}
 }
}
