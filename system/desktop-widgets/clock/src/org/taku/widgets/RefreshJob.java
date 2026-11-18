package org.taku.widgets;
public final class RefreshJob extends android.app.job.JobService {
 @Override public boolean onStartJob(android.app.job.JobParameters p){Widgets.updateAll(this);return false;}
 @Override public boolean onStopJob(android.app.job.JobParameters p){return false;}
}
