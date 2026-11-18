package org.agentos.memory;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;
/** Actual isolated SQLite policy transitions; synthetic answers only in this untrusted test APK. */
public final class SystemAiTests extends Instrumentation {
 int checks;void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle out=new Bundle();try{
  getTargetContext().deleteDatabase("memories.db");getTargetContext().getSharedPreferences("memory",0).edit().clear().commit();MemoryStore s=new MemoryStore(getTargetContext(),()->{});
  check(s.followsSystem());check(s.effectiveModel().isEmpty());check(!s.config().getBoolean("analysisEnabled"));check(!s.config().getBoolean("enabled"));
  s.syncSystemModel(new JSONObject().put("id","system-A").put("revision",1));check(s.effectiveModel().equals("system-A"));check(!s.config().getBoolean("analysisEnabled"));
  s.configure(new JSONObject().put("enabled",true).put("analysisEnabled",true).put("sources",new JSONArray().put("org.agentos.memorytests")));long now=System.currentTimeMillis();
  JSONObject record=s.ingest("org.agentos.memorytests","page","Synthetic","Synthetic shared-model record: meet tomorrow at seven, venue to confirm.",s.config().getLong("generation"));check(record!=null);
  JSONObject batch=s.claimBatch(now+22000);check(batch.getString("model").equals("system-A"));
  JSONObject answers=new JSONObject().put("r0_category",new JSONObject().put("choice","life")).put("r0_importance",new JSONObject().put("noul",.8)).put("r0_next_step",new JSONObject().put("choice","organize").put("confidence",.9));
  s.syncSystemModel(new JSONObject().put("id","system-B").put("revision",2));check(s.completeBatch(batch,answers).length()==0);check(s.status().getInt("analysisQueued")==1);
  JSONObject next=s.claimBatch(now+50000);check(next.getString("model").equals("system-B"));s.completeBatch(next,answers);check(s.status().getInt("analysisQueued")==0);check(s.read(record.getString("id"),false).getString("model").equals("system-B"));
  s.configure(new JSONObject().put("model","explicit-Jev"));check(!s.followsSystem());s.syncSystemModel(new JSONObject().put("id","system-C").put("revision",3));check(s.effectiveModel().equals("explicit-Jev"));s.systemChanged();check(s.effectiveModel().equals("explicit-Jev"));
  s.configure(new JSONObject().put("model","").put("followSystem",true));check(s.followsSystem());s.syncSystemModel(new JSONObject().put("id","system-C").put("revision",3));check(s.effectiveModel().equals("system-C"));s.systemChanged();check(s.effectiveModel().isEmpty());check(s.config().getBoolean("analysisEnabled"));
  android.content.ContentResolver resolver=getTargetContext().getContentResolver();android.net.Uri uri=android.net.Uri.parse("content://org.agentos.ai");
  JSONObject publicState=new JSONObject(resolver.call(uri,"ai","{\"action\":\"read\"}",null).getString("result"));check(publicState.getBoolean("ok")&&!publicState.getJSONObject("result").getBoolean("enabled"));check(!publicState.getJSONObject("result").has("usage")&&!publicState.getJSONObject("result").has("apps"));
  JSONObject forged=new JSONObject(resolver.call(uri,"ai","{\"action\":\"resolve\",\"role\":\"text\",\"consumer\":\"ime\"}",null).getString("result"));check(!forged.getBoolean("ok"));
  JSONObject configure=new JSONObject(resolver.call(uri,"ai","{\"action\":\"configure\",\"patch\":{}}",null).getString("result"));check(configure.getBoolean("ok"));
  out.putString("result",new JSONObject().put("passed",true).put("checks",checks).toString());finish(-1,out);
 }catch(Throwable e){out.putString("failure",e.toString());finish(0,out);}}
}
