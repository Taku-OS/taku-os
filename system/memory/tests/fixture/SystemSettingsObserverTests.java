package org.agentos.memory;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.json.*;
import java.io.*;

/** Exercises real SettingsProvider notifications against an isolated synthetic Memory database. */
public final class SystemSettingsObserverTests extends Instrumentation {
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 String shell(String command)throws Exception{
  try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();}
 }
 @Override public void onStart(){Bundle out=new Bundle();String key="taku_memory_observer_fixture",old=null;
  try{
   old=shell("settings get global "+key);if(!old.equals("null"))throw new IllegalStateException("fixture key already exists");
   getTargetContext().deleteDatabase("memories.db");getTargetContext().getSharedPreferences("memory",0).edit().clear().commit();
   MemoryStore s=new MemoryStore(getTargetContext(),()->{});s.observeSystemSettings();
   s.syncSystemModel(new JSONObject().put("id","fixture").put("revision",1));
   s.configure(new JSONObject().put("enabled",true).put("analysisEnabled",true).put("sources",new JSONArray().put("org.agentos.memorytests")));
   s.ingest("org.agentos.memorytests","fixture","Fixture","Synthetic appointment tomorrow at seven.",s.config().getLong("generation"));
   JSONObject batch=s.claimBatch(System.currentTimeMillis()+22000);long generation=batch.getLong("generation");
   shell("settings put global "+key+" 1");
   long deadline=android.os.SystemClock.elapsedRealtime()+10000;
   while(s.config().getLong("analysisGeneration")==generation&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
   if(s.config().getLong("analysisGeneration")==generation||!s.effectiveModel().isEmpty())throw new AssertionError("setting change did not invalidate model");
   JSONObject answers=new JSONObject().put("r0_category",new JSONObject().put("choice","life")).put("r0_importance",new JSONObject().put("noul",.8)).put("r0_next_step",new JSONObject().put("choice","organize").put("confidence",.9));
   if(s.completeBatch(batch,answers).length()!=0||s.status().getInt("analysisQueued")!=1)throw new AssertionError("stale lease was accepted");
   s.syncSystemModel(new JSONObject().put("id","fixture").put("revision",1));
   shell("settings put global "+key+" 2");shell("settings put global "+key+" 1");
   deadline=android.os.SystemClock.elapsedRealtime()+10000;while(!s.effectiveModel().isEmpty()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
   if(!s.effectiveModel().isEmpty())throw new AssertionError("A-B-A changes were not observed");
   s.syncSystemModel(new JSONObject().put("id","fixture").put("revision",1));
   MemoryStore restarted=new MemoryStore(getTargetContext(),()->{});restarted.startSystemSettings();
   if(!restarted.effectiveModel().isEmpty())throw new AssertionError("startup did not invalidate cached model");
   out.putString("result",new JSONObject().put("passed",true).put("staleLeaseRejected",true).put("abaObserved",true).put("startupInvalidated",true).toString());
  }catch(Throwable e){out.putString("failure",e.toString());}
  finally{if("null".equals(old))try{shell("settings delete global "+key);}catch(Exception ignored){}}
  finish(out.containsKey("failure")?0:-1,out);
 }
}
