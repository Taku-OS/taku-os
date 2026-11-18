package org.agentos.ai;
import android.content.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.IOException;

/** Source-level client for Android apps; new apps need user authorization in Taku AI. Use a background executor. */
public final class AiClient {
 private final Context context;
 public AiClient(Context context){this.context=context.getApplicationContext();}
 public JSONObject settings()throws Exception{return call(new JSONObject().put("action","read"));}
 public JSONObject resolve(String role)throws Exception{return call(new JSONObject().put("action","resolve").put("role",role));}
 public JSONObject text(String prompt)throws Exception{return call(new JSONObject().put("action","generate").put("role","text").put("prompt",prompt));}
 public JSONObject image(String prompt,String dataUrl)throws Exception{return call(new JSONObject().put("action","generate").put("role","image").put("prompt",prompt).put("image",dataUrl));}
 public void openSettings(){context.startActivity(new Intent("org.agentos.action.AI_SETTINGS").setPackage("org.agentos.assistant").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));}
 private JSONObject call(JSONObject request)throws Exception{
  if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Call system AI from a background thread");
  Bundle raw=context.getContentResolver().call(Uri.parse("content://org.agentos.ai"),"ai",request.toString(),null);if(raw==null)throw new IOException("System AI unavailable");JSONObject r=new JSONObject(raw.getString("result","{}"));if(!r.optBoolean("ok"))throw new IOException(r.optString("message","System AI unavailable"));return r.getJSONObject("result");
 }
}
