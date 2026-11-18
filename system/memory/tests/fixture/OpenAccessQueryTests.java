package org.agentos.memory;

import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;

/** Real Binder calls from an ordinary UID, only on the explicitly selected synthetic emulator. */
public final class OpenAccessQueryTests extends Instrumentation {
 int checks;boolean fixture;
 @Override public void onCreate(Bundle b){super.onCreate(b);fixture=b!=null&&"synthetic-emulator".equals(b.getString("fixture"));start();}
 void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 JSONObject call(String method,JSONObject parameters)throws Exception{
  Bundle result=getTargetContext().getContentResolver().call(Uri.parse("content://org.agentos.memory.open-access"),method,parameters.toString(),null);
  if(result==null)throw new AssertionError("missing provider result");return new JSONObject(result.getString("result"));
 }
 void configure(boolean enabled)throws Exception{check(call("configure",new JSONObject().put("queryEnabled",enabled)).getBoolean("ok"),"explicit configure");}
 void closed()throws Exception{
  for(String method:MemoryStore.QUERY_METHODS){
   JSONObject result=call(method,new JSONObject().put("id","missing-synthetic-id"));
   check(!result.getBoolean("ok")&&result.optString("message").contains("记忆查询已关闭"),method+" must honor the closed switch");
  }
  check(!call("status",new JSONObject()).getJSONObject("result").getBoolean("queryEnabled"),"read cannot silently enable queries");
 }
 @Override public void onStart(){Bundle out=new Bundle();Boolean original=null;Throwable failure=null;try{
  check(fixture&&"ranchu".equals(android.os.Build.HARDWARE),"requires explicit isolated emulator fixture");
  check(android.os.Process.myUid()>=10000&&getTargetContext().checkSelfPermission("org.agentos.memory.ACCESS_MEMORY")!=PackageManager.PERMISSION_GRANTED,"must run without the signature permission");
  original=call("status",new JSONObject()).getJSONObject("result").getBoolean("queryEnabled");
  configure(false);closed();configure(true);
  for(String method:MemoryStore.QUERY_METHODS){
   JSONObject result=call(method,new JSONObject().put("id","missing-synthetic-id"));
   check(method.equals("get")?!result.getBoolean("ok")&&!result.optString("message").contains("记忆查询已关闭"):result.getBoolean("ok"),method+" after explicit enable");
  }
  configure(false);closed();
 }catch(Throwable e){failure=e;}finally{if(original!=null)try{configure(original);}catch(Throwable e){failure=e;}}
 try{if(failure==null){out.putString("result",new JSONObject().put("passed",true).put("checks",checks).put("ordinaryUid",true).toString());finish(-1,out);}else{out.putString("failure",failure.toString());finish(0,out);}}catch(Exception impossible){throw new IllegalStateException(impossible);}
 }
}
