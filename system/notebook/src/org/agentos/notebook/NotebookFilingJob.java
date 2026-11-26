package org.agentos.notebook;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class NotebookFilingJob extends JobService {
    private static final String TAG = "NotebookFiling";
    private static final int JOB_ID = 0x4e4246;
    private static final int BATCH_SIZE = 12;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile JobParameters active;
    private Future<?> task;

    static void schedule(Context context) {
        enqueue(context, false);
    }

    static void resume(Context context) {
        enqueue(context, true);
    }

    private static void enqueue(Context context, boolean onlyIfMissing) {
        try {
            var scheduler = context.getSystemService(JobScheduler.class);
            if (onlyIfMissing && scheduler.getPendingJob(JOB_ID) != null) return;
            var job = new JobInfo.Builder(JOB_ID, new ComponentName(context, NotebookFilingJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true);
            if (scheduler.enqueue(job.setExpedited(true).build(), new JobWorkItem.Builder().build())
                    != JobScheduler.RESULT_SUCCESS
                    && scheduler.enqueue(job.setExpedited(false).build(), new JobWorkItem.Builder().build())
                    != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "Could not schedule Notebook filing");
            }
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not schedule Notebook filing", unavailable);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        active = params;
        task = worker.submit(() -> filePending(params));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        active = null;
        if (task != null) task.cancel(true);
        return true;
    }

    @Override public void onDestroy() {
        active = null;
        worker.shutdownNow();
        super.onDestroy();
    }

    private void filePending(JobParameters params) {
        var store = new NotebookStore(this);
        var analysis = new NotebookAnalysis(this);
        try {
            while (active == params) {
                var work = params.dequeueWork();
                if (work == null) return;
                if (!fileBatch(params, store, analysis)) {
                    finishWithRetry(params);
                    return;
                }
                if (active == params) params.completeWork(work);
            }
        } catch (Exception unavailable) {
            Log.w(TAG, "Filing queue is unavailable: " + unavailable.getClass().getSimpleName());
            finishWithRetry(params);
        }
    }

    private boolean fileBatch(JobParameters params, NotebookStore store, NotebookAnalysis analysis)
            throws java.io.IOException {
        while (active == params) {
            var entries = store.pendingAnalysisEntries(BATCH_SIZE);
            boolean pending = false;
            for (var entry : entries) {
                try {
                    long started = SystemClock.elapsedRealtime();
                    var snapshot = entry.filingPending ? store.filingLists(entry.id)
                            : List.<NotebookStore.FilingList>of();
                    var filed = fileFirst(params, store, analysis, entry, snapshot, started);
                    if (active != params) return false;
                    var currentEntry = store.get(entry.id);
                    if (currentEntry == null) continue;
                    var details = analysis.identify(currentEntry, filed ? List.of() : snapshot);
                    if (active != params) return false;
                    store.enrich(entry.id, details, filed ? null : snapshot);
                    var current = store.get(entry.id);
                    if (current != null) {
                        Log.i(TAG, "Saved screenshot analysis entry=" + current.id.substring(0, 12)
                                + " analysisMs=" + (SystemClock.elapsedRealtime() - started)
                                + " sinceSaveMs=" + (System.currentTimeMillis() - current.savedAt)
                                + " expedited=" + params.isExpeditedJob());
                        NotebookSourceJob.onSave(this, current);
                        if (current.filingPending) pending = true;
                        else NotebookArrivalIntent.prepare(this, current);
                    }
                } catch (Exception unavailable) {
                    if (active != params) return false;
                    pending = true;
                    Log.i(TAG, "Notebook filing will retry: " + unavailable.getClass().getSimpleName());
                }
            }
            if (pending || entries.size() < BATCH_SIZE) return !pending;
        }
        return false;
    }

    private boolean fileFirst(JobParameters params, NotebookStore store, NotebookAnalysis analysis,
            NotebookStore.Entry entry, List<NotebookStore.FilingList> snapshot, long started) throws java.io.IOException {
        if (!entry.filingPending) return false;
        NotebookAnalysis.Identification details;
        try {
            details = analysis.file(entry, snapshot);
        } catch (Exception unavailable) {
            Log.i(TAG, "Fast filing route unavailable: " + unavailable.getClass().getSimpleName());
            return false;
        }
        if (details == null || details.listId() == null || active != params) return false;
        store.enrich(entry.id, details, snapshot);
        var current = store.get(entry.id);
        if (current == null) return false;
        NotebookSourceJob.onSave(this, current);
        if (current.filingPending) return false;
        Log.i(TAG, "Fast filing entry=" + entry.id.substring(0, 12)
                + " analysisMs=" + (SystemClock.elapsedRealtime() - started)
                + " sinceSaveMs=" + (System.currentTimeMillis() - entry.savedAt));
        return true;
    }

    private void finishWithRetry(JobParameters params) {
        new Handler(getMainLooper()).post(() -> {
            if (active != params) return;
            active = null;
            jobFinished(params, true);
        });
    }
}
