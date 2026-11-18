package org.agentos.memory;
import android.app.Instrumentation;
import android.os.Bundle;
import org.agentos.ai.AiClient;
import org.json.*;
/** Real public client from an independently signed, ordinary application UID. */
public final class SharedAiClientTests extends Instrumentation {
 String expected;
 @Override public void onCreate(Bundle b){super.onCreate(b);expected=b.getString("model");start();}
 @Override public void onStart(){Bundle out=new Bundle();try{
  AiClient ai=new AiClient(getTargetContext());JSONObject settings=ai.settings();if(!settings.getBoolean("enabled"))throw new AssertionError("fixture must be explicitly authorized by the test operator");
  JSONObject r=ai.text("这是一项合成连接验收，不包含个人数据。请只回复：系统 AI 已连通");if(r.getString("text").isBlank()||!r.getString("model").equals(expected))throw new AssertionError("must use the real system text model");
  JSONObject forged=new JSONObject(getTargetContext().getContentResolver().call(android.net.Uri.parse("content://org.agentos.ai"),"ai","{\"action\":\"resolve\",\"role\":\"text\",\"consumer\":\"ime\",\"model\":\"not-a-real-model\"}",null).getString("result"));if(!forged.getBoolean("ok")||!forged.getJSONObject("result").getString("id").equals(expected))throw new AssertionError("app cannot spoof its identity or override the system model");
  out.putString("result",new JSONObject().put("passed",true).put("modelResponsesSimulated",false).put("model",r.getString("model")).put("text",r.getString("text")).put("checks",3).toString());finish(-1,out);
 }catch(Throwable e){out.putString("failure",e.toString());finish(0,out);}}
}
