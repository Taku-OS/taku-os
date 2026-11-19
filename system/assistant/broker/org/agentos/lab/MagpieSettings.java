package org.agentos.lab;

import android.os.Handler;
import android.os.HandlerThread;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import org.json.*;

/** Settings is authoritative; providers.json is the private Magpie runtime projection. */
final class MagpieSettings {
 static final String PREFIX="taku_magpie_", PROVIDERS=PREFIX+"providers", MODELS=PREFIX+"models", GROUPS=PREFIX+"groups", SCENES=PREFIX+"scene_bindings", MIGRATED=PREFIX+"migrated_v1";
 static final File DIR=new File("/data/local/agentos/magpie/config/magpie"), FILE=new File(DIR,"providers.json"), SECRETS=new File(DIR,"taku-credentials.json");
 static Handler handler; static android.os.FileObserver fileObserver;
 static final File HASH=new File(DIR,"taku-projection.sha256");
 static boolean startupReady;
 static JSONObject file(File f)throws Exception{return f.exists()?new JSONObject(TaskBroker.read(f)):new JSONObject();}
 static void save(File f,JSONObject value)throws Exception{
  File dir=f.getParentFile();dir.mkdirs();android.system.Os.chmod(dir.getPath(),0700);File tmp=new File(dir,f.getName()+".taku.tmp");
  try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
  android.system.Os.chmod(tmp.getPath(),0600);Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
 }
 static JSONArray cleanProviders(JSONArray input,JSONObject secrets,JSONObject models)throws Exception{
  JSONArray clean=new JSONArray();
  for(int i=0;i<input.length();i++){
   JSONObject p=new JSONObject(input.getJSONObject(i).toString());String id=p.getString("id"),ref=p.optString("credentialRef",id);if(ref.isEmpty())ref=id;JSONObject secret=secrets.optJSONObject(ref);
   if(secret==null)secret=new JSONObject();String before=p.toString();MagpieProviderSecrets.split(p,secret);
   if(!before.equals(p.toString())){secrets.put(ref,secret);p.put("credentialRef",ref);}
   if(models!=null&&p.has("models"))models.put(id,p.remove("models"));completeDecisionEndpoint(p);clean.put(p);
  }
  return clean;
 }
 /** Magpie 0.1.2204 treats preset-only records as account picks, not providers.
  * Fill the official endpoint for new keys and old Settings/runtime records alike.
  * Preserve any explicit endpoint, credential reference, model picks and routes. */
 static void completeDecisionEndpoint(JSONObject provider)throws JSONException{
  if("typesafe".equals(provider.optString("preset"))&&provider.optString("decide").isBlank())
   provider.put("decide","https://api.typesafe.ai/v1");
 }
 static synchronized void migrate()throws Exception{migrate(SystemAi.SETTINGS,FILE,SECRETS);}
 static void migrate(SystemAiSettings.Store store,File source,File secretFile)throws Exception{
  if(store.get(SystemAiSettings.CONFIG)!=null)return;
  boolean migrated="1".equals(store.get(MIGRATED));JSONObject secrets=file(secretFile);
  if(!migrated){
   JSONObject old=file(source),models=new JSONObject();JSONArray providers=old.optJSONArray("providers");
   JSONArray clean=cleanProviders(providers==null?new JSONArray():providers,secrets,models);
   // Persist credentials before publishing their references. A failed Settings write is retryable.
   save(secretFile,secrets);
   SystemAiSettings.putMissing(store,PROVIDERS,clean.toString());
   SystemAiSettings.putMissing(store,MODELS,models.toString());
   SystemAiSettings.putMissing(store,GROUPS,(old.optJSONArray("groups")!=null?old.getJSONArray("groups"):new JSONArray()).toString());
   SystemAiSettings.putMissing(store,SCENES,new JSONObject().toString());
   store.put(MIGRATED,"1");
  }
  // Older versions could have published credentials before setting MIGRATED.
  // Also clean a subsequent Settings CLI write before projecting it to Magpie.
  String raw=store.get(PROVIDERS);
  if(raw!=null){JSONArray clean=cleanProviders(new JSONArray(raw),secrets,null);String safe=clean.toString();
   if(!raw.equals(safe)){save(secretFile,secrets);store.put(PROVIDERS,safe);}
  }
  SystemAiSettings.migrateSlots(store);SystemAiSettings.ensureMemoryGroup(store);SystemAiSettings.publishConfig(store);
 }
 static void requirePublicProvider(JSONObject p)throws Exception{JSONObject probe=new JSONObject();MagpieProviderSecrets.split(new JSONObject(p.toString()),probe);if(probe.length()>0)throw new IOException("credentials must use credentialRef");}
 static final String[] PROJECTED_KEYS={PROVIDERS,MODELS,GROUPS,SCENES};
 static String[] readProjection(SystemAiSettings.Store store)throws Exception{
  String[] values=new String[PROJECTED_KEYS.length];for(int i=0;i<values.length;i++)values[i]=store.get(PROJECTED_KEYS[i]);return values;
 }
 static void requireUnchanged(SystemAiSettings.Store store,String[] expected)throws Exception{
  if(!Arrays.equals(expected,readProjection(store)))throw new IOException("Magpie Settings changed during projection; retrying");
 }
 static void sanitizeConfig(SystemAiSettings.Store store,File secretFile)throws Exception{
  String raw=store.get(SystemAiSettings.CONFIG);if(raw==null)throw new IOException("missing unified configuration");JSONObject config=new JSONObject(raw),secrets=file(secretFile),models=config.getJSONObject("models");
  JSONArray original=config.getJSONArray("providers"),clean=cleanProviders(original,secrets,models);
  if(!original.toString().equals(clean.toString())){save(secretFile,secrets);if(!raw.equals(store.get(SystemAiSettings.CONFIG)))throw new IOException("configuration changed during credential cleanup");config.put("providers",clean);store.put(SystemAiSettings.CONFIG,config.toString());}
 }
 static synchronized void sync()throws Exception{sync(SystemAi.SETTINGS,FILE,SECRETS);}
 static void sync(SystemAiSettings.Store store,File source,File secretFile)throws Exception{
  migrate(store,source,secretFile);sanitizeConfig(store,secretFile);String snapshot=store.get(SystemAiSettings.CONFIG);JSONObject config=new JSONObject(snapshot);String fileHash=source.isFile()?hash(source):"";
  JSONArray providers=config.getJSONArray("providers"),groups=config.getJSONArray("groups");JSONObject models=config.getJSONObject("models"),bindings=config.getJSONObject("scenes"),secrets=file(secretFile),out=file(source);String before=out.toString();
  for(String scene:AiPolicy.keys(bindings)){String target=bindings.getString(scene);if(!target.startsWith("group/")||target.length()<=6)throw new IOException("invalid scene group");}
  for(int i=0;i<providers.length();i++){
   JSONObject p=providers.getJSONObject(i);String id=p.getString("id"),ref=p.optString("credentialRef","");
   requirePublicProvider(p);
   p.remove("credentialRef");p.remove("models");if(models.has(id))p.put("models",models.getJSONArray(id));
   if(!ref.isEmpty()){JSONObject s=secrets.optJSONObject(ref);if(s==null)throw new IOException("credential reference missing");MagpieProviderSecrets.restore(p,s);}
  }
  out.put("providers",providers).put("groups",groups);if(!snapshot.equals(store.get(SystemAiSettings.CONFIG))||!fileHash.equals(source.isFile()?hash(source):""))throw new IOException("configuration changed during projection");
  boolean changed=!before.equals(out.toString())||!source.exists();String expected=changed?hashBytes(out.toString().getBytes(StandardCharsets.UTF_8)):fileHash;
  if(changed)save(source,out);writeHash(source,expected);audit("settings -> magpie",before,out.toString());SystemAi.catalogUntil=0;
 }
 static synchronized String importLegacy(String key)throws Exception{return addProvider("openrouter",key,"taku-jev-import","Taku Jev import");}
 /** A Jev key from onboarding: OpenRouter (`sk-or-v1-…`) or TypeSafe's own Jev API (`apikey_…`, Magpie preset `typesafe`). */
 static synchronized String addDecisionProvider(String preset,String key)throws Exception{
  if("typesafe".equals(preset))return addProvider(preset,key,"taku-jev-typesafe","TypeSafe Jev");
  if("openrouter".equals(preset))return addProvider(preset,key,"taku-jev-openrouter","Taku Jev (OpenRouter)");
  throw new IOException("unsupported decision provider");
 }
 /** Reuses a provider of [preset] that already holds [key]; otherwise adds one through Settings and its credential store. */
 private static String addProvider(String preset,String key,String baseId,String name)throws Exception{
  migrate();JSONArray providers=new JSONArray(SystemAi.SETTINGS.get(PROVIDERS));JSONObject secrets=file(SECRETS);
  for(int i=0;i<providers.length();i++){JSONObject p=providers.getJSONObject(i),secret=secrets.optJSONObject(p.optString("credentialRef"));if(!preset.equals(p.optString("preset"))||secret==null)continue;
   if(key.equals(secret.optString("key"))){sync();return p.getString("id");}JSONArray keys=secret.optJSONArray("keys");if(keys!=null)for(int n=0;n<keys.length();n++)if(key.equals(keys.getJSONObject(n).optString("key"))){sync();return p.getString("id");}
  }
  String id=baseId;int suffix=2;boolean used;do{used=secrets.has(id);for(int i=0;i<providers.length();i++)used|=id.equals(providers.getJSONObject(i).optString("id"));if(used)id=baseId+"-"+suffix++;}while(used);
  secrets.put(id,new JSONObject().put("key",key));save(SECRETS,secrets);
  JSONObject provider=new JSONObject().put("id",id).put("name",name).put("preset",preset);
  // A preset name alone does not expand endpoints when Magpie reads providers.json.
  completeDecisionEndpoint(provider);
  if("openrouter".equals(preset))provider.put("chat","https://openrouter.ai/api/v1").put("anthropic","https://openrouter.ai/api").put("decide","https://openrouter.ai/api/v1").put("catalog","openrouter");
  providers.put(provider.put("credentialRef",id));
  SystemAi.SETTINGS.put(PROVIDERS,providers.toString());sync();
  JSONArray confirmed=new JSONArray(SystemAi.SETTINGS.get(PROVIDERS));for(int i=0;i<confirmed.length();i++){JSONObject p=confirmed.getJSONObject(i);if(id.equals(p.optString("id"))&&id.equals(p.optString("credentialRef")))return id;}
  throw new IOException("Magpie settings changed; credentials were preserved");
 }
 static synchronized String bindModel(String scene,String model,boolean overwrite)throws Exception{
  migrate();String snapshot=SystemAi.SETTINGS.get(SystemAiSettings.CONFIG);JSONObject config=new JSONObject(snapshot);JSONArray groups=config.getJSONArray("groups");JSONObject bindings=config.getJSONObject("scenes");
  if(!overwrite&&bindings.has(scene))return bindings.getString(scene);
  String target=SystemAiSettings.legacyGroup(groups,model,"route-"+scene.replaceAll("[^a-zA-Z0-9_-]","-"));bindings.put(scene,target);
  if(!snapshot.equals(SystemAi.SETTINGS.get(SystemAiSettings.CONFIG)))throw new IOException("configuration changed during scene update");
  config.put("groups",groups).put("scenes",bindings).put("updated_at",System.currentTimeMillis()).put("updated_by","taku");SystemAi.SETTINGS.put(SystemAiSettings.CONFIG,config.toString());sync();return target;
 }
 static String scene(String scene)throws Exception{String raw=SystemAi.SETTINGS.get(SCENES);return raw==null?"":new JSONObject(raw).optString(scene,"");}
 static String binding(String app,String role)throws Exception{
  String raw=SystemAi.SETTINGS.get(SCENES);if(raw==null)return "";JSONObject b=new JSONObject(raw);String scene=app.equals("ime")&&role.equals("image")?"ime.vision":app.equals("org.agentos.assistant")&&role.equals("image")?"screen":app+"."+role;return b.optString(scene,"");
 }
 static String hash(File f)throws Exception{
  return hashBytes(Files.readAllBytes(f.toPath()));
 }
 static String hashBytes(byte[] input)throws Exception{
  byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(input);StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return b.toString();
 }
 static File hashFile(File source){return new File(source.getParentFile(),"taku-projection.sha256");}
 static void writeHash(File source,String value)throws Exception{File f=hashFile(source),tmp=new File(f.getParentFile(),f.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}android.system.Os.chmod(tmp.getPath(),0600);Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
 static String savedHash(File source)throws Exception{File f=hashFile(source);return f.isFile()?TaskBroker.read(f).trim():"";}
 // Do not log values: provider objects may contain credentials. Hashes and counts
 // are bounded summaries of what this process actually observed.
 static void audit(String origin,String before,String after)throws Exception{if(before.equals(after))return;java.security.MessageDigest d=java.security.MessageDigest.getInstance("SHA-256");String a=java.util.Base64.getEncoder().encodeToString(d.digest(before.getBytes(StandardCharsets.UTF_8))),b=java.util.Base64.getEncoder().encodeToString(d.digest(after.getBytes(StandardCharsets.UTF_8)));System.out.println("Magpie observed "+origin+" "+summary(before)+" sha256="+a+" -> "+summary(after)+" sha256="+b);}
 static String summary(String value){try{JSONObject o=new JSONObject(value);JSONArray p=o.optJSONArray("providers"),g=o.optJSONArray("groups");JSONObject m=o.optJSONObject("models"),sc=o.optJSONObject("scenes");int models=0;if(m!=null)for(String id:AiPolicy.keys(m)){JSONArray a=m.optJSONArray(id);if(a!=null)models+=a.length();}else if(p!=null)for(int i=0;i<p.length();i++){JSONArray a=p.getJSONObject(i).optJSONArray("models");if(a!=null)models+=a.length();}return "providers="+(p==null?0:p.length())+",models="+models+",groups="+(g==null?0:g.length())+",scenes="+(sc==null?0:sc.length());}catch(Exception e){return "unavailable";}}
 static void importFile(SystemAiSettings.Store store,File source,File secretFile)throws Exception{
  if(!source.isFile())return;String actual=hash(source),own=savedHash(source);if(actual.equals(own))return;
  String snapshot=store.get(SystemAiSettings.CONFIG);if(snapshot==null)throw new IOException("missing unified configuration");
  JSONObject config=new JSONObject(snapshot),runtime=file(source),secrets=file(secretFile),models=new JSONObject();
  JSONArray providers=runtime.optJSONArray("providers");if(providers==null)providers=new JSONArray();
  JSONArray clean=cleanProviders(providers,secrets,models);JSONArray groups=runtime.optJSONArray("groups");if(groups==null)groups=new JSONArray();
  save(secretFile,secrets);if(!snapshot.equals(store.get(SystemAiSettings.CONFIG))||!actual.equals(hash(source)))throw new IOException("configuration changed during import");
  config.put("providers",clean).put("models",models).put("groups",groups).put("updated_at",Math.max(System.currentTimeMillis(),source.lastModified())).put("updated_by","magpie");
  String result=config.toString();store.put(SystemAiSettings.CONFIG,result);audit("magpie -> settings",snapshot,result);SystemAi.catalogUntil=0;
 }
 static synchronized void fileChanged()throws Exception{importFile(SystemAi.SETTINGS,FILE,SECRETS);}
 static synchronized void reconcile(SystemAiSettings.Store store,File source,File secretFile)throws Exception{
  migrate(store,source,secretFile);String raw=store.get(SystemAiSettings.CONFIG);if(raw==null)throw new IOException("missing unified configuration");
  if(source.isFile()&&!hash(source).equals(savedHash(source))&&source.lastModified()>new JSONObject(raw).optLong("updated_at",0))importFile(store,source,secretFile);else sync(store,source,secretFile);
 }
 static synchronized void startup()throws Exception{reconcile(SystemAi.SETTINGS,FILE,SECRETS);}
 static void start(){
  HandlerThread thread=new HandlerThread("magpie-settings");thread.start();handler=new Handler(thread.getLooper());DIR.mkdirs();
  fileObserver=new android.os.FileObserver(DIR,android.os.FileObserver.CLOSE_WRITE|android.os.FileObserver.MOVED_TO){@Override public void onEvent(int event,String path){if("providers.json".equals(path))scheduleFile();}};fileObserver.startWatching();handler.post(STARTUP);
  // ActivityManager owns the observer; the standalone Broker receives UID-gated IPC.
  Thread starter=new Thread(()->{for(;;){try{TaskBroker.command("/system/bin/am","startservice","--user","0","-n","org.agentos.assistant/.MagpieSettingsObserverService");}catch(Exception e){System.err.println("Magpie observer service: "+e);}try{Thread.sleep(60000);}catch(InterruptedException e){return;}}},"magpie-observer-start");starter.setDaemon(true);starter.start();
 }
 static final Runnable STARTUP=new Runnable(){public void run(){try{startup();startupReady=true;}catch(Exception e){System.err.println("Magpie startup: "+e);handler.postDelayed(this,5000);}}};
 static final Runnable APPLY=new Runnable(){public void run(){try{if(!startupReady){handler.postDelayed(this,5000);return;}sync();}catch(Exception e){System.err.println("Magpie Settings sync: "+e);handler.postDelayed(this,5000);}}};
 static final Runnable IMPORT=new Runnable(){public void run(){try{if(!startupReady){handler.postDelayed(this,5000);return;}fileChanged();}catch(Exception e){System.err.println("Magpie file sync: "+e);handler.postDelayed(this,5000);}}};
 static void schedule(){handler.removeCallbacks(APPLY);handler.postDelayed(APPLY,100);}
 static void scheduleFile(){handler.removeCallbacks(IMPORT);handler.postDelayed(IMPORT,100);}
}
