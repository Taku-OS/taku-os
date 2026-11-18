package org.agentos.assistant;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.util.Arrays;
/** UID captured on Binder entry. No arbitrary RPC or key access. */
public final class ImeReplyProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  int uid=Binder.getCallingUid();Bundle out=new Bundle();try{
   String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
   if(packages==null||!Arrays.asList(packages).contains("org.agentos.inputmethod"))throw new SecurityException("Only the Taku input method may call this interface");
   boolean signer=false;for(String digest:getContext().getResources().getStringArray(R.array.ime_reply_signers)){
    byte[] cert=new byte[digest.length()/2];for(int i=0;i<cert.length;i++)cert[i]=(byte)Integer.parseInt(digest.substring(i*2,i*2+2),16);
    signer|=getContext().getPackageManager().hasSigningCertificate("org.agentos.inputmethod",cert,PackageManager.CERT_INPUT_SHA256);
   }if(!signer)throw new SecurityException("Input method signature is not trusted");
   if(!"reply".equals(method)||arg==null||arg.length()>2500)throw new IllegalArgumentException();JSONObject source=new JSONObject(arg);String action=source.getString("action");
   if(!Arrays.asList("settings","models","start","poll","cancel").contains(action))throw new SecurityException("Unsupported operation");
   if(!Arrays.asList("settings","models","cancel").contains(action)){String active=Settings.Secure.getString(getContext().getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);if(active==null||!active.startsWith("org.agentos.inputmethod/"))throw new SecurityException("Not the current input method");}
   JSONObject p=new JSONObject().put("op","imeReplies").put("action",action).put("owner",uid);for(String key:new String[]{"id","foreground","height","top","model","fresh","previous"})if(source.has(key))p.put(key,source.get(key));
   long token=Binder.clearCallingIdentity();try{out.putString("result",new JSONObject().put("ok",true).put("result",NativeClient.exchange(p)).toString());}finally{Binder.restoreCallingIdentity(token);}
  }catch(SecurityException e){throw e;}catch(Exception e){try{out.putString("result",new JSONObject().put("ok",false).put("message","Could not connect this time. Reply in your own words for now").toString());}catch(Exception ignored){}}return out;
 }
 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){throw new UnsupportedOperationException();}
 public String getType(Uri u){return "application/vnd.taku.ime-reply+json";}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
