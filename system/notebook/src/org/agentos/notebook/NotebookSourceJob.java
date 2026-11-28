package org.agentos.notebook;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.agentos.ai.AiClient;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONObject;

public final class NotebookSourceJob extends JobService {
    private static final String TAG = "NotebookSource";
    private static final int JOB_ID = 0x4e4258;
    private static final int MAX_ATTEMPTS = 3;
    private static final String ASSISTANT_PACKAGE = "org.agentos.assistant";
    private static volatile AssistantSupport support;
    private static boolean waitingForSourceExit;

    private record AssistantSupport(long lastUpdate, boolean available) {}
    private enum Result { CONTINUE, RETRY, WAIT_FOR_SOURCE_EXIT }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile JobParameters active;
    private Future<?> task;

    static void schedule(Context context) {
        enqueue(context, false);
    }

    static void resumeAfterLeavingSource(Context context) {
        try {
            if (!new NotebookStore(context).hasPendingSourceLookups()) return;
            new android.os.Handler(context.getMainLooper()).post(() -> {
                if (waitingForSourceExit) {
                    waitingForSourceExit = false;
                    context.getSystemService(JobScheduler.class).cancel(JOB_ID);
                }
                enqueue(context, true);
            });
        } catch (IOException unavailable) {
            Log.i(TAG, "Could not resume source lookup");
        }
    }

    static void onSave(Context context, NotebookStore.Entry entry) {
        if (!NotebookStore.SOURCE_PENDING.equals(entry.sourceResolution)) return;
        try {
            if (!lookupApiAvailable(context, new AiClient(context))) {
                markApiUnavailable(new NotebookStore(context), entry);
                return;
            }
        } catch (Exception unavailable) {
            Log.i(TAG, "Could not check source lookup: " + unavailable.getClass().getSimpleName());
        }
        if (!entry.sourceEvidence.isEmpty() || !entry.summary.isEmpty()) enqueue(context, true);
    }

    static boolean lookupApiAvailable(Context context, AiClient ai) throws Exception {
        long lastUpdate = context.getPackageManager().getPackageInfo(ASSISTANT_PACKAGE, 0).lastUpdateTime;
        var cached = support;
        if (cached != null && cached.lastUpdate() == lastUpdate) return cached.available();
        boolean available;
        try {
            ai.notebookStatus();
            available = true;
        } catch (IOException unavailable) {
            if (!"未知系统 AI 操作".equals(unavailable.getMessage())
                    && !"Unknown system AI operation".equals(unavailable.getMessage())) throw unavailable;
            available = false;
        }
        support = new AssistantSupport(lastUpdate, available);
        return available;
    }

    private static void enqueue(Context context, boolean saved) {
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (!saved && scheduler.getPendingJob(JOB_ID) != null) return;
            var job = new JobInfo.Builder(JOB_ID, new ComponentName(context, NotebookSourceJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
            if ((!saved || scheduler.enqueue(job.setExpedited(true).build(), new JobWorkItem.Builder().build())
                    != JobScheduler.RESULT_SUCCESS)
                    && scheduler.enqueue(job.setExpedited(false).build(), new JobWorkItem.Builder().build())
                    != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "Could not schedule source lookup");
            }
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not schedule source lookup: " + unavailable.getClass().getSimpleName());
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        waitingForSourceExit = false;
        active = params;
        task = worker.submit(() -> drain(params));
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

    private void drain(JobParameters params) {
        var store = new NotebookStore(this);
        var ai = new AiClient(this);
        var analysis = new NotebookAnalysis(this);
        try {
            Boolean supported = null;
            while (active == params) {
                JobWorkItem work = params.dequeueWork();
                if (work == null) return;
                NotebookStore.Entry entry;
                while (active == params && (entry = store.nextSourceLookup()) != null) {
                    if (supported == null) supported = lookupApiAvailable(this, ai);
                    if (!supported) {
                        markApiUnavailable(store, entry);
                        continue;
                    }
                    Result result = resolve(params, store, analysis, ai, entry);
                    if (result != Result.CONTINUE) {
                        finish(params, result);
                        return;
                    }
                }
                if (active == params) params.completeWork(work);
            }
        } catch (Exception unavailable) {
            Log.w(TAG, "Source queue unavailable: " + unavailable.getClass().getSimpleName());
            finish(params, Result.RETRY);
        }
    }

    private static void markApiUnavailable(NotebookStore store, NotebookStore.Entry entry) throws IOException {
        if (store.updateSourceLookup(entry, NotebookStore.SOURCE_UNAVAILABLE, null)) {
            Log.i(TAG, "Source lookup API unavailable on this Assistant build");
        }
    }

    private Result resolve(JobParameters params, NotebookStore store, NotebookAnalysis analysis,
            AiClient ai, NotebookStore.Entry entry) throws IOException {
        long start = android.os.SystemClock.elapsedRealtime();
        var platform = NotebookSocialSource.byPackage(entry.sourcePackage);
        try {
            String captured = Instant.ofEpochMilli(entry.capturedAt).atZone(ZoneId.of(entry.sourceZone)).toString();
            String image = analysis.encodeScreenshot(entry.imageUri);
            JSONObject evidence = entry.sourceEvidence.isEmpty() ? null : new JSONObject(entry.sourceEvidence);
            boolean earlyEvidence = evidence != null && evidence.optBoolean("early");
            if (evidence == null || (earlyEvidence && entry.sourceAttempts != 0)) {
                String prompt = "Read the main " + platform.displayName()
                        + " post. Return only this JSON shape: " + NotebookPostEvidence.SHAPE
                        + "\n" + NotebookPostEvidence.INSTRUCTIONS
                        + "\nScreenshot capture time, for calendar context: " + captured;
                var result = ai.image(prompt, image);
                evidence = NotebookPostEvidence.from(NotebookAnalysis.imageResponse(result.getString("text")), platform);
            }
            if (active != params) return Result.RETRY;
            var current = store.get(entry.id);
            if (current == null || current.savedAt != entry.savedAt
                    || current.sourceAttempts != entry.sourceAttempts
                    || !NotebookStore.SOURCE_PENDING.equals(current.sourceResolution)) return Result.CONTINUE;
            JSONObject source = ai.notebookResolveSocialSource(
                    entry.sourcePackage, evidence, entry.capturedAt, entry.sourceZone, image);
            String link = source.optString("url", "");
            if (active != params) return Result.RETRY;
            if (link.isEmpty() && earlyEvidence && entry.sourceAttempts == 0) {
                store.updateSourceLookup(entry, NotebookStore.SOURCE_PENDING, null);
                return Result.CONTINUE;
            }
            String state = link.isEmpty() ? NotebookStore.SOURCE_NOT_FOUND : NotebookStore.SOURCE_LINKED;
            if (store.updateSourceLookup(entry, state, link, evidence)) {
                String via = switch (source.optString("via")) {
                    case "native_share" -> " via native_share";
                    case "verified_web" -> " via verified_web";
                    default -> "";
                };
                report(platform, state + via, start, entry.savedAt);
            }
            return Result.CONTINUE;
        } catch (Exception failure) {
            if (active != params) return Result.RETRY;
            if ("NOTEBOOK_POST_FOREGROUND_SOURCE".equals(failure.getMessage())) {
                Log.i(TAG, "X source lookup deferred while X is in the foreground");
                return Result.WAIT_FOR_SOURCE_EXIT;
            }
            boolean retry = !(failure instanceof SecurityException) && entry.sourceAttempts + 1 < MAX_ATTEMPTS;
            String state = retry ? NotebookStore.SOURCE_PENDING : NotebookStore.SOURCE_UNAVAILABLE;
            if (!store.updateSourceLookup(entry, state, null)) return Result.CONTINUE;
            report(platform, state, start, entry.savedAt);
            return retry ? Result.RETRY : Result.CONTINUE;
        }
    }

    private void finish(JobParameters params, Result result) {
        android.os.Handler main = new android.os.Handler(getMainLooper());
        main.post(() -> {
            if (active != params) return;
            active = null;
            waitingForSourceExit = result == Result.WAIT_FOR_SOURCE_EXIT;
            jobFinished(params, true);
        });
    }

    private static void report(NotebookSocialSource platform, String state, long started, long savedAt) {
        Log.i(TAG, platform.displayName() + " source lookup " + state + " after "
                + (android.os.SystemClock.elapsedRealtime() - started) + " ms, sinceSaveMs="
                + (System.currentTimeMillis() - savedAt));
    }
}
