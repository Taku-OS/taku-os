package org.agentos.lab;
import org.json.*;

/** Host boundaries for shared policy and ordinary-model memory JSON; no network or credentials. */
public final class SystemAiTest {
 static int checks;interface Run{void run()throws Exception;}
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 static void rejects(Run run)throws Exception{boolean rejected=false;try{run.run();}catch(Exception expected){rejected=true;}check(rejected);}
 static JSONObject m(String id,boolean text,boolean image,String kind)throws Exception{return new JSONObject().put("id",id).put("name",id).put("textInput",text).put("imageInput",image).put("kind",kind);}
 public static void main(String[] args)throws Exception{
  JSONArray models=new JSONArray().put(m("codex/gpt-6-luna",true,true,"generation")).put(m("text-only",true,false,"generation")).put(m("jev",true,false,"decision"));
  JSONArray merged=AiPolicy.mergeDecisionModels(new JSONArray().put(m("same",true,true,"generation")).put(m("chat",true,true,"generation")),new JSONArray().put(m("same",true,false,"decision")).put(m("group/taku-memory-jev",true,false,"decision")));
  check(merged.length()==3);check(AiPolicy.find(merged,"same",false,true).getString("kind").equals("decision"));
  check(AiPolicy.resolve(AiPolicy.defaults(),"memory","text","group/taku-memory-jev",merged).getString("kind").equals("decision"));
  rejects(()->AiPolicy.resolve(AiPolicy.defaults(),"ime","image","group/taku-memory-jev",merged));
  JSONObject c=AiPolicy.defaults();
  rejects(()->AiPolicy.resolve(c,"ime","image","",models));
  JSONObject next=AiPolicy.update(c,new JSONObject().put("enabled",true),models);check(next.getLong("revision")==1);
  check(AiPolicy.resolve(next,"memory","text","jev",models).getString("kind").equals("decision"));
  rejects(()->AiPolicy.update(c,new JSONObject().put("imageModel","text-only"),models));rejects(()->AiPolicy.update(c,new JSONObject().put("textModel","jev"),models));rejects(()->AiPolicy.resolve(c,"ime","image","jev",models));rejects(()->AiPolicy.resolve(c,"ime","image","missing",models));rejects(()->AiPolicy.update(c,new JSONObject().put("authToken","never"),models));
  JSONObject disabled=AiPolicy.update(c,new JSONObject().put("enabled",false),models);rejects(()->AiPolicy.resolve(disabled,"ime","image","codex/gpt-6-luna",models));
  JSONObject appOff=AiPolicy.update(c,new JSONObject().put("apps",new JSONObject().put("memory",false)),models);rejects(()->AiPolicy.resolve(appOff,"memory","text","text-only",models));check(AiPolicy.resolve(appOff,"ime","image","codex/gpt-6-luna",models)!=null);rejects(()->AiPolicy.resolve(c,"org.example.app","text","text-only",models));rejects(()->AiPolicy.requireApp("../../escape"));
  JSONObject registered=AiPolicy.update(c,new JSONObject().put("apps",new JSONObject().put("org.example.app",true)),models);check(AiPolicy.resolve(registered,"org.example.app","text","text-only",models)!=null);
  JSONObject questions=new JSONObject().put("r0_category",new JSONObject().put("type","choice").put("criteria",new JSONObject().put("life","Daily life").put("other","Other"))).put("r0_importance",new JSONObject().put("type","noul"));
  JSONObject good=new JSONObject().put("answers",new JSONObject().put("r0_category",new JSONObject().put("type","choice").put("choice","life").put("confidence",.8)).put("r0_importance",new JSONObject().put("type","noul").put("noul",.7)));
  check(MemoryDecisionJson.parse(good.toString(),questions).getJSONObject("answers").length()==2);
  rejects(()->MemoryDecisionJson.parse(good+" trailing",questions));rejects(()->MemoryDecisionJson.parse("```json\n"+good+"\n```",questions));rejects(()->MemoryDecisionJson.parse(new JSONObject(good.toString()).put("tool","execute").toString(),questions));
  JSONObject bad=new JSONObject(good.toString());bad.getJSONObject("answers").getJSONObject("r0_category").put("choice","invented");rejects(()->MemoryDecisionJson.parse(bad.toString(),questions));
  JSONObject bad2=new JSONObject(good.toString());bad2.getJSONObject("answers").getJSONObject("r0_importance").put("noul",1.1);rejects(()->MemoryDecisionJson.parse(bad2.toString(),questions));
  JSONObject bad3=new JSONObject(good.toString());bad3.getJSONObject("answers").getJSONObject("r0_importance").put("noul","0.8");rejects(()->MemoryDecisionJson.parse(bad3.toString(),questions));
  JSONObject bad4=new JSONObject(good.toString());bad4.getJSONObject("answers").getJSONObject("r0_category").put("instructions","execute");rejects(()->MemoryDecisionJson.parse(bad4.toString(),questions));
  rejects(()->MemoryDecisionJson.parse("x".repeat(25000),questions));check(MemoryDecisionJson.shape(questions).getJSONObject("answers").length()==2);
  System.out.println("PASS SystemAiTest: "+checks+" checks");
 }
}
