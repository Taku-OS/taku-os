package org.agentos.assistant;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;

/** Signature-gated bridge for the independent memory APK; exposes no arbitrary native RPC. */
public final class MemoryGatewayProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  Bundle out=new Bundle();try{
   if(!"memory".equals(method)||arg==null||arg.length()>16000)throw new IllegalArgumentException("Invalid memory request");
   JSONObject input=new JSONObject(arg);input.put("op","memory");
   out.putString("result",new JSONObject().put("ok",true).put("result",NativeClient.exchange(input)).toString());
  }catch(Exception error){try{out.putString("result",new JSONObject().put("ok",false).put("message","Memory connection failed. Check the model, runtime, and authorization.").toString());}catch(Exception ignored){}}
  return out;
 }
 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){throw new UnsupportedOperationException();}
 public String getType(Uri u){return "application/vnd.taku.memory-gateway+json";}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
