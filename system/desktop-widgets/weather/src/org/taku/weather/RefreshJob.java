package org.taku.weather;
public final class RefreshJob extends android.app.job.JobService {
 @Override public boolean onStartJob(android.app.job.JobParameters p){WeatherData.refresh(this);new Thread(()->{try{while(WeatherData.read(this).getBoolean("fetching"))Thread.sleep(250);}catch(Exception ignored){}jobFinished(p,false);}).start();return true;}
 @Override public boolean onStopJob(android.app.job.JobParameters p){return true;}
}
