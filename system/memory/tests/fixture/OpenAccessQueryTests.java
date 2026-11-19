package org.agentos.memory;

import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;

/**
 * Real Binder calls from an ordinary untrusted UID on the isolated synthetic emulator:
 * verifies that global Memory configuration is always blocked for external apps and that
 * default `deny` policy blocks ungranted third-party reads/writes.
 */
public final class OpenAccessQueryTests extends Instrumentation {
 int checks;boolean fixture;
 @Override public void onCreate(Bundle b){super.onCreate(b);fixture=b!=null&&"synthetic-emulator".equals(b.getString("fixture"));start();}
 void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 JSONObject call(String method,JSONObject parameters)throws Exception{
  Bundle result=getTargetContext().getContentResolver().call(Uri.parse("content://org.agentos.memory.open-access"),method,parameters.toString(),null);
  if(result==null)throw new AssertionError("missing provider result");return new JSONObject(result.getString("result"));
 }
 @Override public void onStart(){Bundle out=new Bundle();Throwable failure=null;try{
  check(fixture&&"ranchu".equals(android.os.Build.HARDWARE),"requires explicit isolated emulator fixture");
  check(android.os.Process.myUid()>=10000&&getTargetContext().checkSelfPermission("org.agentos.memory.ACCESS_MEMORY")!=PackageManager.PERMISSION_GRANTED,"must run without the signature permission");
  JSONObject cfg=call("configure",new JSONObject().put("queryEnabled",true));
  check(!cfg.getBoolean("ok")&&"PERMISSION_DENIED".equals(cfg.optString("code")),"ordinary app must never modify global Memory settings");
  for(String method:MemoryStore.QUERY_METHODS){
   JSONObject result=call(method,new JSONObject().put("id","missing-synthetic-id"));
   check(!result.getBoolean("ok")&&"PERMISSION_DENIED".equals(result.optString("code")),method+" must be denied to ungranted third-party app");
  }
 }catch(Throwable e){failure=e;}
 try{if(failure==null){out.putString("result",new JSONObject().put("passed",true).put("checks",checks).put("ordinaryUid",true).toString());finish(-1,out);}else{out.putString("failure",failure.toString());finish(0,out);}}catch(Exception impossible){throw new IllegalStateException(impossible);}
 }
}
