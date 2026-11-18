package org.agentos.lab;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
public final class MagpieSyncTest {
 static final class Store implements SystemAiSettings.Store {
  Map<String,String> values=new HashMap<>();int writes;
  public String get(String k){return values.get(k);} public void put(String k,String v){values.put(k,v);writes++;} public void delete(String k){values.remove(k);} public Map<String,String> list(){return values;}
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static JSONObject config(Store s)throws Exception{return new JSONObject(s.get(SystemAiSettings.CONFIG));}
 static JSONObject base(long time)throws Exception{return new JSONObject().put("version",1).put("updated_at",time).put("updated_by","cli").put("providers",new JSONArray().put(new JSONObject().put("id","alpha").put("name","CLI provider"))).put("models",new JSONObject().put("alpha",new JSONArray().put("one"))).put("groups",new JSONArray()).put("scenes",new JSONObject().put("screen","group/vision"));}
 static void set(Store s,JSONObject c)throws Exception{s.put(SystemAiSettings.CONFIG,c.toString());}
 static void file(File f,String name,String key,String model)throws Exception{JSONObject p=new JSONObject().put("id","alpha").put("name",name).put("key",key).put("models",new JSONArray().put(model));Files.writeString(f.toPath(),new JSONObject().put("providers",new JSONArray().put(p)).put("groups",new JSONArray()).toString());}
 public static void main(String[]args)throws Exception{
  File dir=Files.createTempDirectory("magpie-sync").toFile(),source=new File(dir,"providers.json"),secrets=new File(dir,"secrets.json");Store s=new Store();long now=System.currentTimeMillis();set(s,base(now));
  MagpieSettings.sync(s,source,secrets);JSONObject projected=new JSONObject(Files.readString(source.toPath()));check(projected.getJSONArray("providers").getJSONObject(0).getJSONArray("models").getString(0).equals("one"),"settings -> model");
  int writes=s.writes;MagpieSettings.importFile(s,source,secrets);check(s.writes==writes,"own projection caused a loop");
  file(source,"Edited in Magpie","private-token","two");MagpieSettings.importFile(s,source,secrets);JSONObject imported=config(s);check(imported.getString("updated_by").equals("magpie"),"file source");check(imported.getJSONArray("providers").getJSONObject(0).getString("name").equals("Edited in Magpie"),"file -> provider");check(imported.getJSONObject("models").getJSONArray("alpha").getString(0).equals("two"),"file -> model");check(!imported.toString().contains("private-token")&&Files.readString(secrets.toPath()).contains("private-token"),"credentials separated");check(imported.getJSONObject("scenes").has("screen"),"scene preserved");
  MagpieSettings.sync(s,source,secrets);writes=s.writes;MagpieSettings.importFile(s,source,secrets);check(s.writes==writes,"import projection loop");
  // Independent offline edits: newer file wins, then newer setting wins.
  set(s,base(now-10000));file(source,"file wins","second-token","three");source.setLastModified(now-1000);MagpieSettings.reconcile(s,source,secrets);check(config(s).getJSONArray("providers").getJSONObject(0).getString("name").equals("file wins"),"offline file wins");
  JSONObject newer=base(now+10000);newer.getJSONArray("providers").getJSONObject(0).put("name","setting wins");set(s,newer);file(source,"stale file","old-token","old");source.setLastModified(now);MagpieSettings.reconcile(s,source,secrets);check(new JSONObject(Files.readString(source.toPath())).getJSONArray("providers").getJSONObject(0).getString("name").equals("setting wins"),"offline setting wins");
  String before=s.get(SystemAiSettings.CONFIG);writes=s.writes;MagpieSettings.reconcile(s,source,secrets);check(before.equals(s.get(SystemAiSettings.CONFIG))&&writes==s.writes,"restart imported own file");
  System.out.println("Magpie bidirectional, loop suppression, offline LWW and restart tests passed");
 }
}
