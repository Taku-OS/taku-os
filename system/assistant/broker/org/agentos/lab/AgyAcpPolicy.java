package org.agentos.lab;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure rules for the Gemini lane: Codex → Magpie → `agy acp` (Google Antigravity CLI) → Gemini.
 *
 * <p>A Magpie provider with an {@code acp} object is answered by Magpie running agy on this phone.
 * Magpie runs as root, and its {@code providers.json} is projected from {@code Settings.Global}, which
 * the shell can write. So an {@code acp} object is projected only when its executable lives in a
 * root-owned system location (or the dedicated agy directory), its profile stays under Taku's private
 * agent data, and it carries no environment of its own. Anything else is left out of the projection.
 */
final class AgyAcpPolicy {
 private AgyAcpPolicy(){}

 /** The provider Taku creates and manages; other ACP providers are left as their owner wrote them. */
 static final String PROVIDER="taku-agy";
 static final String NAME="Gemini (Antigravity)";
 static final String DEFAULT_MODEL="gemini-3.8-flash";
 /** Where an image ships agy, preferred when present. */
 static final String SYSTEM_BINARY="/system_ext/bin/agy";
 /** The dedicated, root-only agy directory used by development installs. */
 static final String DATA_BINARY="/data/local/agentos/acp/bin/agy";
 /** agy's HOME for the Taku provider: its Google sign-in lives in {@code <home>/.gemini}. */
 static final String HOME="/data/local/agentos/magpie/agy-profile";
 static final String APP_DATA="taku-acp";
 static final List<String> BINARY_ROOTS=Arrays.asList("/system/bin/","/system_ext/bin/","/product/bin/","/vendor/bin/","/data/local/agentos/acp/bin/");
 static final List<String> PROFILE_ROOTS=Arrays.asList("/data/local/agentos/magpie/","/data/local/agentos/acp/");
 static final List<String> ACP_KEYS=Arrays.asList("binary","home","appDataDir","cwd","search");
 static final List<String> SIGN_IN_HOSTS=Arrays.asList("accounts.google.com");
 static final int MAX_CODE=4096;

 /** Throws when [provider]'s {@code acp} object must not reach the root-run Magpie. No acp: nothing to check. */
 static void requireSafe(JSONObject provider)throws IOException{
  if(!provider.has("acp"))return;
  Object raw=provider.opt("acp");
  if(!(raw instanceof JSONObject))throw new IOException("acp must be an object");
  JSONObject acp=(JSONObject)raw;
  for(Iterator<String> it=acp.keys();it.hasNext();){String key=it.next();if(!ACP_KEYS.contains(key))throw new IOException("acp."+key+" is not allowed");}
  String binary=text(acp,"binary");
  // Empty: Magpie finds agy on its own PATH, which its launcher limits to system directories.
  if(!binary.isEmpty()&&!under(binary,BINARY_ROOTS))throw new IOException("acp.binary must be in a system directory or "+DATA_BINARY);
  for(String key:new String[]{"home","cwd"}){String path=text(acp,key);if(!path.isEmpty()&&!under(path,PROFILE_ROOTS))throw new IOException("acp."+key+" must be under /data/local/agentos/magpie/");}
  String app=text(acp,"appDataDir");
  if(!app.isEmpty()&&!plainName(app))throw new IOException("acp.appDataDir must be one plain name");
  if(acp.has("search")&&!(acp.opt("search") instanceof Boolean))throw new IOException("acp.search must be true or false");
 }
 static boolean safe(JSONObject provider){try{requireSafe(provider);return true;}catch(IOException e){return false;}}

 static String text(JSONObject o,String key)throws IOException{
  if(!o.has(key))return "";Object v=o.opt(key);if(!(v instanceof String))throw new IOException("acp."+key+" must be text");return (String)v;
 }
 /** An absolute path with no {@code .}/{@code ..} segment, strictly inside one of [roots]. */
 static boolean under(String path,List<String> roots){
  if(!path.startsWith("/")||path.indexOf('\0')>=0||path.contains("//"))return false;
  for(String segment:path.substring(1).split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals(".."))return false;
  for(String root:roots)if(path.startsWith(root)&&path.length()>root.length())return true;
  return false;
 }
 static boolean plainName(String value){return value.matches("[A-Za-z0-9._-]{1,64}")&&!value.equals(".")&&!value.equals("..");}

 /** The Taku provider record (public: no credentials; agy keeps its own sign-in in [HOME]). */
 static JSONObject provider(String binary)throws JSONException{
  return new JSONObject().put("id",PROVIDER).put("name",NAME)
   .put("acp",new JSONObject().put("binary",binary).put("home",HOME).put("appDataDir",APP_DATA));
 }

 /**
  * Adds or repairs the Taku provider in the unified System AI [config] and gives it a model pick
  * when it has none. Returns whether anything changed. Other providers, groups and scenes stay.
  */
 static boolean ensure(JSONObject config,String binary)throws JSONException{
  JSONArray providers=config.getJSONArray("providers");JSONObject models=config.getJSONObject("models");boolean changed=false;
  JSONObject wanted=provider(binary),found=null;
  for(int i=0;i<providers.length();i++)if(PROVIDER.equals(providers.getJSONObject(i).optString("id"))){found=providers.getJSONObject(i);break;}
  if(found==null){providers.put(wanted);changed=true;}
  else{
   JSONObject acp=found.optJSONObject("acp");
   // Repair what Taku owns; a user-picked name is kept.
   if(acp==null||!binary.equals(acp.optString("binary"))||!HOME.equals(acp.optString("home"))||!APP_DATA.equals(acp.optString("appDataDir"))||acp.length()!=3){found.put("acp",wanted.getJSONObject("acp"));changed=true;}
   if(found.optString("name").isBlank()){found.put("name",NAME);changed=true;}
  }
  JSONArray picks=models.optJSONArray(PROVIDER);
  if(picks==null||picks.length()==0){models.put(PROVIDER,new JSONArray().put(DEFAULT_MODEL));changed=true;}
  return changed;
 }

 /** The model ids picked for the Taku provider, as Codex's Magpie catalog names them ({@code taku-agy/<model>}). */
 static String modelId(String model){return PROVIDER+"/"+model;}
 static boolean validModel(String model){return model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");}

 private static final Pattern URL=Pattern.compile("https://[^\\s\"'<>`]+");
 /** The first Google sign-in link in a line agy printed, or null. Nothing else in the line is kept. */
 static String signInUrl(String line){
  Matcher m=URL.matcher(line);
  while(m.find()){
   String url=m.group().replaceAll("[).,;:\\]]+$","");
   int slash=url.indexOf('/',8);String host=(slash<0?url.substring(8):url.substring(8,slash)).toLowerCase(java.util.Locale.ROOT);
   int q=host.indexOf('?');if(q>=0)host=host.substring(0,q);
   if(SIGN_IN_HOSTS.contains(host))return url;
  }
  return null;
 }

 /** What agy takes back on its stdin: the code Google's page shows, or the URL the browser landed on. */
 static String code(String raw)throws IOException{
  String code=raw==null?"":raw.trim();
  if(code.isEmpty())throw new IOException("SECRET_EMPTY");
  if(code.length()>MAX_CODE)throw new IOException("INVALID_CREDENTIAL");
  for(int i=0;i<code.length();i++){char c=code.charAt(i);if(Character.isWhitespace(c)||Character.isISOControl(c))throw new IOException("INVALID_CREDENTIAL");}
  return code;
 }

 /**
  * The public view of Magpie's {@code GET /_magpie/agy-acp/status} reply: state, models, thought
  * levels, plan name and resting windows. Paths, the login command and agy's own words stay out.
  */
 static JSONObject status(int http,String body)throws JSONException{
  JSONObject view=new JSONObject().put("models",new JSONArray()).put("efforts",new JSONArray()).put("plan","").put("restingUntil","");
  // 404/405 without a JSON body: this Magpie does not know agy-acp providers yet.
  if(http==404||http==405){
   JSONObject error=null;try{error=new JSONObject(body);}catch(Exception ignored){}
   return view.put("state",error!=null&&error.optString("error").startsWith("no agy-acp provider")?"not_configured":"unsupported");
  }
  if(http!=200)return view.put("state","unavailable");
  JSONObject reply;try{reply=new JSONObject(body);}catch(Exception e){return view.put("state","unavailable");}
  String state=reply.optString("state");
  if(!Arrays.asList("ready","needs_login","unavailable").contains(state))state="unavailable";
  view.put("state",state);
  JSONArray models=reply.optJSONArray("models"),efforts=reply.optJSONArray("efforts");
  if(models!=null)for(int i=0;i<models.length()&&i<32;i++){String m=models.optString(i);if(validModel(m))view.getJSONArray("models").put(m);}
  if(efforts!=null)for(int i=0;i<efforts.length()&&i<16;i++){String e=efforts.optString(i);if(e.matches("[A-Za-z0-9_-]{1,32}"))view.getJSONArray("efforts").put(e);}
  JSONObject tier=reply.optJSONObject("tier");
  if(tier!=null)view.put("plan",clip(tier.optString("name"),64));
  JSONArray rests=reply.optJSONArray("rests");
  if(rests!=null)for(int i=0;i<rests.length();i++){JSONObject r=rests.optJSONObject(i);if(r!=null&&!r.optString("until").isEmpty()){view.put("restingUntil",clip(r.optString("until"),40));break;}}
  return view;
 }
 static String clip(String value,int max){String clean=value.replaceAll("\\p{Cntrl}"," ").trim();return clean.length()>max?clean.substring(0,max):clean;}
}
