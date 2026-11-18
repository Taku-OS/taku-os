package org.agentos.lab;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
public final class MagpieProjectionRaceTest {
 static final class Store implements SystemAiSettings.Store {
  final Map<String,String> values=new HashMap<>();int reads,changeAt;
  public String get(String k)throws Exception{if(k.equals(SystemAiSettings.CONFIG)&&++reads==changeAt){JSONObject c=new JSONObject(values.get(k));c.put("groups",new JSONArray().put(new JSONObject().put("id","new")));values.put(k,c.toString());}return values.get(k);}
  public void put(String k,String v){values.put(k,v);}public void delete(String k){values.remove(k);}public Map<String,String> list(){return values;}
 }
 public static void main(String[]args)throws Exception{
  File dir=Files.createTempDirectory("magpie-race").toFile(),source=new File(dir,"providers.json"),secrets=new File(dir,"secrets.json");
  Files.writeString(source.toPath(),"{\"providers\":[],\"groups\":[{\"id\":\"old\"}]}");String original=Files.readString(source.toPath());
  Store s=new Store();s.values.put(SystemAiSettings.CONFIG,new JSONObject().put("version",1).put("updated_at",1).put("updated_by","test").put("providers",new JSONArray()).put("models",new JSONObject()).put("groups",new JSONArray().put(new JSONObject().put("id","old"))).put("scenes",new JSONObject()).toString());
  s.changeAt=4;try{MagpieSettings.sync(s,source,secrets);throw new AssertionError("race accepted");}catch(IOException expected){if(!expected.getMessage().contains("changed during projection"))throw expected;}
  if(!original.equals(Files.readString(source.toPath())))throw new AssertionError("race overwrote file");
  s.changeAt=0;MagpieSettings.sync(s,source,secrets);if(!Files.readString(source.toPath()).contains("new"))throw new AssertionError("retry lost change");
  System.out.println("Magpie projection race tests passed");
 }
}
