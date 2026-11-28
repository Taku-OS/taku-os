// SPDX-License-Identifier: Apache-2.0
package org.agentos.lab;

import android.net.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Root-owned settings subscription for the separately signed audio host. No credentials in snapshots. */
final class AudioConfigBridge {
 private static final Map<LocalSocket,BlockingQueue<String>> subscribers=new ConcurrentHashMap<>();
 private static final String DEFAULT_LIVE_PROFILE="fal-grok";
 private static final String GEMINI_LIVE_PROFILE="gemini-live";
 private static final String MOSS_LIVE_PROFILE="moss-live";
 private static final Set<String> LIVE_PROFILES=Set.of(DEFAULT_LIVE_PROFILE,GEMINI_LIVE_PROFILE,MOSS_LIVE_PROFILE);
 private static boolean started;
 static synchronized void start()throws Exception {
  if(started)return;LocalServerSocket server=new LocalServerSocket("taku-audio-config");started=true;
  Thread accept=new Thread(()->{while(true)try{LocalSocket socket=server.accept();Thread worker=new Thread(()->serve(socket),"audio-config-peer");worker.setDaemon(true);worker.start();}catch(Exception e){break;}},"audio-config-listener");accept.setDaemon(true);accept.start();
 }
 private static boolean trusted(int uid)throws Exception {
  Object pm=Class.forName("android.app.ActivityThread").getDeclaredMethod("getPackageManager").invoke(null);
  Class<?> api=Class.forName("android.content.pm.IPackageManager");
  String[] packages=(String[])api.getMethod("getPackagesForUid",int.class).invoke(pm,uid);
  if(packages==null||packages.length!=1||!packages[0].equals("org.agentos.audio"))return false;
  for(Method method:api.getMethods())if(method.getName().equals("checkUidSignatures")) {
   Object result=method.getParameterCount()==2?method.invoke(pm,uid,1000):method.invoke(pm,uid,1000,0);
   return ((Integer)result)==0;
  }
  return false;
 }
 private static JSONObject audioSettings()throws Exception {
  File file=new File(SystemAi.ROOT,"audio-settings.json");
  return file.isFile()?new JSONObject(TaskBroker.read(file)):new JSONObject().put("revision",0).put("profiles",new JSONObject());
 }
 private static final String DICTATION_SCOPE="assistant-entry";
 private static final Set<String> DICTATION_PROFILES=Set.of("zipformer","fal-whisper");
 private static final Set<String> DICTATION_LANGUAGES=Set.of("system","zh-CN","en-US");
 /** Raw saved scope map for the audio host; absent means defaults, a wrong shape is forwarded intact. */
 private static Object dictation(JSONObject settings){return settings.has("dictation")?settings.opt("dictation"):new JSONObject();}
 private static JSONObject dictationProfile(JSONObject request)throws Exception {
  Object primary=request.opt("primaryProfileId");
  if(!DICTATION_SCOPE.equals(request.optString("scope"))||!(primary instanceof String)||!DICTATION_PROFILES.contains(primary))
   throw new AudioConfigFailure("INVALID_ARGUMENT","Select an available home dictation backend and settings scope");
  Object rawFallback=request.opt("fallbackProfileId");
  if(!(rawFallback instanceof String)&&!JSONObject.NULL.equals(rawFallback))
   throw new AudioConfigFailure("INVALID_ARGUMENT","Select an available fallback backend");
  String fallback=rawFallback instanceof String?(String)rawFallback:null;
  if(fallback!=null&&(!DICTATION_PROFILES.contains(fallback)||fallback.equals(primary)))
   throw new AudioConfigFailure("INVALID_ARGUMENT","The fallback backend must differ from the primary backend");
  Object language=request.opt("language");
  if(!(language instanceof String)||!DICTATION_LANGUAGES.contains(language))
   throw new AudioConfigFailure("INVALID_ARGUMENT","Select an available recognition language");
  if("fal-whisper".equals(primary)||"fal-whisper".equals(fallback)) {
   JSONObject fal=NativeBridge.byok.falSettings();
   if(!fal.optBoolean("configured"))throw new AudioConfigFailure("PROVIDER_NOT_CONFIGURED","First add or save your fal Key again in Taku Audio");
  }
  return new JSONObject().put("primaryProfileId",primary).put("fallbackProfileId",fallback==null?JSONObject.NULL:fallback).put("language",language);
 }
 /** Same root monitor as reading writes; BYOK reads stay unsynchronized so no lock order reverses. */
 private static synchronized void writeDictation(JSONObject request,boolean reset)throws Exception {
  if(!DICTATION_SCOPE.equals(request.optString("scope")))
   throw new AudioConfigFailure("INVALID_ARGUMENT","Select an available home dictation settings scope");
  JSONObject current;
  try {current=audioSettings();current.getLong("revision");current.getJSONObject("profiles");}
  catch(Exception unreadable){throw new AudioConfigFailure("SETTINGS_UNREADABLE","Saved voice settings cannot be read; no configuration was changed");}
  Object existing=current.opt("dictation");
  JSONObject dictation;
  if(existing==null)dictation=new JSONObject();
  else if(existing instanceof JSONObject)dictation=(JSONObject)existing;
  else if(reset)throw new AudioConfigFailure("SETTINGS_UNREADABLE","Saved voice settings cannot be read; no configuration was changed");
  else dictation=new JSONObject();
  if(reset) {
   if(!dictation.has(DICTATION_SCOPE))return;
   dictation.remove(DICTATION_SCOPE);
  } else dictation.put(DICTATION_SCOPE,dictationProfile(request));
  current.put("dictation",dictation);
  current.put("revision",current.getLong("revision")+1);
  SystemAi.save(new File(SystemAi.ROOT,"audio-settings.json"),current);changed();
 }
 private static JSONObject readingProfile(JSONObject request,boolean reset)throws Exception {
  String language=request.getString("language");
  if(!Set.of("zh-CN","en-US").contains(language))throw new IOException("Select an available language");
  if(reset)return null;
  String voice=request.getString("voiceId");double speed=request.getDouble("speed");
  if(!voice.matches(java.util.regex.Pattern.quote(language)+"-local-[0-9]{1,3}")||!Double.isFinite(speed)||speed<.5||speed>2)throw new IOException("Select an available voice and speed");
  return new JSONObject().put("voiceId",voice).put("speed",speed);
 }
 private static synchronized void writeReading(String language,JSONObject profile)throws Exception {
  JSONObject current=audioSettings();
  if(profile==null)current.getJSONObject("profiles").remove(language);
  else current.getJSONObject("profiles").put(language,profile);
  current.put("revision",current.getLong("revision")+1);
  SystemAi.save(new File(SystemAi.ROOT,"audio-settings.json"),current);changed();
 }
 private static JSONObject configureReading(JSONObject request)throws Exception {
  JSONObject profile=readingProfile(request,request.optString("op").equals("resetReading"));
  writeReading(request.getString("language"),profile);return snapshot();
 }
 /**
  * Provider used while audio-settings.json has no liveProfileId. A device that already holds a
  * fal or Gemini key (and no MOSS key) keeps the provider it was using; everything else defaults
  * to MOSS-Live. The first credential save pins this value so adding a key never switches it.
  */
 static String defaultLiveProfile(ByokProviders byok) {
  if(byok.hasKeyFile(ByokProviders.MOSS_FILE))return MOSS_LIVE_PROFILE;
  if(byok.hasKeyFile("fal.json"))return DEFAULT_LIVE_PROFILE;
  if(byok.hasKeyFile(ByokProviders.GEMINI_FILE))return GEMINI_LIVE_PROFILE;
  return MOSS_LIVE_PROFILE;
 }
 /** Two-level standby for Live Talk; the saved value or these defaults, always within range. */
 static JSONObject standby(JSONObject audio)throws Exception {
  JSONObject saved=audio.optJSONObject("liveStandby");
  JSONObject result=new JSONObject().put("enabled",true).put("hotTimeoutSec",15).put("coldTimeoutSec",60).put("lockScreenWakeEnabled",true);
  if(saved==null)return result;
  // An unreadable saved value falls back to the defaults; it must never break the settings snapshot.
  try{return standbyValue(saved);}catch(AudioConfigFailure invalid){return result;}
 }
 private static JSONObject standbyValue(JSONObject request)throws Exception {
  Object enabled=request.opt("enabled"),hot=request.opt("hotTimeoutSec"),cold=request.opt("coldTimeoutSec"),lock=request.opt("lockScreenWakeEnabled");
  if(!(enabled instanceof Boolean)||!(lock instanceof Boolean)||!(hot instanceof Integer)||!(cold instanceof Integer)
    ||(Integer)hot<5||(Integer)hot>120||(Integer)cold<15||(Integer)cold>600)
   throw new AudioConfigFailure("INVALID_ARGUMENT","Standby needs on/off switches, a 5–120 s hot timeout and a 15–600 s cold timeout");
  return new JSONObject().put("enabled",enabled).put("hotTimeoutSec",hot).put("coldTimeoutSec",cold).put("lockScreenWakeEnabled",lock);
 }
 private static synchronized void configureStandby(JSONObject request)throws Exception {
  JSONObject value=standbyValue(request);
  JSONObject current;
  try {current=audioSettings();current.getLong("revision");current.getJSONObject("profiles");}
  catch(Exception unreadable){throw new AudioConfigFailure("SETTINGS_UNREADABLE","Saved voice settings cannot be read; no configuration was changed");}
  if(value.toString().equals(String.valueOf(current.optJSONObject("liveStandby"))))return;
  current.put("liveStandby",value).put("revision",current.getLong("revision")+1);
  SystemAi.save(new File(SystemAi.ROOT,"audio-settings.json"),current);changed();
 }
 private static JSONObject liveSettings(JSONObject audio,JSONObject fal)throws Exception {
  Object profileId=audio.has("liveProfileId")?audio.opt("liveProfileId"):defaultLiveProfile(NativeBridge.byok);
  if(!(profileId instanceof String)||!LIVE_PROFILES.contains(profileId))
   throw new AudioConfigFailure("SETTINGS_UNREADABLE","Select the Live Talk provider again");
  JSONObject gemini=NativeBridge.byok.geminiSettings(),moss=NativeBridge.byok.mossSettings();
  boolean configured=(GEMINI_LIVE_PROFILE.equals(profileId)?gemini:MOSS_LIVE_PROFILE.equals(profileId)?moss:fal).getBoolean("configured");
  return new JSONObject().put("profileId",profileId).put("configured",configured).put("gemini",gemini).put("moss",moss).put("standby",standby(audio));
 }
 /** Stores the effective default provider before a voice key changes which default applies. */
 static synchronized void pinLiveDefault()throws Exception {
  JSONObject current;
  try {current=audioSettings();current.getLong("revision");current.getJSONObject("profiles");}
  catch(Exception unreadable){return;}
  if(current.has("liveProfileId"))return;
  current.put("liveProfileId",defaultLiveProfile(NativeBridge.byok)).put("revision",current.getLong("revision")+1);
  SystemAi.save(new File(SystemAi.ROOT,"audio-settings.json"),current);
 }
 private static synchronized void selectLiveProvider(String profileId)throws Exception {
  if(!LIVE_PROFILES.contains(profileId))
   throw new AudioConfigFailure("INVALID_ARGUMENT","Select an available Live Talk provider");
  JSONObject current;
  try {current=audioSettings();current.getLong("revision");current.getJSONObject("profiles");}
  catch(Exception unreadable){throw new AudioConfigFailure("SETTINGS_UNREADABLE","Saved voice settings cannot be read; no configuration was changed");}
  if(profileId.equals(current.opt("liveProfileId")))return;
  current.put("liveProfileId",profileId).put("revision",current.getLong("revision")+1);
  SystemAi.save(new File(SystemAi.ROOT,"audio-settings.json"),current);changed();
 }
 private static JSONObject snapshot()throws Exception {
  JSONObject c=SystemAi.config(),reading=audioSettings();
  JSONObject fal=NativeBridge.byok.falSettings();
  return new JSONObject().put("enabled",c.optBoolean("enabled")).put("audioEnabled",c.optBoolean("audioEnabled",true))
   .put("textTranslationConfigured",!MagpieSettings.binding("org.agentos.audio","text").isBlank()).put("systemRevision",c.optLong("revision")).put("audioRevision",reading.getLong("revision")).put("reading",reading.getJSONObject("profiles")).put("dictation",dictation(reading)).put("apps",consumers(c.getJSONObject("apps")))
   .put("fal",fal).put("live",liveSettings(reading,fal));
 }
 private static JSONObject settingsSnapshot(JSONObject fal)throws Exception {
  JSONObject config=SystemAi.config(),audio=audioSettings();
  if(fal==null)fal=NativeBridge.byok.falSettings();
  return new JSONObject().put("enabled",config.getBoolean("enabled")).put("audioEnabled",config.optBoolean("audioEnabled",true))
   .put("systemRevision",config.getLong("revision")).put("audioRevision",audio.getLong("revision"))
   .put("reading",audio.getJSONObject("profiles")).put("dictation",dictation(audio))
   .put("fal",fal).put("live",liveSettings(audio,fal));
 }
 static JSONObject settings(JSONObject request)throws Exception {
  String action=settingsAction(request);
  try {
   JSONObject fal=null;
   switch(action){
    case "read":break;
    case "saveFalKey","removeFalKey","selectFalModel":fal=NativeBridge.byok.falControl(request);break;
    case "saveLiveKey","removeLiveKey":NativeBridge.byok.liveControl(request);break;
    case "saveMossKey","removeMossKey":NativeBridge.byok.mossControl(request);break;
    case "configureLiveStandby":configureStandby(request);break;
    case "selectLiveProvider":selectLiveProvider(request.getString("profileId"));break;
    case "setAudioEnabled":SystemAi.control(new JSONObject().put("action","configure").put("patch",new JSONObject().put("audioEnabled",request.getBoolean("enabled"))));break;
    case "configureReading","resetReading":{
     JSONObject profile;
     try{profile=readingProfile(request,action.equals("resetReading"));}
     catch(IOException|JSONException invalid){throw new AudioConfigFailure("INVALID_ARGUMENT","Select an available reading language, voice, and speed");}
     writeReading(request.getString("language"),profile);break;
    }
    case "configureDictation":writeDictation(request,false);break;
    case "resetDictation":writeDictation(request,true);break;
   }
   return settingsSnapshot(fal);
  }catch(AudioConfigFailure failure){throw failure;}
  catch(Exception failure){throw new AudioConfigFailure(action.equals("read")?"CONFIG_UNAVAILABLE":"SETTINGS_NOT_SAVED","Voice settings are temporarily unavailable. Refresh and check their status");}
 }
 private static String settingsAction(JSONObject request)throws Exception {
  if(!"settings".equals(request.opt("op"))||!(request.opt("action") instanceof String))throw new AudioConfigFailure("INVALID_ARGUMENT","Invalid voice settings parameters");
  String action=request.getString("action");
  Set<String> fields=switch(action){
   case "read","removeFalKey","removeLiveKey","removeMossKey"->Set.of("op","action");
   case "saveFalKey","saveLiveKey","saveMossKey"->Set.of("op","action","key");
   case "selectFalModel"->Set.of("op","action","model");
   case "selectLiveProvider"->Set.of("op","action","profileId");
   case "configureLiveStandby"->Set.of("op","action","enabled","hotTimeoutSec","coldTimeoutSec","lockScreenWakeEnabled");
   case "setAudioEnabled"->Set.of("op","action","enabled");
   case "configureReading"->Set.of("op","action","language","voiceId","speed");
   case "resetReading"->Set.of("op","action","language");
   case "configureDictation"->Set.of("op","action","scope","primaryProfileId","fallbackProfileId","language");
   case "resetDictation"->Set.of("op","action","scope");
   default->throw new AudioConfigFailure("UNSUPPORTED_OPERATION","Voice settings operation is unavailable");
  };
  if(request.length()!=fields.size())throw new AudioConfigFailure("INVALID_ARGUMENT","Invalid voice settings parameters");
  for(Iterator<String> keys=request.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new AudioConfigFailure("INVALID_ARGUMENT","Invalid voice settings parameters");
  for(String field:fields){
   Object value=request.opt(field);
   boolean valid=field.equals("enabled")||field.equals("lockScreenWakeEnabled")?value instanceof Boolean
    :field.equals("hotTimeoutSec")||field.equals("coldTimeoutSec")?value instanceof Integer
    :field.equals("speed")?value instanceof Number&&Double.isFinite(((Number)value).doubleValue())
    :field.equals("fallbackProfileId")?value instanceof String||JSONObject.NULL.equals(value)
    :value instanceof String;
   if(!valid)throw new AudioConfigFailure("INVALID_ARGUMENT","Invalid voice settings parameter type");
  }
  return action;
 }
 /**
  * The engine authorizes callers by package. First-party app keys are not packages, so the
  * Agent UI's key (agent-ui, on unless the user turned it off) also stands for its package: the
  * chat and home voice input there use the engine under that one switch.
  */
 static final String AGENT_UI_PACKAGE="org.agentos.agent";
 static JSONObject consumers(JSONObject apps)throws Exception {
  return new JSONObject(apps.toString()).put(AGENT_UI_PACKAGE,apps.optBoolean("agent-ui",true));
 }
 static synchronized void credentialsChanged()throws Exception {
  try {
   JSONObject current=audioSettings();current.put("revision",current.getLong("revision")+1);
   SystemAi.save(new File(SystemAi.ROOT,"audio-settings.json"),current);
  } finally {changed();}
 }
 private static synchronized void attach(LocalSocket socket,BlockingQueue<String> queue)throws Exception {
  queue.add(snapshot().toString());subscribers.put(socket,queue);
 }
 static synchronized void changed() {
  try {String next=snapshot().toString();for(var entry:subscribers.entrySet())if(!entry.getValue().offer(next)){try{entry.getKey().close();}catch(Exception ignored){}subscribers.remove(entry.getKey());}}
  catch(Exception e){for(LocalSocket socket:subscribers.keySet())try{socket.close();}catch(Exception ignored){}subscribers.clear();}
 }
 /** Reason codes the engine maps to user messages; exception text is not forwarded. */
 static String failureCode(String operation,Exception error){
  if(error instanceof AudioConfigFailure)return ((AudioConfigFailure)error).code;
  return switch(operation){
   case "read"->"CONFIG_UNAVAILABLE";
   case "settings"->"CONFIG_UNAVAILABLE";
   case "configureReading","resetReading"->"SETTINGS_NOT_SAVED";
   case "configureDictation","resetDictation"->"SETTINGS_NOT_SAVED";
   case "falCredential","liveCredential","mossCredential"->"PROVIDER_NOT_CONFIGURED";
   case "prepareTranslation","translate"->"TEXT_TRANSLATION_FAILED";
   default->"UNSUPPORTED_OPERATION";
  };
 }
 private static String failureMessage(String operation,String code){
  return switch(code){
   case "POLICY_REVOKED"->"Voice AI is paused";
   case "PROVIDER_NOT_CONFIGURED"->operation.equals("liveCredential")?"Add or save your Gemini API key again in Taku Audio"
    :operation.equals("mossCredential")?"Add or save your MOSS API key again in Taku Audio":"Add or save your fal Key again in Taku Audio";
   case "SETTINGS_UNREADABLE"->"Saved voice settings cannot be read. Select them again in Taku Audio";
   case "SETTINGS_NOT_SAVED"->"Voice settings could not be saved";
   case "TEXT_TRANSLATION_FAILED"->"Text translation did not complete";
   case "CONFIG_UNAVAILABLE"->"Voice configuration is temporarily unavailable";
   case "INVALID_ARGUMENT"->"Invalid voice settings parameters. Check the language, voice, speed, or key";
   case "UNSUPPORTED_OPERATION"->"Voice settings operation is unavailable";
   default->"Voice configuration operation is unavailable";
  };
 }
 private static void serve(LocalSocket socket) {
  try(LocalSocket s=socket){
   if(!trusted(s.getPeerCredentials().getUid()))return;
   s.setSoTimeout(5000);InputStream input=s.getInputStream();ByteArrayOutputStream request=new ByteArrayOutputStream();int value;
   while((value=input.read())!=-1&&value!=10){if(request.size()>=16384)return;request.write(value);}
   JSONObject message=new JSONObject(request.toString("UTF-8"));String operation=message.optString("op");
   if(!operation.equals("subscribe")){
    JSONObject response;
    try {
     JSONObject result=switch(operation){
      case "settings"->settings(message);
      case "read"->snapshot();
      case "falCredential"->NativeBridge.byok.falCredential();
      case "liveCredential"->NativeBridge.byok.liveCredential();
      case "mossCredential"->NativeBridge.byok.mossCredential();
      case "prepareTranslation"->AudioTextTranslation.prepare();
      case "translate"->AudioTextTranslation.translate(message);
      case "configureReading","resetReading"->configureReading(message);
      default->throw new IOException("Unknown voice configuration operation");
     };
     response=new JSONObject().put("ok",true).put("result",result);
    } catch(Exception error) {
     String code=failureCode(operation,error);
     response=new JSONObject().put("ok",false).put("code",code).put("message",failureMessage(operation,code));
    }
    s.getOutputStream().write((response.toString()+"\n").getBytes(StandardCharsets.UTF_8));return;
   }
   BlockingQueue<String> queue=new ArrayBlockingQueue<>(16);attach(s,queue);
   while(true){String next=queue.poll(2,TimeUnit.SECONDS);if(next==null)next=snapshot().toString();s.getOutputStream().write((next+"\n").getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();}
  }catch(Exception ignored){}finally{subscribers.remove(socket);}
 }
}
