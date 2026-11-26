package org.agentos.lab;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host tests for the Gemini lane's pure rules (Codex → Magpie → agy acp). */
public final class AgyAcpPolicyTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static JSONObject acp(String json)throws Exception{return new JSONObject().put("id","p").put("acp",new JSONObject(json));}
 static boolean refused(JSONObject provider){try{AgyAcpPolicy.requireSafe(provider);return false;}catch(IOException e){return true;}}

 static void safeAgents()throws Exception{
  check(!refused(new JSONObject().put("id","plain").put("chat","https://example.test/v1")),"a provider without acp is not an agent");
  check(!refused(AgyAcpPolicy.provider(AgyAcpPolicy.DATA_BINARY)),"Taku's own provider (development agy)");
  check(!refused(AgyAcpPolicy.provider(AgyAcpPolicy.SYSTEM_BINARY)),"Taku's own provider (image agy)");
  check(!refused(acp("{}")),"agy found on Magpie's system PATH");
  check(!refused(acp("{\"binary\":\"/vendor/bin/agy\",\"cwd\":\"/data/local/agentos/acp/work\",\"search\":true}")),"system agy, private cwd, search");
 }

 static void unsafeAgents()throws Exception{
  // Settings.Global is shell-writable and Magpie runs as root: none of these may reach providers.json.
  String[] bad={
   "{\"binary\":\"/data/local/tmp/agy\"}",
   "{\"binary\":\"/sdcard/agy\"}",
   "{\"binary\":\"/data/local/agentos/acp/bin/../../tmp/agy\"}",
   "{\"binary\":\"/system/bin/\"}",
   "{\"binary\":\"agy\"}",
   "{\"binary\":\"/system_ext/bin//agy\"}",
   "{\"binary\":\"/system_ext/bin/./agy\"}",
   "{\"env\":[\"LD_PRELOAD=/data/local/tmp/x.so\"]}",
   "{\"home\":\"/data/local/tmp/profile\"}",
   "{\"home\":\"/data/local/agentos/magpie/../home\"}",
   "{\"cwd\":\"/\"}",
   "{\"appDataDir\":\"/abs\"}",
   "{\"appDataDir\":\"..\"}",
   "{\"appDataDir\":\"a/b\"}",
   "{\"search\":\"yes\"}",
   "{\"binary\":7}",
   "{\"args\":[\"--danger\"]}",
  };
  for(String json:bad)check(refused(acp(json)),"must refuse "+json);
  check(refused(new JSONObject().put("id","p").put("acp","agy")),"acp must be an object");
 }

 static void ensure()throws Exception{
  JSONObject config=new JSONObject().put("providers",new JSONArray().put(new JSONObject().put("id","alpha")))
   .put("models",new JSONObject().put("alpha",new JSONArray().put("one"))).put("groups",new JSONArray()).put("scenes",new JSONObject().put("assistant","group/route-assistant"));
  String untouched=config.getJSONArray("groups").toString()+config.getJSONObject("scenes");
  check(AgyAcpPolicy.ensure(config,AgyAcpPolicy.DATA_BINARY),"first ensure adds the provider");
  JSONObject p=config.getJSONArray("providers").getJSONObject(1);
  check(p.getString("id").equals("taku-agy")&&p.getJSONObject("acp").getString("home").equals(AgyAcpPolicy.HOME),"provider added");
  check(config.getJSONObject("models").getJSONArray("taku-agy").getString(0).equals(AgyAcpPolicy.DEFAULT_MODEL),"default model picked");
  check(!AgyAcpPolicy.ensure(config,AgyAcpPolicy.DATA_BINARY),"ensure is idempotent");
  check(config.getJSONArray("providers").length()==2,"no duplicate");
  // A tampered record is repaired; a renamed one keeps its name; picks are kept.
  p.put("name","My Gemini").getJSONObject("acp").put("binary","/data/local/tmp/agy").put("env",new JSONArray().put("X=1"));
  config.getJSONObject("models").put("taku-agy",new JSONArray().put("gemini-4-neon"));
  check(AgyAcpPolicy.ensure(config,AgyAcpPolicy.SYSTEM_BINARY),"repair reported");
  check(p.getString("name").equals("My Gemini"),"user name kept");
  check(p.getJSONObject("acp").getString("binary").equals(AgyAcpPolicy.SYSTEM_BINARY)&&!p.getJSONObject("acp").has("env"),"acp repaired");
  check(config.getJSONObject("models").getJSONArray("taku-agy").getString(0).equals("gemini-4-neon"),"model pick kept");
  check(AgyAcpPolicy.safe(p),"repaired provider is safe");
  check(untouched.equals(config.getJSONArray("groups").toString()+config.getJSONObject("scenes")),"routes untouched");
  check(config.getJSONArray("providers").getJSONObject(0).toString().equals("{\"id\":\"alpha\"}"),"other provider untouched");
 }

 static void signIn()throws Exception{
  String link="https://accounts.google.com/o/oauth2/auth?client_id=x&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&state=s";
  check(link.equals(AgyAcpPolicy.signInUrl("Open this link to sign in: "+link)),"link in a sentence");
  check(link.equals(AgyAcpPolicy.signInUrl("<"+link+">.")),"link in brackets");
  check(AgyAcpPolicy.signInUrl("see https://antigravity.google/docs then")==null,"other hosts ignored");
  check(AgyAcpPolicy.signInUrl("https://accounts.google.com.evil.example/x")==null,"look-alike host ignored");
  check(AgyAcpPolicy.signInUrl("http://accounts.google.com/x")==null,"plain http ignored");
  check(AgyAcpPolicy.signInUrl("no link here")==null,"no link");
  check(AgyAcpPolicy.code("  4/0AbCdEf  ").equals("4/0AbCdEf"),"code trimmed");
  check(AgyAcpPolicy.code("https://antigravity.google/oauth-callback?code=4/0x&state=s").startsWith("https://"),"landed URL accepted");
  for(String bad:new String[]{"","   ","a b","a\nb","a\u0000b"}){
   try{AgyAcpPolicy.code(bad);throw new AssertionError("must refuse code "+bad.replace("\n","\\n"));}catch(IOException expected){}
  }
  try{AgyAcpPolicy.code("x".repeat(AgyAcpPolicy.MAX_CODE+1));throw new AssertionError("oversized code");}catch(IOException expected){}
 }

 static void status()throws Exception{
  JSONObject ready=AgyAcpPolicy.status(200,"{\"state\":\"ready\",\"binary\":\"/data/local/agentos/acp/bin/agy\",\"home\":\"/data/local/agentos/magpie/agy-profile\","
   +"\"models\":[\"gemini-3.8-flash\",\"gemini-4-neon\",\"bad model\"],\"efforts\":[\"low\",\"high\"],\"tier\":{\"name\":\"Google AI Ultra\",\"id\":\"g1-ultra-tier\"},"
   +"\"login\":[\"/data/local/agentos/acp/bin/agy\",\"acp\",\"--login\"],\"detail\":\"agy said things\",\"rests\":[{\"why\":\"failQuota\",\"status\":429,\"until\":\"2026-11-26T00:00:00Z\",\"by\":\"resets\"}]}");
  check(ready.getString("state").equals("ready"),"ready");
  check(ready.getJSONArray("models").length()==2,"invalid model names dropped");
  check(ready.getString("plan").equals("Google AI Ultra"),"plan");
  check(ready.getString("restingUntil").equals("2026-11-26T00:00:00Z"),"rest");
  String text=ready.toString();
  check(!text.contains("/data/local")&&!text.contains("login")&&!text.contains("agy said"),"paths, login command and agy's words stay out: "+text);
  check(AgyAcpPolicy.status(200,"{\"state\":\"needs_login\"}").getString("state").equals("needs_login"),"needs_login");
  check(AgyAcpPolicy.status(200,"{\"state\":\"surprise\"}").getString("state").equals("unavailable"),"unknown state");
  check(AgyAcpPolicy.status(200,"not json").getString("state").equals("unavailable"),"bad body");
  check(AgyAcpPolicy.status(404,"404 page not found").getString("state").equals("unsupported"),"older Magpie");
  check(AgyAcpPolicy.status(404,"{\"error\":\"no agy-acp provider taku-agy\"}").getString("state").equals("not_configured"),"provider not projected yet");
  check(AgyAcpPolicy.status(502,"").getString("state").equals("unavailable"),"gateway error");
 }

 public static void main(String[] args)throws Exception{
  safeAgents();unsafeAgents();ensure();signIn();status();
  System.out.println("AgyAcpPolicyTest passed");
 }
}
