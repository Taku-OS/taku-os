package org.agentos.memory;

import android.app.job.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable, bounded batches. Recording never waits for a model or owns a model task. */
public final class AnalysisJob extends JobService {
 private static final int FIRST=4102,SECOND=4103;
 private static final Object LOCK=new Object();
 private static int active=-1;
 private final AtomicBoolean stopped=new AtomicBoolean();

 static void schedule(Context context){
  synchronized(LOCK){
   try{
    MemoryStore store=MemoryStore.get(context);long now=System.currentTimeMillis(),delay=store.queueDelay(now);
    JobScheduler jobs=context.getSystemService(JobScheduler.class);
    if(delay<0){for(int id:new int[]{FIRST,SECOND})if(id!=active||!store.prefs.getBoolean("analysisEnabled",false))jobs.cancel(id);return;}
    int target=active==FIRST?SECOND:FIRST;
    scheduleAt(context,jobs,target,now+Math.max(1000,delay));
   }catch(Exception unavailable){/* Database remains the source of truth; maintenance recovers scheduling. */}
  }
 }
 private static void scheduleAt(Context c,JobScheduler jobs,int id,long at){
  JobInfo existing=jobs.getPendingJob(id);
  if(existing!=null&&existing.getExtras().getLong("dueAt",Long.MAX_VALUE)<=at)return;
  PersistableBundle extras=new PersistableBundle();extras.putLong("dueAt",at);
  jobs.schedule(new JobInfo.Builder(id,new ComponentName(c,AnalysisJob.class)).setMinimumLatency(Math.max(1000,at-System.currentTimeMillis())).setPersisted(true).setExtras(extras).build());
 }
 @Override public boolean onStartJob(JobParameters parameters){
  synchronized(LOCK){if(active!=-1)return false;active=parameters.getJobId();}
  stopped.set(false);
  new Thread(()->runBatch(parameters),"memory-analysis-batch").start();return true;
 }
 private void runBatch(JobParameters parameters){
  MemoryStore store=MemoryStore.get(this);JSONObject batch=null;
  try{
   if(getSystemService(UserManager.class).isUserUnlocked()&&!stopped.get()){
    JSONObject selected=BrokerClient.call("systemModel",new JSONObject().put("model",store.followsSystem()?"":store.prefs.getString("model","")));
    store.syncSystemModel(selected);
    batch=store.claimBatch(System.currentTimeMillis());
    if(batch!=null&&!stopped.get()){
     int count=batch.getJSONArray("items").length();
     JSONObject request=new JSONObject().put("token",batch.getString("token")).put("generation",batch.getLong("generation")).put("model",batch.getString("model")).put("count",count).put("questions",BrokerClient.batchQuestions(count));
     JSONObject answers=BrokerClient.call("decideBatch",request).getJSONObject("answers");
     if(stopped.get())store.retryBatch(batch.getString("token"),System.currentTimeMillis());
     else{
      JSONArray offers=store.completeBatch(batch,answers);
      JSONObject cfg=store.config();boolean accepted=cfg.getBoolean("analysisEnabled")&&cfg.getLong("analysisGeneration")==batch.getLong("generation")&&cfg.getString("model").equals(batch.getString("model"));
      store.prefs.edit().putString("analysisLastStatus",accepted?"batch_done:"+count:"stale").apply();
      ObservationService.suggest(offers);
     }
    }else if(batch!=null)store.retryBatch(batch.getString("token"),System.currentTimeMillis());
   }
  }catch(Exception unavailable){
   if(batch!=null)try{store.retryBatch(batch.getString("token"),System.currentTimeMillis());}catch(Exception ignored){}
   store.prefs.edit().putString("analysisLastStatus","backoff").putLong("analysisLast",System.currentTimeMillis()+15000).apply();
  }finally{
   synchronized(LOCK){
    try{
     long delay=store.queueDelay(System.currentTimeMillis());
     // Persist the successor before completing this job, including lease recovery after interruption.
     if(delay>=0){if(!getSystemService(UserManager.class).isUserUnlocked())delay=Math.max(delay,60000);int next=parameters.getJobId()==FIRST?SECOND:FIRST;scheduleAt(this,getSystemService(JobScheduler.class),next,System.currentTimeMillis()+Math.max(1000,delay));}
    }catch(Exception unavailable){/* Durable queue and maintenance retry after storage becomes available. */}
    finally{active=-1;jobFinished(parameters,false);}
   }
   store.changed();
  }
 }
 @Override public boolean onStopJob(JobParameters parameters){stopped.set(true);return true;}
}
