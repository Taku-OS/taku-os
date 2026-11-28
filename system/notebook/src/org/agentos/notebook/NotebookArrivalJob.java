package org.agentos.notebook;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class NotebookArrivalJob extends JobService {
    private static final String TAG = "NotebookArrivalJob";
    private static final int UPDATE = 0x4e4241;
    private static final int MAINTENANCE = 0x4e4242;
    private static final long INTERVAL_MS = 30 * 60_000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<JobParameters, Future<?>> active = new HashMap<>();

    static void enqueue(Context context) {
        var scheduler = context.getSystemService(JobScheduler.class);
        var job = new JobInfo.Builder(UPDATE, new ComponentName(context, NotebookArrivalJob.class))
                .setPersisted(true).build();
        if (scheduler.enqueue(job, new JobWorkItem.Builder().build()) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "Could not enqueue Notebook arrival refresh");
        }
    }

    static void resume(Context context) {
        var scheduler = context.getSystemService(JobScheduler.class);
        if (NotebookArrivals.enabled(context)) {
            if (scheduler.getPendingJob(MAINTENANCE) == null) {
                var job = new JobInfo.Builder(MAINTENANCE, new ComponentName(context, NotebookArrivalJob.class))
                        .setPeriodic(INTERVAL_MS).setPersisted(true).build();
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    Log.w(TAG, "Could not schedule Notebook arrival renewal");
                }
            }
        } else {
            scheduler.cancel(MAINTENANCE);
        }
        enqueue(context);
    }

    static void onEntryChanged(Context context) {
        if (NotebookArrivals.enabled(context)) enqueue(context);
    }

    @Override public boolean onStartJob(JobParameters params) {
        active.put(params, worker.submit(() -> refresh(params)));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        var task = active.remove(params);
        if (task != null) task.cancel(true);
        return true;
    }

    @Override public void onDestroy() {
        for (var task : active.values()) task.cancel(true);
        active.clear();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refresh(JobParameters params) {
        boolean retry = false;
        try {
            if (params.getJobId() == UPDATE) {
                while (!Thread.currentThread().isInterrupted()) {
                    var item = params.dequeueWork();
                    if (item == null) return;
                    NotebookArrivals.refresh(this);
                    params.completeWork(item);
                }
            } else {
                NotebookArrivals.refresh(this);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            retry = params.getJobId() == UPDATE && !NotebookArrivals.enabled(this);
            Log.w(TAG, "Notebook arrival refresh deferred: " + failure.getClass().getSimpleName());
        } finally {
            boolean reschedule = retry;
            getMainExecutor().execute(() -> {
                if (active.remove(params) != null) jobFinished(params, reschedule);
            });
        }
    }
}
