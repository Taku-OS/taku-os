package org.agentos.lab;
import org.json.*;
import java.util.*;
public final class SystemAiSettingsTest {
 static final class Store implements SystemAiSettings.Store {
  final Map<String,String> values=new HashMap<>();
  public String get(String key){return values.get(key);}
  public void put(String key,String value){values.put(key,value);}
  public void delete(String key){values.remove(key);}
  public Map<String,String> list(){return new HashMap<>(values);}
 }
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  Store store=new Store();JSONObject legacy=AiPolicy.defaults().put("enabled",false).put("textModel","old/text").put("imageModel","old/image");legacy.getJSONObject("apps").put("org.example.app",true);
  store.put(SystemAiSettings.TEXT,"external/text");SystemAiSettings.migrate(store,legacy);
  store.put(SystemAiSettings.GROUPS,"[{\"id\":\"existing\",\"members\":[\"model/x\"]}]");
  store.put(SystemAiSettings.SCENES,"{\"memory.text\":\"group/existing\"}");
  SystemAiSettings.migrateSlots(store);
  JSONObject scenes=new JSONObject(store.get(SystemAiSettings.SCENES));JSONArray groups=new JSONArray(store.get(SystemAiSettings.GROUPS));
  check(scenes.getString("memory.text").equals("group/taku-memory-jev"));check(scenes.getString("ime.vision").equals("group/route-vision"));check(scenes.getString("screen").equals("group/route-vision"));check(scenes.getString("org.example.app.text").equals("group/route-memory"));check(groups.length()==4);
  check(store.get(SystemAiSettings.TEXT)==null&&store.get(SystemAiSettings.IMAGE)==null);check(!SystemAiSettings.read(store).has("textModel"));check(!SystemAiSettings.read(store).has("imageModel"));
  String before=store.get(SystemAiSettings.SCENES);SystemAiSettings.migrateSlots(store);check(before.equals(store.get(SystemAiSettings.SCENES)));
  SystemAiSettings.writePatch(store,new JSONObject().put("apps",new JSONObject().put("memory",false)));check(!SystemAiSettings.read(store).getJSONObject("apps").getBoolean("memory"));
  Store roles=new Store();roles.put(SystemAiSettings.SCENES,"{\"text\":\"group/prior-text\",\"image\":\"group/prior-image\",\"ime.image\":\"group/prior-ime\"}");SystemAiSettings.migrateSlots(roles);
  JSONObject converted=new JSONObject(roles.get(SystemAiSettings.SCENES));check(converted.getString("memory.text").equals("group/prior-text"));check(converted.getString("ime.vision").equals("group/prior-ime"));check(converted.getString("screen").equals("group/prior-image"));check(!converted.has("text")&&!converted.has("image"));
  Store fresh=new Store();SystemAiSettings.migrateSlots(fresh);SystemAiSettings.ensureMemoryGroup(fresh);
  check(new JSONObject(fresh.get(SystemAiSettings.SCENES)).getString("memory.text").equals("group/taku-memory-jev"));
  Store upgraded=new Store();upgraded.put(SystemAiSettings.GROUPS,"[{\"id\":\"chosen\",\"members\":[\"fixture/jev\"],\"routing\":\"order\"}]");upgraded.put(SystemAiSettings.SCENES,"{\"memory.text\":\"group/chosen\"}");SystemAiSettings.ensureMemoryGroup(upgraded);
  check(new JSONObject(upgraded.get(SystemAiSettings.SCENES)).getString("memory.text").equals("group/taku-memory-jev"));check(new JSONArray(upgraded.get(SystemAiSettings.GROUPS)).getJSONObject(1).getJSONArray("members").getString(0).equals("fixture/jev"));
  Store migration=new Store();migration.put(SystemAiSettings.GROUPS,"[{\"id\":\"chosen\",\"members\":[\"fixture/jev\"],\"routing\":\"order\"}]");migration.put(SystemAiSettings.SCENES,"{\"memory.text\":\"group/chosen\"}");SystemAiSettings.migrateSlots(migration);SystemAiSettings.ensureMemoryGroup(migration);
  JSONArray migratedGroups=new JSONArray(migration.get(SystemAiSettings.GROUPS));boolean copied=false;for(int i=0;i<migratedGroups.length();i++){JSONObject g=migratedGroups.getJSONObject(i);if(g.getString("id").equals("taku-memory-jev"))copied=g.getJSONArray("members").getString(0).equals("fixture/jev");}check(copied);check(new JSONObject(migration.get(SystemAiSettings.SCENES)).getString("memory.text").equals("group/taku-memory-jev"));
  Store conflict=new Store();conflict.put(SystemAiSettings.GROUPS,"[{\"id\":\"taku-memory-jev\",\"members\":[\"model/a\"]},{\"id\":\"chosen\",\"members\":[\"model/b\"]}]");conflict.put(SystemAiSettings.SCENES,"{\"memory.text\":\"group/chosen\"}");SystemAiSettings.migrateSlots(conflict);SystemAiSettings.ensureMemoryGroup(conflict);check(new JSONObject(conflict.get(SystemAiSettings.SCENES)).getString("memory.text").equals("group/chosen"));
  String saved=upgraded.get(SystemAiSettings.GROUPS);SystemAiSettings.ensureMemoryGroup(upgraded);check(saved.equals(upgraded.get(SystemAiSettings.GROUPS)));
  Store presentation=new Store();presentation.put(SystemAiSettings.TEXT,"custom/text");SystemAiSettings.ensurePresentationRoutes(presentation);SystemAiSettings.migrateSlots(presentation);
  JSONObject routes=new JSONObject(presentation.get(SystemAiSettings.SCENES));String uiRoute=routes.getString("agent-ui.text");
  check(uiRoute.equals(routes.getString("org.agentos.audio.text")));boolean preserved=false;
  JSONArray routeGroups=new JSONArray(presentation.get(SystemAiSettings.GROUPS));for(int i=0;i<routeGroups.length();i++){JSONObject g=routeGroups.getJSONObject(i);if(uiRoute.equals("group/"+g.getString("id")))preserved=g.getJSONArray("members").getString(0).equals("custom/text");}check(preserved);
  Store existingUi=new Store();existingUi.put(SystemAiSettings.SCENES,"{\"agent-ui.text\":\"group/custom-ui\",\"assistant\":\"group/main\"}");SystemAiSettings.ensurePresentationRoutes(existingUi);
  JSONObject uiScenes=new JSONObject(existingUi.get(SystemAiSettings.SCENES));check(uiScenes.getString("agent-ui.text").equals("group/custom-ui"));check(uiScenes.getString("org.agentos.audio.text").equals("group/main"));
  uiScenes.remove("agent-ui.text");existingUi.put(SystemAiSettings.SCENES,uiScenes.toString());SystemAiSettings.ensurePresentationRoutes(existingUi);check(!new JSONObject(existingUi.get(SystemAiSettings.SCENES)).has("agent-ui.text"));
  JSONObject uiOff=AiPolicy.update(AiPolicy.defaults(),new JSONObject().put("apps",new JSONObject().put("agent-ui",false)),new JSONArray());SystemAiSettings.writePatch(presentation,new JSONObject().put("apps",uiOff.getJSONObject("apps")));check(!SystemAiSettings.read(presentation).getJSONObject("apps").getBoolean("agent-ui"));
  notebookPolicy();
  System.out.println("PASS SystemAiSettingsTest: slot migration and Notebook AI defaults");
 }

 static void notebookPolicy()throws Exception{
  String notebook="org.agentos.notebook";
  Store store=new Store();
  check(SystemAiSettings.read(store).getJSONObject("apps").getBoolean(notebook));
  AiPolicy.allowed(SystemAiSettings.read(store),notebook);

  SystemAiSettings.writePatch(store,new JSONObject().put("enabled",false));
  check(!SystemAiSettings.read(store).getBoolean("enabled"));
  rejectsNotebook(store,notebook);

  SystemAiSettings.writePatch(store,new JSONObject().put("enabled",true)
    .put("apps",new JSONObject().put(notebook,false)));
  check(!SystemAiSettings.read(store).getJSONObject("apps").getBoolean(notebook));
  rejectsNotebook(store,notebook);
 }

 static void rejectsNotebook(Store store,String notebook)throws Exception{
  JSONObject settings=SystemAiSettings.read(store);
  try{AiPolicy.allowed(settings,notebook);}catch(SecurityException expected){return;}
  throw new AssertionError("Notebook AI should be paused");
 }
}
