package org.agentos.memory;
import android.app.job.*;
import android.content.*;
/** Local cleanup, durable analysis-job recovery and home feed card upkeep. Never observes screens or invokes a model itself. */
public final class MemoryMaintenance extends JobService {
 static void schedule(Context c){JobScheduler j=c.getSystemService(JobScheduler.class);if(j.getPendingJob(4101)==null||j.getPendingJob(4101).getIntervalMillis()!=900000L)j.schedule(new JobInfo.Builder(4101,new ComponentName(c,MemoryMaintenance.class)).setPeriodic(900000L).setPersisted(true).build());}
 @Override public boolean onStartJob(JobParameters p){new Thread(()->{try{MemoryStore s=MemoryStore.get(this);s.prune();s.changed();AnalysisJob.schedule(this);
  try{s.ensureVectors();}catch(Exception ignored){}
  long now=s.now(); // Release builds use the system clock; isolated debug tests can advance the existing test clock.
  ProactiveNotice.tidyLater(this,s,now);   // Home cards: retract expired items and read reactions without waiting for nightly organization.
  if(s.prefs.getBoolean("autoOrganize",true)&&organizeDue(s,now))Organizer.start(this,0);}finally{jobFinished(p,false);}},"memory-retention").start();return true;}
 /** Nightly at 23:00; if that hour was missed (phone off, asleep, offline) at the next chance after it. A delete or correction asks for a rebuild right away. After a failure, wait 30 minutes. */
 static boolean organizeDue(MemoryStore s,long nowMs){
  if(nowMs-s.prefs.getLong("organizeFailedAt",0)<30*60000L)return false;
  if(s.kvGet("rebuildPending","0").equals("1"))return true;
  java.time.ZonedDateTime now=java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault());
  java.time.ZonedDateTime boundary=now.withHour(23).withMinute(0).withSecond(0).withNano(0);if(now.getHour()<23)boundary=boundary.minusDays(1);
  return s.prefs.getLong("organizedAt",0)<boundary.toInstant().toEpochMilli();
 }
 @Override public boolean onStopJob(JobParameters p){return true;}
}
