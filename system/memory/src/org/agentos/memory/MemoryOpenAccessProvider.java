package org.agentos.memory;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** CLI access to the same store and management operations as the Memory UI. */
public final class MemoryOpenAccessProvider extends ContentProvider {
 private static final Set<String> METHODS=new HashSet<>(Arrays.asList("status","search","get","board","profile","agenda","nudges","configure","maintain","clearDerived","deleteBoardItem","deleteProfileItem","update","forget"));
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
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
 public Cursor query(Uri u,String[] p,String s,String[] a,String order){throw new UnsupportedOperationException("Use content call");}
 public String getType(Uri u){return "application/json";}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
