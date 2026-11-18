package org.agentos.lab;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
public final class MagpieMigrationTest {
 static final class Store implements SystemAiSettings.Store {
  final Map<String,String> data=new HashMap<>();File secretFile;boolean failed;
  public String get(String k){return data.get(k);}public void delete(String k){data.remove(k);}public Map<String,String> list(){return data;}
  public void put(String k,String v)throws Exception{if(k.equals(MagpieSettings.PROVIDERS)){if(!secretFile.exists()||!Files.readString(secretFile.toPath()).contains("fixture-balance"))throw new AssertionError("credentials must be persisted first");if(failed){failed=false;throw new Exception("synthetic write failure");}}data.put(k,v);}
 }
 static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
 public static void main(String[]args)throws Exception{
  File dir=Files.createTempDirectory("magpie-migration-test").toFile(),source=new File(dir,"providers.json"),secrets=new File(dir,"credentials.json");
  String provider="{\"id\":\"fixture\",\"credentialRef\":\"\",\"key\":\"fixture-key\",\"balanceToken\":\"fixture-balance\",\"proxy\":\"http://user:fixture-password@proxy.example:8080\",\"models\":[\"model\"]}";
  Files.writeString(source.toPath(),"{\"providers\":["+provider+"],\"groups\":[]}");Store store=new Store();store.secretFile=secrets;store.failed=true;
  try{MagpieSettings.migrate(store,source,secrets);throw new AssertionError("failure not injected");}catch(Exception expected){check(!"1".equals(store.get(MagpieSettings.MIGRATED)),"failed migration marked complete");}
  MagpieSettings.migrate(store,source,secrets);check("1".equals(store.get(MagpieSettings.MIGRATED)),"marker");
  String publicValue=new JSONObject(store.get(SystemAiSettings.CONFIG)).getJSONArray("providers").toString();for(String secret:new String[]{"fixture-key","fixture-balance","fixture-password"})check(!publicValue.contains(secret),"new migration leaked secret");
  JSONObject publicProvider=new JSONArray(publicValue).getJSONObject(0);check("fixture".equals(publicProvider.getString("credentialRef")),"empty credential reference repaired");JSONObject saved=new JSONObject(Files.readString(secrets.toPath())).getJSONObject("fixture");publicProvider.remove("credentialRef");MagpieProviderSecrets.restore(publicProvider,saved);check(publicProvider.getString("proxy").contains("fixture-password"),"restore private proxy");
  check(store.get(MagpieSettings.PROVIDERS)==null,"legacy provider key removed");
  SystemAiSettings.Store view=SystemAiSettings.unified(store);
  check(view.get(MagpieSettings.PROVIDERS).equals(publicValue),"unified read");
  view.put(MagpieSettings.GROUPS,"[]");check(new JSONObject(store.get(SystemAiSettings.CONFIG)).getJSONArray("groups").length()==0,"unified write");
  MagpieSettings.requirePublicProvider(new JSONArray(publicValue).getJSONObject(0));boolean rejected=false;try{MagpieSettings.requirePublicProvider(new JSONObject(provider));}catch(java.io.IOException e){rejected=true;}check(rejected,"reject raced credential-bearing Settings read");
  check("1".equals(store.get(MagpieSettings.MIGRATED)),"existing marker preserved");System.out.println("Magpie migration tests passed");
 }
}
