package org.agentos.assistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Saved-calendar to managed original-conversation Uber preparation. Never operates Uber itself. */
final class DiningBookingRide {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private static Context owner;
    private static boolean running;
    private static volatile boolean checking;

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dining-booking-ride", 0);
    }

    private static JSONObject entries(Context c) throws Exception {
        return new JSONObject(prefs(c).getString("entries", "{}"));
    }

    private static synchronized void save(Context c, JSONObject e) throws Exception {
        JSONObject all = entries(c);
        all.put(e.getString("id"), e);
        if (!prefs(c).edit().putString("entries", all.toString()).commit())
            throw new IOException("DINING_RIDE_STATE_SAVE_FAILED");
    }

    private static JSONObject calendars(Context c) throws Exception {
        return new JSONObject(
                c.getSharedPreferences("dining-booking-calendar", 0).getString("entries", "{}"));
    }

    static synchronized void track(Context c, JSONObject calendar) throws Exception {
        if (!calendar.has("eventId")
                || !calendar.optBoolean("feedPublished")
                || !Set.of("saved", "dismissed").contains(calendar.optString("phase"))) return;
        JSONObject watch = DiningBookingCalendar.current(c, calendar.getString("watchId"));
        if (!latest(c, watch)) return;
        String fingerprint = DiningBookingRidePolicy.identity(calendar, watch),
                id = "ride:" + fingerprint;
        if (!entries(c).has(id))
            save(
                    c,
                    new JSONObject()
                            .put("id", id)
                            .put("fingerprint", fingerprint)
                            .put("watchId", watch.getString("id"))
                            .put("calendarEntryId", calendar.getString("id"))
                            .put("receiptFingerprint", calendar.getString("fingerprint"))
                            .put("calendarId", calendar.getLong("calendarId"))
                            .put("eventId", calendar.getLong("eventId"))
                            .put("calendarFeedCardId", calendar.getString("feedCardId"))
                            .put(
                                    "notBefore",
                                    Math.max(
                                            calendar.optLong("createdAt"),
                                            DiningBookingCalendarPolicy.receiptAt(watch)))
                            .put("meal", new JSONObject(calendar.getJSONObject("meal").toString()))
                            .put("phase", "waiting_details")
                            .put("key", UUID.randomUUID().toString())
                            .put("rideRequested", false)
                            .put("createdAt", System.currentTimeMillis()));
        ACTIVE.add(id);
        owner = c.getApplicationContext();
        if (!running) {
            running = true;
            MAIN.post(DiningBookingRide::tick);
        }
    }

    private static void tick() {
        MAIN.postDelayed(DiningBookingRide::tick, 1800);
        if (checking || owner == null) return;
        checking = true;
        IO.execute(
                () -> {
                    synchronized (DiningBookingRide.class) {
                        try {
                            JSONObject all = entries(owner);
                            boolean shown = false;
                            for (String id : new ArrayList<>(ACTIVE))
                                try {
                                    JSONObject e = all.getJSONObject(id);
                                    verify(owner, e);
                                    reconcile(owner, e);
                                    if (DiningRideInputActivity.isShowing()
                                            || !available(owner)
                                            || !Set.of(
                                                            "waiting_details",
                                                            "offered",
                                                            "failed",
                                                            "input_required",
                                                            "collecting_input",
                                                            "input_ready",
                                                            "ended")
                                                    .contains(e.optString("phase"))) continue;
                                    if (!e.has("calendarDispatchCursor")) readClick(owner, e);
                                    if (!e.has("calendarDispatchCursor")) continue;
                                    if (Set.of("waiting_details", "offered", "failed")
                                                    .contains(e.optString("phase"))
                                            && !viewedAndLeftDetails(owner, e)) continue;
                                    if (e.optString("phase").equals("waiting_details")) {
                                        e.put("phase", "offered")
                                                .put("key", UUID.randomUUID().toString());
                                        save(owner, e);
                                    }
                                    DiningBookingRideIsland.show(owner, e);
                                    shown = true;
                                    break;
                                } catch (Exception stale) {
                                    android.util.Log.w(
                                            "TakuDining",
                                            "Ride continuation pending: " + stale.getMessage());
                                }
                            if (!shown) DiningBookingRideIsland.hide(owner);
                        } catch (Exception failure) {
                            android.util.Log.w("TakuDining", "Ride observation unavailable");
                        } finally {
                            checking = false;
                        }
                    }
                });
    }

    static synchronized JSONObject call(Context c, String method, JSONObject args)
            throws Exception {
        Set<String> fields =
                switch (method) {
                    case "dining/poll/ride-state" -> Set.of();
                    case "dining/poll/ride-open" -> Set.of("id");
                    case "dining/poll/ride-respond" -> Set.of("id", "key", "action");
                    case "dining/poll/ride-validate-plan" ->
                            Set.of("id", "pickup", "departureLocal");
                    case "dining/poll/ride-resume-input" ->
                            Set.of("id", "taskId", "turnId", "requestId");
                    case "dining/poll/ride-input-read", "dining/poll/ride-input-cancel" ->
                            Set.of("id", "key");
                    case "dining/poll/ride-input-save", "dining/poll/ride-input-submit" ->
                            Set.of("id", "key", "pickup", "departureLocal");
                    default -> throw new IOException("UNKNOWN_POLL_METHOD");
                };
        PanelProtocol.exact(args, fields);
        if (method.equals("dining/poll/ride-state")) {
            JSONObject all = entries(c);
            for (var ids = all.keys(); ids.hasNext(); )
                try {
                    JSONObject e = all.getJSONObject(ids.next());
                    reconcile(c, e);
                } catch (Exception unavailable) {
                    android.util.Log.w("TakuDining", "Ride status reconciliation deferred");
                }
            return new JSONObject()
                    .put("entries", entries(c))
                    .put("rideRequested", false)
                    .put("finalOrderRequiresUser", true)
                    .put("presentationHost", "systemui-island")
                    .put("inputHost", "native-activity");
        }
        JSONObject watch = DiningBookingCalendar.current(c, args.getString("id")),
                calendar = findCalendar(c, watch);
        track(c, calendar);
        JSONObject e =
                entries(c)
                        .getJSONObject("ride:" + DiningBookingRidePolicy.identity(calendar, watch));
        verify(c, e);
        reconcile(c, e);
        if (method.startsWith("dining/poll/ride-input-")) {
            String key = args.getString("key");
            DiningRideInputState.require(e, key);
            if (method.equals("dining/poll/ride-input-read")) return inputDescription(e);
            if (method.equals("dining/poll/ride-input-cancel")) {
                DiningRideInputState.cancel(e, key);
                save(c, e);
                DiningBookingRideIsland.hide(c);
                return e;
            }
            String pickup = args.getString("pickup"), departure = args.getString("departureLocal");
            if (method.equals("dining/poll/ride-input-save")) {
                DiningRideInputState.draft(e, key, pickup, departure);
                save(c, e);
                return inputDescription(e);
            }
            boolean fresh =
                    DiningRideInputState.accept(
                            e, key, pickup, departure, System.currentTimeMillis());
            if (fresh) {
                e.put(
                        "prompt",
                        DiningBookingRidePolicy.prompt(
                                e, e.getJSONObject("input").optString("mode", "confirm")));
                save(c, e);
            }
            if (!fresh && !Set.of("input_ready", "submitting").contains(e.optString("phase")))
                return e;
            if (!available(c)) throw new IOException("DINING_PHONE_BUSY");
            return submit(c, e, watch);
        }
        if (method.equals("dining/poll/ride-resume-input")) {
            if (!available(c)) throw new IOException("DINING_PHONE_BUSY");
            String thread =
                    watch.getJSONObject("state").getJSONObject("meal").getString("sourceThreadId");
            JSONObject recovery = DiningRideInputRecovery.check(c, e, args, thread);
            e.put("inputRecovery", recovery);
            archiveAttempt(e);
            e.put("phase", "input_required");
            return beginInput(c, e, watch, "resume-input");
        }
        if (method.equals("dining/poll/ride-validate-plan"))
            return DiningRidePlan.validate(
                    e.getJSONObject("meal"),
                    args.getString("pickup"),
                    args.getString("departureLocal"),
                    System.currentTimeMillis());
        if (method.equals("dining/poll/ride-open")) {
            if (!Set.of(
                            "waiting_details",
                            "offered",
                            "failed",
                            "collecting_input",
                            "input_required",
                            "input_cancelled",
                            "input_ready",
                            "ended")
                    .contains(e.optString("phase"))) throw new IOException("REQUEST_EXPIRED");
            if (!available(c)) throw new IOException("DINING_PHONE_BUSY");
            readClick(c, e);
            if (!e.has("calendarDispatchCursor"))
                throw new IOException("DINING_RIDE_EVENT_DETAILS_REQUIRED");
            if (Set.of("waiting_details", "offered", "failed").contains(e.optString("phase"))
                    && !viewedAndLeftDetails(c, e))
                throw new IOException("DINING_RIDE_EVENT_DETAILS_REQUIRED");
            if (e.optString("phase").equals("waiting_details"))
                e.put("phase", "offered").put("key", UUID.randomUUID().toString());
            save(c, e);
            DiningBookingRideIsland.show(c, e);
            return e;
        }
        String action = args.getString("action");
        DiningBookingRidePolicy.action(e, args.getString("key"), action);
        if (action.equals("dismiss")) {
            e.put("phase", "dismissed").put("key", UUID.randomUUID().toString());
            save(c, e);
            DiningBookingRideIsland.hide(c);
            return e;
        }
        if (!available(c)) throw new IOException("DINING_PHONE_BUSY");
        if (Set.of("offered", "failed").contains(e.optString("phase"))
                && !viewedAndLeftDetails(c, e))
            throw new IOException("DINING_RIDE_EVENT_DETAILS_REQUIRED");
        DiningBookingRideIsland.hide(c);
        if (e.has("terminal")
                || "failed".equals(e.optString("phase")) && e.has("requestId") && !e.has("taskId"))
            archiveAttempt(e);
        return beginInput(c, e, watch, action);
    }

    private static JSONObject beginInput(Context c, JSONObject e, JSONObject watch, String mode)
            throws Exception {
        String thread =
                watch.getJSONObject("state").getJSONObject("meal").getString("sourceThreadId");
        priorPreparations(c, e, thread);
        if (!"input_ready".equals(e.optString("phase")))
            DiningRideInputState.begin(e, thread, mode, System.currentTimeMillis());
        else DiningRideInputState.require(e, e.getJSONObject("input").getString("key"));
        save(c, e);
        DiningBookingRideIsland.hide(c);
        Intent form =
                new Intent(c, DiningRideInputActivity.class)
                        .putExtra("watchId", e.getString("watchId"))
                        .putExtra("inputKey", e.getJSONObject("input").getString("key"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        MAIN.post(() -> c.startActivity(form));
        return inputDescription(e);
    }

    private static JSONObject inputDescription(JSONObject e) throws Exception {
        JSONObject input = e.getJSONObject("input"), meal = e.getJSONObject("meal");
        return new JSONObject()
                .put("id", e.getString("watchId"))
                .put("key", input.getString("key"))
                .put("phase", e.getString("phase"))
                .put("sourceThreadId", e.getString("sourceThreadId"))
                .put("revision", input.getLong("revision"))
                .put("restaurant", meal.getString("restaurant"))
                .put("zone", meal.getString("zone"))
                .put("pickup", input.optString("pickup"))
                .put("departureLocal", input.optString("departureLocal"))
                .put("status", input.getString("status"))
                .put("priorOrderStatus", "not-attested");
    }

    private static JSONObject submit(Context c, JSONObject e, JSONObject watch) throws Exception {
        String
                thread =
                        watch.getJSONObject("state")
                                .getJSONObject("meal")
                                .getString("sourceThreadId"),
                prompt = e.getString("prompt"),
                requestId = e.getString("requestId");
        // The actual native answers and stable request ID were committed before publication.
        DiningRidePlan.validate(
                e.getJSONObject("meal"),
                e.getJSONObject("ridePlan").getString("pickup"),
                e.getJSONObject("ridePlan").getString("departureLocal"),
                System.currentTimeMillis());
        e.put("phase", "submitting");
        save(c, e);
        DiningBookingRideIsland.hide(c);
        try {
            PanelNativeServices services = new PanelNativeServices(c);
            JSONObject result =
                    services.getSubmissions().lookup(requestId, thread, prompt, null, null, true);
            if ("missing".equals(result.optString("state")))
                result =
                        services.getSubmissions()
                                .submit(
                                        requestId,
                                        thread,
                                        prompt,
                                        () -> {
                                            verify(c, e);
                                            services.checkSubmit(thread);
                                            priorPreparations(c, e, thread);
                                            DiningRideInputRecovery.checkIdle(thread);
                                        },
                                        before -> {
                                            JSONObject options =
                                                    new JSONObject()
                                                            .put("includeScreenshot", false)
                                                            .put("responseStyle", "concise")
                                                            .put("composerRequestId", requestId)
                                                            .put("diningRideKey", requestId);
                                            return TaskRequests.submit(
                                                    prompt,
                                                    "Prepare a ride to dinner",
                                                    null,
                                                    thread,
                                                    options,
                                                    id -> {
                                                        before.persist(id);
                                                        e.put("taskId", id).put("phase", "queued");
                                                        save(c, e);
                                                        if (!c.getSharedPreferences(
                                                                        "agent-settings", 0)
                                                                .edit()
                                                                .putString("task-" + thread, id)
                                                                .commit())
                                                            throw new IOException(
                                                                    "DINING_RIDE_TASK_POINTER_SAVE_FAILED");
                                                    });
                                        });
            e.put("submission", result);
            if ("notSent".equals(result.optString("state")) && !e.has("taskId")) {
                e.put("phase", "failed")
                        .put("error", result.optString("code", "DINING_RIDE_NOT_STARTED"));
                save(c, e);
                return e;
            }
            if (!"queued".equals(result.optString("state"))
                    || !result.optString("taskId").matches("[0-9]{1,20}")
                    || e.has("taskId") && !result.getString("taskId").equals(e.getString("taskId")))
                throw new IOException("DINING_RIDE_SUBMISSION_UNKNOWN");
            e.put("taskId", result.getString("taskId")).put("phase", "queued");
            save(c, e);
            if (!c.getSharedPreferences("agent-settings", 0)
                    .edit()
                    .putString("task-" + thread, e.getString("taskId"))
                    .commit()) throw new IOException("DINING_RIDE_TASK_POINTER_SAVE_FAILED");
            MAIN.post(
                    () -> {
                        try {
                            c.getContentResolver()
                                    .call(
                                            Uri.parse("content://org.agentos.agent.home-assistant"),
                                            "assistant/resume",
                                            new JSONObject()
                                                    .put("threadId", thread)
                                                    .put("home", false)
                                                    .toString(),
                                            null);
                        } catch (Exception unavailable) {
                            android.util.Log.w(
                                    "TakuDining", "Ride task retained; presentation unavailable");
                        }
                    });
            return e;
        } catch (Exception unknown) {
            e.put("phase", "unknown").put("error", unknown.getMessage());
            save(c, e);
            throw unknown;
        }
    }

    private static void archiveAttempt(JSONObject e) throws Exception {
        if (!e.has("taskId") && !e.has("requestId")) return;
        if (e.has("taskId") && !e.has("terminal") && !e.has("unpublishedAudit"))
            throw DiningRidePreparationHistory.blocked();
        JSONArray history = e.optJSONArray("attempts");
        if (history == null) history = new JSONArray();
        JSONObject previous = new JSONObject();
        for (String field :
                List.of(
                        "taskId",
                        "requestId",
                        "submission",
                        "terminal",
                        "prompt",
                        "ridePlan",
                        "inputRecovery",
                        "input",
                        "nativeEvidence",
                        "unpublishedAudit")) if (e.has(field)) previous.put(field, e.get(field));
        previous.put("sourceThreadId", e.getString("sourceThreadId"))
                .put("orderStatus", "not-attested");
        history.put(previous);
        e.put("attempts", history);
        for (String field :
                List.of(
                        "taskId",
                        "requestId",
                        "submission",
                        "terminal",
                        "prompt",
                        "inputRecovery",
                        "input",
                        "nativeEvidence",
                        "unpublishedAudit")) e.remove(field);
    }

    private static void reconcile(Context c, JSONObject e) throws Exception {
        if ("awaiting_review".equals(e.optString("phase"))) {
            refreshHandoff(c, e);
            return;
        }
        if (!Set.of("queued", "running", "submitting", "unknown").contains(e.optString("phase")))
            return;
        String task = e.optString("taskId");
        if (!task.matches("[0-9]{1,20}")) {
            if (e.optString("requestId").isBlank() || e.optString("prompt").isBlank()) return;
            JSONObject receipt =
                    new PanelNativeServices(c)
                            .getSubmissions()
                            .lookup(
                                    e.getString("requestId"),
                                    e.getString("sourceThreadId"),
                                    e.getString("prompt"),
                                    null,
                                    null,
                                    true);
            if ("queued".equals(receipt.optString("state"))
                    && receipt.optString("taskId").matches("[0-9]{1,20}")) {
                task = receipt.getString("taskId");
                e.put("taskId", task).put("submission", receipt).put("phase", "queued");
                save(c, e);
            } else if ("missing".equals(receipt.optString("state"))) {
                e.put("phase", "input_ready");
                save(c, e);
                return;
            } else if ("notSent".equals(receipt.optString("state"))) {
                e.put("phase", "failed")
                        .put("submission", receipt)
                        .put("error", receipt.optString("code"));
                save(c, e);
                return;
            } else return;
        }
        File dir = new File(TaskRuntime.TASKS, task), done = new File(dir, "done");
        if (!new File(dir, "ready").isFile()) {
            recoverUnpublished(c, e, dir);
            return;
        }
        JSONObject state =
                new JSONObject(
                        new String(
                                record(new File(dir, "state.json"), 64000),
                                StandardCharsets.UTF_8));
        if (!done.isFile()) {
            if (task.equals(state.optString("id"))
                    && "running".equals(state.optString("state"))
                    && !"running".equals(e.optString("phase"))) {
                e.put("phase", "running");
                save(c, e);
            }
            return;
        }
        if (done.length() > 32) throw DiningRidePreparationHistory.blocked();
        String terminal =
                new String(Files.readAllBytes(done.toPath()), StandardCharsets.UTF_8).strip();
        JSONObject job =
                new JSONObject(
                        new String(
                                record(new File(dir, "request.json"), 64000),
                                StandardCharsets.UTF_8));
        JSONObject audit =
                DiningRidePreparationHistory.audit(
                        e, job, state, terminal, e.getString("sourceThreadId"));
        File runtimeFile = new File(dir, "runtime-info.json");
        JSONObject runtime = new JSONObject();
        if (runtimeFile.isFile()) {
            runtime =
                    new JSONObject(new String(record(runtimeFile, 16000), StandardCharsets.UTF_8));
            if (!task.equals(runtime.optString("taskId"))
                    || !e.getString("sourceThreadId").equals(runtime.optString("threadId"))
                    || runtime.optString("turnId").isBlank())
                throw DiningRidePreparationHistory.blocked();
            audit.put("turnId", runtime.getString("turnId")).put("runtimeConfirmed", true);
        } else if ("completed".equals(terminal)) throw DiningRidePreparationHistory.blocked();
        else audit.put("runtimeConfirmed", false);
        audit.put("at", System.currentTimeMillis());
        e.put("terminal", audit)
                .put("phase", "ended")
                .put("key", UUID.randomUUID().toString())
                .put("orderStatus", "not-attested");
        File evidenceFile = new File(dir, "dining-ride-evidence.json");
        if (evidenceFile.isFile()) {
            JSONObject evidence =
                    new JSONObject(new String(record(evidenceFile, 64000), StandardCharsets.UTF_8));
            if ("taku.dining-ride-evidence/1".equals(evidence.optString("schema"))
                    && task.equals(evidence.optString("taskId"))
                    && e.getString("sourceThreadId").equals(evidence.optString("threadId"))
                    && runtime.has("turnId")
                    && runtime.getString("turnId").equals(evidence.optString("turnId"))
                    && evidence.optString("screenKey").matches("task-[a-f0-9]{20}")
                    && evidence.optString("generation").matches("[a-f0-9-]{36}")
                    && evidence.optInt("displayId", -1) > 0
                    && evidence.optInt("userId", -1) >= 0) {
                e.put("nativeEvidence", evidence);
                JSONObject handoff = evidence.optJSONObject("handoff");
                if (handoff != null
                        && "awaiting_review".equals(handoff.optString("status"))
                        && "com.ubercab".equals(handoff.optString("packageName")))
                    e.put("phase", "awaiting_review");
            }
        }
        if ("awaiting_review".equals(e.optString("phase"))) refreshHandoff(c, e);
        save(c, e);
    }

    private static void recoverUnpublished(Context c, JSONObject e, File directory)
            throws Exception {
        try {
            PanelNativeServices services = new PanelNativeServices(c);
            JSONObject receipt =
                    services.getSubmissions()
                            .lookup(
                                    e.getString("requestId"),
                                    e.getString("sourceThreadId"),
                                    e.getString("prompt"),
                                    null,
                                    null,
                                    true);
            byte[] jobBytes = record(new File(directory, "request.json"), 64000),
                    stateBytes = record(new File(directory, "state.json"), 64000);
            JSONObject job = new JSONObject(new String(jobBytes, StandardCharsets.UTF_8)),
                    state = new JSONObject(new String(stateBytes, StandardCharsets.UTF_8));
            JSONObject audit =
                    DiningRidePreparationHistory.unpublished(
                            e,
                            receipt,
                            job,
                            state,
                            new File(directory, "ready").exists(),
                            new File(directory, "runtime-info.json").exists(),
                            new File(directory, "done").exists(),
                            new File(directory, "cancel").exists());
            DiningRideInputRecovery.checkIdle(e.getString("sourceThreadId"));
            // Recheck the irreversible ready/runtime/done markers after the native idle proof.
            receipt =
                    services.getSubmissions()
                            .lookup(
                                    e.getString("requestId"),
                                    e.getString("sourceThreadId"),
                                    e.getString("prompt"),
                                    null,
                                    null,
                                    true);
            DiningRidePreparationHistory.unpublished(
                    e,
                    receipt,
                    job,
                    state,
                    new File(directory, "ready").exists(),
                    new File(directory, "runtime-info.json").exists(),
                    new File(directory, "done").exists(),
                    new File(directory, "cancel").exists());
            audit.put("requestSha256", digest(jobBytes))
                    .put("stateSha256", digest(stateBytes))
                    .put("checkedAt", System.currentTimeMillis());
            e.put("unpublishedAudit", audit);
            archiveAttempt(e);
            e.put("phase", "input_required");
            DiningRideInputState.begin(
                    e, e.getString("sourceThreadId"), "resume-input", System.currentTimeMillis());
            save(c, e);
        } catch (Exception uncertain) {
            e.put("phase", "unknown").put("error", "DINING_RIDE_UNPUBLISHED_UNCONFIRMED");
            save(c, e);
        }
    }

    private static void refreshHandoff(Context c, JSONObject e) throws Exception {
        JSONObject availability;
        try {
            JSONObject workspaces =
                    NativeClient.exchange(
                            new JSONObject()
                                    .put("op", "workspaces")
                                    .put("action", "list")
                                    .put("threadId", e.getString("sourceThreadId")));
            availability =
                    DiningRidePreparationHistory.handoffAvailability(
                            e.getJSONObject("nativeEvidence"),
                            workspaces.getJSONArray("screens"),
                            System.currentTimeMillis());
        } catch (Exception unavailable) {
            availability =
                    new JSONObject()
                            .put("status", "unavailable")
                            .put("previewAvailable", false)
                            .put("quoteCurrent", false)
                            .put("checkedAt", System.currentTimeMillis());
        }
        e.put("handoffAvailability", availability);
        save(c, e);
    }

    private static JSONArray priorPreparations(Context c, JSONObject current, String thread)
            throws Exception {
        JSONArray audits = new JSONArray();
        JSONObject all = entries(c);
        JSONArray attempts = current.optJSONArray("attempts");
        if (attempts != null)
            for (int i = 0; i < attempts.length(); i++) {
                JSONObject terminal = attempts.getJSONObject(i).optJSONObject("terminal");
                if (terminal != null) audits.put(new JSONObject(terminal.toString()));
                JSONObject unpublished =
                        attempts.getJSONObject(i).optJSONObject("unpublishedAudit");
                if (unpublished != null) {
                    File directory = new File(TaskRuntime.TASKS, unpublished.getString("taskId"));
                    if (new File(directory, "ready").exists()
                            || new File(directory, "runtime-info.json").exists()
                            || new File(directory, "done").exists()
                            || !digest(record(new File(directory, "request.json"), 64000))
                                    .equals(unpublished.optString("requestSha256"))
                            || !digest(record(new File(directory, "state.json"), 64000))
                                    .equals(unpublished.optString("stateSha256")))
                        throw DiningRidePreparationHistory.blocked();
                    audits.put(new JSONObject(unpublished.toString()));
                }
            }
        for (var ids = all.keys(); ids.hasNext(); ) {
            JSONObject previous = all.getJSONObject(ids.next());
            if (!DiningRidePreparationHistory.relevant(previous, current)) continue;
            String task = DiningRidePreparationHistory.task(previous);
            File directory = new File(TaskRuntime.TASKS, task);
            File ready = new File(directory, "ready"), done = new File(directory, "done");
            if (!ready.isFile() || !done.isFile() || done.length() > 32)
                throw DiningRidePreparationHistory.blocked();
            String terminal =
                    new String(Files.readAllBytes(done.toPath()), StandardCharsets.UTF_8).strip();
            byte[] jobBytes = record(new File(directory, "request.json"), 64_000),
                    stateBytes = record(new File(directory, "state.json"), 64_000);
            JSONObject audit =
                    DiningRidePreparationHistory.audit(
                            previous,
                            new JSONObject(new String(jobBytes, StandardCharsets.UTF_8)),
                            new JSONObject(new String(stateBytes, StandardCharsets.UTF_8)),
                            terminal,
                            thread);
            audit.put("requestFile", new File(directory, "request.json").getAbsolutePath())
                    .put("stateFile", new File(directory, "state.json").getAbsolutePath())
                    .put("requestSha256", digest(jobBytes))
                    .put("stateSha256", digest(stateBytes))
                    .put("checkedAt", System.currentTimeMillis());
            audits.put(audit);
        }
        if (audits.length() > 0) DiningRideInputRecovery.checkIdle(thread);
        return audits;
    }

    private static byte[] record(File file, long limit) throws Exception {
        if (!file.isFile() || file.length() <= 0 || file.length() > limit)
            throw DiningRidePreparationHistory.blocked();
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > limit) throw DiningRidePreparationHistory.blocked();
        return bytes;
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JSONObject findCalendar(Context c, JSONObject watch) throws Exception {
        JSONObject all = calendars(c);
        String id = watch.getString("id") + ":" + DiningBookingCalendarPolicy.identity(watch);
        JSONObject calendar = all.optJSONObject(id);
        if (calendar == null) throw new IOException("DINING_RIDE_CALENDAR_NOT_SAVED");
        return calendar;
    }

    private static void verify(Context c, JSONObject e) throws Exception {
        JSONObject watch = DiningBookingCalendar.current(c, e.getString("watchId")),
                calendar = findCalendar(c, watch);
        if (!latest(c, watch)
                || !e.getString("fingerprint")
                        .equals(DiningBookingRidePolicy.identity(calendar, watch)))
            throw new IOException("REQUEST_EXPIRED");
        JSONObject meal = calendar.getJSONObject("meal");
        if (meal.getLong("start") <= System.currentTimeMillis())
            throw new IOException("DINING_RESERVATION_EXPIRED");
        boolean found = false;
        for (var event : DiningReservationCalendar.existing(c, meal))
            if (event.eventId() == e.getLong("eventId")
                    && event.calendarId() == e.getLong("calendarId")) found = true;
        if (!found) throw new IOException("DINING_CALENDAR_EVENT_CHANGED");
    }

    private static boolean latest(Context c, JSONObject watch) throws Exception {
        return watch.getString("id")
                .equals(
                        DiningBookingCalendarPolicy.latestWatch(
                                DiningPollBooking.ledger(c), watch.getString("feedCardId")));
    }

    private static boolean available(Context c) throws Exception {
        if (c.getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        Bundle result =
                c.getContentResolver()
                        .call(
                                Uri.parse("content://org.agentos.agent.home-assistant"),
                                "assistant/get",
                                null,
                                null);
        JSONObject
                envelope = new JSONObject(result == null ? "{}" : result.getString("result", "{}")),
                home = envelope.optJSONObject("result");
        return home != null
                && !home.optBoolean("working")
                && !home.optBoolean("needsUser")
                && !home.optBoolean("sending")
                && home.isNull("question");
    }

    /**
     * Opening a URI only arms observation. A matching detail screen must be witnessed before its
     * exit can offer an Uber. Failed or in-transition accessibility reads do not prove an exit.
     */
    private static boolean viewedAndLeftDetails(Context c, JSONObject entry) throws Exception {
        if (!entry.has("calendarDispatchCursor")) return false;
        DiningBookingRideUi.Probe probe =
                DiningBookingRideUi.details(c, entry.getJSONObject("meal"));
        String dispatch = entry.getString("calendarDispatchCursor");
        if (probe.matched() && !dispatch.equals(entry.optString("detailDispatchCursor"))) {
            entry.put("detailDispatchCursor", dispatch)
                    .put("detailCheckedAt", System.currentTimeMillis())
                    .put("detailTaskId", probe.taskId());
            save(c, entry);
        }
        return DiningBookingRidePolicy.detailsWereLeft(entry, probe.reason());
    }

    private static void readClick(Context c, JSONObject e) throws Exception {
        if (e.has("calendarDispatchCursor")) return;
        Bundle b =
                c.getContentResolver()
                        .call(
                                Uri.parse("content://org.agentos.agent.home-feed"),
                                "feed/get",
                                new JSONObject()
                                        .put("id", e.getString("calendarFeedCardId"))
                                        .toString(),
                                null);
        JSONObject
                card =
                        new JSONObject(b == null ? "{}" : b.getString("result", "{}"))
                                .getJSONObject("result")
                                .getJSONObject("card"),
                intent =
                        card.getJSONObject("actions")
                                .getJSONObject("open-calendar")
                                .getJSONObject("intent");
        if (!"open-app".equals(intent.optString("type"))
                || !"com.google.android.calendar".equals(intent.optString("package"))
                || !("content://com.android.calendar/events/" + e.getLong("eventId"))
                        .equals(intent.optString("uri")))
            throw new IOException("DINING_RIDE_CALENDAR_CARD_CHANGED");
        Uri uri =
                Uri.parse("content://org.agentos.agent.home-feed/events")
                        .buildUpon()
                        .appendQueryParameter("after", e.optString("feedCursor", "0"))
                        .appendQueryParameter("follow", "0")
                        .appendQueryParameter("cardId", e.getString("calendarFeedCardId"))
                        .appendQueryParameter("op", "action")
                        .build();
        int count = 0, size = 0;
        String checkpoint = null;
        JSONObject matched = null;
        try (var input = c.getContentResolver().openInputStream(uri);
                var reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        Objects.requireNonNull(input), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                size += line.length();
                if (++count > 4096 || size > 2 * 1024 * 1024)
                    throw new IOException("DINING_RIDE_EVENT_WINDOW_TOO_LARGE");
                JSONObject event = new JSONObject(line);
                if ("checkpoint".equals(event.optString("type")))
                    checkpoint = event.getString("through");
                if (DiningBookingRidePolicy.click(event, e, System.currentTimeMillis()))
                    matched = event;
            }
        }
        if (checkpoint != null) e.put("feedCursor", checkpoint);
        if (matched != null)
            e.put("calendarDispatchCursor", matched.getString("cursor"))
                    .put("calendarOpenedAt", matched.getLong("at"));
        if (checkpoint != null || matched != null) save(c, e);
    }
}
