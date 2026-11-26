package org.agentos.lab;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Gemini lane of the main assistant: Codex keeps running every turn and talks to the local
 * Magpie gateway; Magpie answers the {@code taku-agy} provider's models by running {@code agy acp}
 * (Google Antigravity CLI) in its own profile. Taku never reads agy's sign-in: Magpie's
 * {@code agy-acp login} runs agy's own Google sign-in, and its status endpoint says whether the
 * profile is signed in. Rules shared with tests are in {@link AgyAcpPolicy}.
 *
 * <p>Broker op {@code agyGemini}: {@code read}, {@code refresh}, {@code configure}, {@code signIn},
 * {@code signInCode} (the code Google's page shows, or the URL it landed on), {@code signInCancel};
 * {@code use} is handled by {@link NativeBridge}, which queues the connection switch.
 */
final class AgyGemini {
 private AgyGemini(){}
 static final String STATUS_URL="http://127.0.0.1:3425/_magpie/agy-acp/status?provider="+AgyAcpPolicy.PROVIDER;
 static final long STATUS_FRESH_MS=10*60_000L, SIGN_IN_BUDGET_MS=10*60_000L;

 // Status: agy's probe starts the agent (seconds, ~240MB), so it runs in the background and is cached.
 private static JSONObject status;
 private static long statusAt;
 private static boolean probing;
 // Sign-in: one at a time. The URL is kept in memory for the settings screen only; never logged or written.
 private static Process login;
 private static String phase="idle", url="", error="";
 private static long loginStarted;

 static String binary(){return new File(AgyAcpPolicy.SYSTEM_BINARY).canExecute()?AgyAcpPolicy.SYSTEM_BINARY:AgyAcpPolicy.DATA_BINARY;}
 static boolean installed(){return new File(binary()).canExecute();}

 static JSONObject control(JSONObject input)throws Exception{
  switch(input.optString("action","read")){
   case "read":return read(false);
   case "refresh":return read(true);
   case "configure":configure();return read(true);
   case "signIn":signIn();return read(false);
   case "signInCode":signInCode(input.optString("code"));input.remove("code");return read(false);
   case "signInCancel":cancel();return read(false);
   default:throw new IOException("UNKNOWN_METHOD");
  }
 }

 /** Adds or repairs the {@code taku-agy} provider in Settings and projects it to Magpie. */
 static void configure()throws Exception{
  if(!installed())throw new IOException("AGY_NOT_INSTALLED");
  synchronized(MagpieSettings.class){
   MagpieSettings.migrate();
   String snapshot=SystemAi.SETTINGS.get(SystemAiSettings.CONFIG);
   if(snapshot==null)throw new IOException("missing unified configuration");
   JSONObject config=new JSONObject(snapshot);
   if(AgyAcpPolicy.ensure(config,binary())){
    if(!snapshot.equals(SystemAi.SETTINGS.get(SystemAiSettings.CONFIG)))throw new IOException("SETTINGS_CHANGED");
    config.put("updated_at",System.currentTimeMillis()).put("updated_by","taku");
    SystemAi.SETTINGS.put(SystemAiSettings.CONFIG,config.toString());
   }
   MagpieSettings.sync();
  }
  synchronized(AgyGemini.class){statusAt=0;}
 }

 static boolean configured()throws Exception{
  String raw=SystemAi.SETTINGS.get(SystemAiSettings.CONFIG);if(raw==null)return false;
  JSONArray providers=new JSONObject(raw).optJSONArray("providers");if(providers==null)return false;
  for(int i=0;i<providers.length();i++){JSONObject p=providers.getJSONObject(i);if(AgyAcpPolicy.PROVIDER.equals(p.optString("id")))return p.has("acp")&&AgyAcpPolicy.safe(p);}
  return false;
 }

 static synchronized JSONObject read(boolean refresh)throws Exception{
  boolean installed=installed(),configured=configured();
  if(installed&&configured&&!probing&&(refresh||status==null||System.currentTimeMillis()-statusAt>STATUS_FRESH_MS))probe();
  String state=!installed?"agy_missing":!configured?"not_configured":status==null?"checking":status.getString("state");
  JSONObject view=status==null?AgyAcpPolicy.status(503,""):new JSONObject(status.toString());
  view.put("state",state).put("checking",probing).put("installed",installed).put("configured",configured)
   .put("provider",AgyAcpPolicy.PROVIDER).put("defaultModel",AgyAcpPolicy.DEFAULT_MODEL);
  String assistant="";try{if(ModelConnection.local())assistant=ModelConnection.settings().optString("model");}catch(Exception ignored){}
  view.put("assistantModel",assistant).put("active",usesAgy(assistant));
  expireSignIn();
  return view.put("signIn",new JSONObject().put("phase",phase).put("url","pending".equals(phase)?url:"").put("error",error));
 }

 /** Whether the assistant's route is the Taku agy provider, directly or through its routing group. */
 static boolean usesAgy(String assistant)throws Exception{
  if(assistant.startsWith(AgyAcpPolicy.PROVIDER+"/"))return true;
  if(!assistant.startsWith("group/"))return false;
  String raw=SystemAi.SETTINGS.get(SystemAiSettings.CONFIG);if(raw==null)return false;
  JSONArray groups=new JSONObject(raw).optJSONArray("groups");if(groups==null)return false;
  for(int i=0;i<groups.length();i++){JSONObject g=groups.getJSONObject(i);if(!assistant.substring(6).equals(g.optString("id")))continue;
   JSONArray members=g.optJSONArray("members");return members!=null&&members.length()>0&&members.optString(0).startsWith(AgyAcpPolicy.PROVIDER+"/");}
  return false;
 }

 private static void probe(){
  probing=true;
  Thread worker=new Thread(()->{
   JSONObject next=null;
   try{next=fetchStatus();}catch(Exception e){try{next=AgyAcpPolicy.status(503,"");}catch(Exception impossible){}}
   finally{synchronized(AgyGemini.class){if(next!=null)status=next;statusAt=System.currentTimeMillis();probing=false;}}
  },"agy-status");
  worker.setDaemon(true);worker.start();
 }

 static JSONObject fetchStatus()throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL(STATUS_URL).openConnection(Proxy.NO_PROXY);
  // Magpie bounds its own probe at 90 s (starting agy and asking for a session).
  c.setConnectTimeout(1500);c.setReadTimeout(100_000);
  c.setRequestProperty("Authorization","Bearer taku-android");c.setRequestProperty("User-Agent","taku-android/1");
  try{
   int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();
   String body="";if(in!=null)try(InputStream s=in){body=NativeBridge.read(s,262144);}
   return AgyAcpPolicy.status(code,body);
  }finally{c.disconnect();}
 }

 /**
  * Starts {@code magpie agy-acp login} for the Taku provider. agy prints Google's sign-in link and
  * then reads, from stdin, the code that Google's page shows (or the URL the browser landed on).
  */
 static void signIn()throws Exception{
  if(!configured())configure();
  synchronized(AgyGemini.class){
   expireSignIn();
   if(login!=null)return;
   ProcessBuilder builder=new ProcessBuilder("/system_ext/bin/magpie","agy-acp","login","--provider",AgyAcpPolicy.PROVIDER).redirectErrorStream(true);
   // agy prints the link instead of opening a desktop browser; the settings screen opens it.
   builder.environment().put("BROWSER","true");
   Process process=builder.start();
   login=process;phase="starting";url="";error="";loginStarted=System.currentTimeMillis();
   Thread reader=new Thread(()->follow(process),"agy-sign-in");reader.setDaemon(true);reader.start();
  }
 }

 private static void follow(Process process){
  try(BufferedReader out=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
   String line;
   while((line=out.readLine())!=null){
    String found=AgyAcpPolicy.signInUrl(line);
    if(found!=null)synchronized(AgyGemini.class){if(login==process&&url.isEmpty()){url=found;phase="pending";}}
   }
  }catch(IOException ignored){}
  int exit;
  try{exit=process.waitFor();}catch(InterruptedException e){exit=-1;}
  synchronized(AgyGemini.class){
   if(login!=process)return;
   login=null;url="";
   if(exit==0){phase="done";error="";statusAt=0;}
   else if(!"cancelled".equals(phase)){phase="failed";error="SIGN_IN_FAILED";}
  }
  // A finished sign-in changes what agy's status says.
  try{read(true);}catch(Exception ignored){}
 }

 static void signInCode(String raw)throws Exception{
  String code=AgyAcpPolicy.code(raw);
  synchronized(AgyGemini.class){
   expireSignIn();
   if(login==null||!"pending".equals(phase))throw new IOException("SIGN_IN_NOT_PENDING");
   OutputStream in=login.getOutputStream();
   in.write((code+"\n").getBytes(StandardCharsets.UTF_8));in.flush();
   phase="verifying";
  }
 }

 static synchronized void cancel(){
  if(login==null)return;
  phase="cancelled";url="";error="";
  Process process=login;
  try{process.getOutputStream().close();}catch(IOException ignored){}
  process.destroy();
  try{if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){process.destroyForcibly();}
 }

 private static synchronized void expireSignIn(){
  if(login!=null&&System.currentTimeMillis()-loginStarted>SIGN_IN_BUDGET_MS){cancel();phase="failed";error="SIGN_IN_TIMEOUT";}
 }

 /**
  * Before the assistant switches to [model] of the Taku provider: the provider is configured,
  * agy's profile is signed in and Magpie's Codex catalog lists the model. Returns its catalog id.
  */
 static String prepareUse(String model)throws Exception{
  String picked=model==null||model.isEmpty()?AgyAcpPolicy.DEFAULT_MODEL:model;
  if(!AgyAcpPolicy.validModel(picked))throw new IOException("INVALID_ARGUMENT");
  if(!configured())configure();
  // agy's probe can take longer than the caller's socket allows: use the cached result and, when
  // there is none yet, start one and let the screen retry once it reads "ready".
  JSONObject known;boolean checking;synchronized(AgyGemini.class){known=status;checking=probing;}
  if(known==null||checking){read(known==null);throw new IOException("AGY_STATUS_CHECKING");}
  String state=known.optString("state");
  if(!"ready".equals(state))throw new IOException("needs_login".equals(state)?"AGY_SIGN_IN_REQUIRED":"unsupported".equals(state)?"MAGPIE_AGY_UNSUPPORTED":"AGY_UNAVAILABLE");
  JSONArray offered=known.getJSONArray("models");
  if(offered.length()>0){boolean ok=false;for(int i=0;i<offered.length();i++)ok|=picked.equals(offered.getString(i));if(!ok)throw new IOException("AGY_MODEL_UNAVAILABLE");}
  ensureModelPicked(picked);
  String id=AgyAcpPolicy.modelId(picked);
  // Magpie reloads providers.json on its own; wait briefly for the catalog to list the model.
  long until=System.currentTimeMillis()+8_000;
  while(true){
   JSONArray catalog=ModelConnection.publicModels(ModelConnection.catalog());
   for(int i=0;i<catalog.length();i++)if(id.equals(catalog.getJSONObject(i).optString("id")))return id;
   if(System.currentTimeMillis()>until)throw new IOException("AGY_MODEL_UNAVAILABLE");
   Thread.sleep(500);
  }
 }

 /** Keeps [model] among the Taku provider's model picks, so Magpie serves and lists it. */
 static void ensureModelPicked(String model)throws Exception{
  synchronized(MagpieSettings.class){
   String snapshot=SystemAi.SETTINGS.get(SystemAiSettings.CONFIG);JSONObject config=new JSONObject(snapshot);
   JSONObject models=config.getJSONObject("models");JSONArray picks=models.optJSONArray(AgyAcpPolicy.PROVIDER);if(picks==null)picks=new JSONArray();
   for(int i=0;i<picks.length();i++)if(model.equals(picks.optString(i)))return;
   models.put(AgyAcpPolicy.PROVIDER,picks.put(model));
   if(!snapshot.equals(SystemAi.SETTINGS.get(SystemAiSettings.CONFIG)))throw new IOException("SETTINGS_CHANGED");
   config.put("updated_at",System.currentTimeMillis()).put("updated_by","taku");
   SystemAi.SETTINGS.put(SystemAiSettings.CONFIG,config.toString());MagpieSettings.sync();
  }
 }
}
