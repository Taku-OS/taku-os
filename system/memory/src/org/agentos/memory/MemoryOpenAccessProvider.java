package org.agentos.memory;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/**
 * CLI and authorized-app access to the same store and management operations as the Memory UI.
 *
 * System, Agent shell and platform-signed callers pass through directly
 * (still respecting `queryEnabled` on reads);
 * external third-party UIDs are authorized against the central Taku App Permission Center
 * (`content://org.agentos.assistant.permissions`) and may never modify global Memory settings.
 */
public final class MemoryOpenAccessProvider extends ContentProvider {
 private static final Set<String> METHODS=new HashSet<>(Arrays.asList("status","search","get","board","profile","agenda","nudges","configure","maintain","clearDerived","deleteBoardItem","deleteProfileItem","update","forget"));
 private static final Uri PERMISSION_GATE=Uri.parse("content://org.agentos.assistant.permissions");
 public boolean onCreate(){return true;}

 private boolean isTrustedCaller(int uid){
  if(uid==0||uid==Process.SYSTEM_UID||uid==Process.SHELL_UID||uid==Process.myUid())return true;
  Context ctx=getContext();
  return ctx!=null&&ctx.getPackageManager().checkSignatures(uid,Process.SYSTEM_UID)==PackageManager.SIGNATURE_MATCH;
 }

 private boolean checkExternalPermission(int uid,String scope){
  Context ctx=getContext();if(ctx==null)return false;
  if("memory.read".equals(scope)&&ctx.checkPermission("org.agentos.memory.ACCESS_MEMORY",Binder.getCallingPid(),uid)==PackageManager.PERMISSION_GRANTED){
   return true;
  }
  try{
   Bundle res=ctx.getContentResolver().call(PERMISSION_GATE,"check",new JSONObject().put("uid",uid).put("scope",scope).toString(),null);
   if(res==null)return false;
   String raw=res.getString("result");if(raw==null||raw.isEmpty())return false;
   JSONObject verdict=new JSONObject(raw);
   return verdict.optBoolean("ok",false)&&verdict.optBoolean("allowed",false);
  }catch(Exception failClosed){return false;}
 }

 @Override public Bundle call(String method,String arg,Bundle extras){
  int callerUid=Binder.getCallingUid();
  if(method==null||method.isEmpty())return denied("INVALID_ARGUMENT","Missing memory method");
  if(!isTrustedCaller(callerUid)){
   if("configure".equals(method)){
    return denied("PERMISSION_DENIED","Memory configuration is restricted to system settings and Agent CLI");
   }
   boolean isRead="status".equals(method)||MemoryStore.QUERY_METHODS.contains(method);
   String requiredScope=isRead?"memory.read":"memory.write";
   if(!checkExternalPermission(callerUid,requiredScope)){
    return denied("PERMISSION_DENIED","Memory access denied by App Permission Center");
   }
  }
  long identity=Binder.clearCallingIdentity();
  try{
   JSONObject p=arg==null||arg.isEmpty()?new JSONObject():new JSONObject(arg);
   if(MemoryStore.QUERY_METHODS.contains(method)){
    // This exported API must not inherit the app's own-UID exemption after clearCallingIdentity.
    JSONObject r=MemoryStore.get(getContext()).query(method,p,true,true);
    Bundle b=new Bundle();b.putString("result",new JSONObject().put("ok",true).put("result",r).toString());return b;
   }
   if(method.equals("update")){
    JSONObject r=MemoryStore.get(getContext()).updateFromOpenAccess(p);
    Bundle b=new Bundle();b.putString("result",new JSONObject().put("ok",true).put("result",r).toString());return b;
   }
   if(!METHODS.contains(method))throw new IllegalArgumentException("Unknown memory method: "+method);
   return getContext().getContentResolver().call(Uri.parse("content://org.agentos.memory"),method,p.toString(),null);
  }catch(Exception e){Bundle b=new Bundle();try{b.putString("result",new JSONObject().put("ok",false).put("message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).toString());}catch(Exception ignored){}return b;}
  finally{Binder.restoreCallingIdentity(identity);}
 }

 private static Bundle denied(String code,String message){
  Bundle b=new Bundle();
  try{b.putString("result",new JSONObject().put("ok",false).put("code",code).put("message",message).toString());}catch(Exception ignored){}
  return b;
 }
 public Cursor query(Uri u,String[] p,String s,String[] a,String order){throw new UnsupportedOperationException("Use content call");}
 public String getType(Uri u){return "application/json";}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
