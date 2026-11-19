package org.agentos.assistant;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.util.*;

/** Shared, permission-gated AI API. Caller identity and application policy cannot be supplied by clients. */
public final class SystemAiProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  int uid=Binder.getCallingUid();Bundle out=new Bundle();
  try{
   if(!"ai".equals(method)||arg==null||arg.length()>400000)throw new IllegalArgumentException("Invalid AI request");
   JSONObject source=new JSONObject(arg);String action=source.optString("action");
   if(!Set.of("read","resolve","generate","configure").contains(action))throw new IllegalArgumentException("Unknown system AI operation");
   if(action.equals("configure")){
    if(!AppPermissionCatalog.isSystemOrShellUid(uid)&&!AppPermissionCatalog.isTrustedEnforcementHost(getContext(),uid))
     throw new SecurityException("System AI configuration denied");
   }else{
    getContext().enforceCallingPermission("org.agentos.assistant.permission.USE_AI","System AI access denied");
   }
   String consumer="";
   if(!action.equals("configure")){
    String[] packages=getContext().getPackageManager().getPackagesForUid(uid);if(packages==null||packages.length!=1)throw new SecurityException("AI caller identity is ambiguous");
    String pkg=packages[0];consumer=pkg;
    if(pkg.equals("org.agentos.memory")&&getContext().getPackageManager().checkSignatures(pkg,getContext().getPackageName())==android.content.pm.PackageManager.SIGNATURE_MATCH)consumer="memory";
    if(pkg.equals("org.agentos.inputmethod"))for(String digest:getContext().getResources().getStringArray(R.array.ime_reply_signers)){byte[] cert=new byte[digest.length()/2];for(int i=0;i<cert.length;i++)cert[i]=(byte)Integer.parseInt(digest.substring(i*2,i*2+2),16);if(getContext().getPackageManager().hasSigningCertificate(pkg,cert,android.content.pm.PackageManager.CERT_INPUT_SHA256))consumer="ime";}
    if(!consumer.equals("memory")&&!consumer.equals("ime")&&!AppPermissionProvider.isAllowedLocal(getContext(),uid,AppPermissionCatalog.SCOPE_TAKU_CONTROL))
     throw new SecurityException("PERMISSION_DENIED");
   }
   JSONObject request=new JSONObject().put("op","systemAi").put("action",action).put("consumer",consumer);
   for(String key:new String[]{"role","model","prompt","image"})if(source.has(key))request.put(key,source.get(key));
   if(action.equals("configure"))request.put("patch",source.getJSONObject("patch"));
   if(!consumer.equals("ime")&&!consumer.equals("memory"))request.remove("model");
   long token=Binder.clearCallingIdentity();try{
    if(!action.equals("configure"))NativeClient.exchange(new JSONObject().put("op","systemAi").put("action","register").put("consumer",consumer));
    JSONObject result=NativeClient.exchange(request);
    if(action.equals("read"))result=new JSONObject().put("enabled",result.optBoolean("enabled")&&result.getJSONObject("apps").optBoolean(consumer)).put("revision",result.getLong("revision")).put("models",result.getJSONArray("models"));
    out.putString("result",new JSONObject().put("ok",true).put("result",result).toString());
   }finally{Binder.restoreCallingIdentity(token);}
  }catch(SecurityException e){throw e;}catch(Exception e){try{out.putString("result",new JSONObject().put("ok",false).put("message",e.getMessage()==null?"System AI temporarily unavailable":e.getMessage()).toString());}catch(Exception ignored){}}
  return out;
 }
 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){throw new UnsupportedOperationException();}public String getType(Uri u){return "application/vnd.taku.ai+json";}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
