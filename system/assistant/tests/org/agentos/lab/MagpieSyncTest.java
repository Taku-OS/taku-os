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
 static void typeSafeEndpointRepair()throws Exception{
  File dir=Files.createTempDirectory("magpie-typesafe").toFile(),source=new File(dir,"providers.json"),secrets=new File(dir,"secrets.json");
  Store store=new Store();JSONObject original=base(System.currentTimeMillis());
  JSONObject fresh=new JSONObject().put("id","taku-jev-typesafe").put("preset","typesafe");
  MagpieSettings.completeDecisionEndpoint(fresh);
  check(fresh.getString("decide").equals("https://api.typesafe.ai/v1"),"new TypeSafe endpoint missing");
  JSONObject legacy=new JSONObject().put("id","taku-jev-typesafe").put("preset","typesafe").put("credentialRef","saved-key");
  JSONObject custom=new JSONObject().put("id","custom").put("preset","typesafe").put("decide","https://custom.example/v1");
  original.getJSONArray("providers").put(legacy).put(custom);
  String groups=original.getJSONArray("groups").toString(),scenes=original.getJSONObject("scenes").toString(),models=original.getJSONObject("models").toString();
  set(store,original);Files.writeString(secrets.toPath(),"{\"saved-key\":{\"key\":\"synthetic-key\"}}");String secretBefore=Files.readString(secrets.toPath());
  MagpieSettings.sync(store,source,secrets);
  JSONObject saved=config(store),runtime=new JSONObject(Files.readString(source.toPath()));
  check(saved.getJSONArray("providers").length()==3,"repair duplicated provider");
  check(saved.getJSONArray("providers").getJSONObject(1).getString("decide").equals("https://api.typesafe.ai/v1"),"canonical Settings not repaired");
  JSONObject projected=runtime.getJSONArray("providers").getJSONObject(1);
  check(projected.getString("decide").equals("https://api.typesafe.ai/v1")&&projected.getString("key").equals("synthetic-key"),"runtime endpoint or credential missing");
  check(!saved.toString().contains("synthetic-key")&&Files.readString(secrets.toPath()).equals(secretBefore),"repair changed or exposed credentials");
  check(saved.getJSONArray("providers").getJSONObject(2).getString("decide").equals("https://custom.example/v1"),"custom endpoint overwritten");
  check(!saved.getJSONArray("providers").getJSONObject(0).has("decide"),"unrelated provider changed");
  check(saved.getJSONArray("groups").toString().equals(groups)&&saved.getJSONObject("scenes").toString().equals(scenes)&&saved.getJSONObject("models").toString().equals(models),"routes or model choices changed");
  int writes=store.writes;String stable=store.get(SystemAiSettings.CONFIG);MagpieSettings.sync(store,source,secrets);MagpieSettings.importFile(store,source,secrets);
  check(writes==store.writes&&stable.equals(store.get(SystemAiSettings.CONFIG)),"repair is not idempotent");
  // A newer legacy runtime file imported at startup must repair the same authoritative data.
  runtime.getJSONArray("providers").getJSONObject(1).remove("decide");Files.writeString(source.toPath(),runtime.toString());
  MagpieSettings.importFile(store,source,secrets);MagpieSettings.sync(store,source,secrets);
  check(config(store).getJSONArray("providers").getJSONObject(1).getString("decide").equals("https://api.typesafe.ai/v1"),"runtime import bypassed repair");
 }
 /** Settings is shell-writable and Magpie runs as root: an unsafe acp agent never reaches providers.json. */
 static void unsafeAgentLeftOut()throws Exception{
  File dir=Files.createTempDirectory("magpie-acp").toFile(),source=new File(dir,"providers.json"),secrets=new File(dir,"secrets.json");
  Store store=new Store();JSONObject c=base(System.currentTimeMillis());
  AgyAcpPolicy.ensure(c,AgyAcpPolicy.DATA_BINARY);
  c.getJSONArray("providers").put(new JSONObject().put("id","evil").put("acp",new JSONObject().put("binary","/data/local/tmp/agy")));
  c.getJSONArray("providers").put(new JSONObject().put("id","evil-env").put("acp",new JSONObject().put("env",new JSONArray().put("LD_PRELOAD=/data/local/tmp/x.so"))));
  set(store,c);MagpieSettings.sync(store,source,secrets);
  JSONArray projected=new JSONObject(Files.readString(source.toPath())).getJSONArray("providers");
  Set<String> ids=new HashSet<>();for(int i=0;i<projected.length();i++)ids.add(projected.getJSONObject(i).getString("id"));
  check(ids.equals(Set.of("alpha","taku-agy")),"only safe providers projected: "+ids);
  JSONObject agy=projected.getJSONObject(1);
  check(agy.getJSONObject("acp").getString("binary").equals(AgyAcpPolicy.DATA_BINARY)&&agy.getJSONArray("models").getString(0).equals(AgyAcpPolicy.DEFAULT_MODEL),"agy provider projected with its model");
  check(config(store).getJSONArray("providers").length()==4,"Settings keeps what its owner wrote");
 }
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
  typeSafeEndpointRepair();
  unsafeAgentLeftOut();
  System.out.println("Magpie bidirectional, loop suppression, offline LWW, restart, TypeSafe endpoint repair and acp agent safety tests passed");
 }
}
