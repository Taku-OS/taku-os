package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Single owner for the poll booking journal and its durable effects. */
final class DiningPollBooking {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long WATCH_LIFETIME_MS = 12 * 60 * 60 * 1_000L;
    private static volatile Context owner;
    private static boolean recoveryScheduled;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("dining-poll-booking", Context.MODE_PRIVATE);
    }

    static JSONObject ledger(Context context) throws Exception {
        return new JSONObject(prefs(context).getString("watches", "{}"));
    }

    static synchronized void save(Context context, JSONObject watch) throws Exception {
        JSONObject all = ledger(context);
        all.put(watch.getString("id"), watch);
        saveLedger(context, all);
        if (owner == null) owner = context.getApplicationContext();
        if (needsEffectRecovery(watch)) scheduleRecovery(2_000);
    }

    static synchronized void claimCheckout(
            Context context, String id, Booking expected, JSONObject attempt, int user)
            throws Exception {
        JSONObject all = ledger(context);
        DiningBookingClaim.reserve(all, id, expected, attempt, user);
        saveLedger(context, all);
    }

    static synchronized void confirmCheckoutDispatch(
            Context context, String id, String operationToken) throws Exception {
        JSONObject all = ledger(context), watch = all.getJSONObject(id);
        JSONObject attempt = watch.getJSONObject("openTableSubmission");
        if (!operationToken.equals(attempt.optString("operationToken")))
            throw new IOException("BOOKING_SUBMISSION_UNKNOWN");
        attempt.put("outcome", "dispatched");
        saveLedger(context, all);
    }

    private static void saveLedger(Context context, JSONObject all) throws IOException {
        if (!prefs(context).edit().putString("watches", all.toString()).commit()) {
            throw new IOException("POLL_STATE_SAVE_FAILED");
        }
    }

    static JSONObject require(Context context, String id) throws Exception {
        JSONObject watch = ledger(context).optJSONObject(id);
        if (watch == null) throw new IOException("POLL_NOT_FOUND");
        return watch;
    }

    static State state(JSONObject watch) throws Exception {
        return DiningPollBookingCodec.state(watch.getJSONObject("state"));
    }

    static void execute(Runnable action) {
        IO.execute(action);
    }

    static <T> T serial(Callable<T> action) throws Exception {
        try {
            return IO.submit(action).get(35, TimeUnit.SECONDS);
        } catch (ExecutionException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            throw wrapped;
        }
    }

    static boolean needsEffectRecovery(JSONObject watch) {
        if (watch == null || !watch.optBoolean("active", true)) return false;
        try {
            State state = state(watch);
            return (watch.optBoolean("dispatching") && state.phase() == Phase.STARTING)
                    || (state.phase() == Phase.CONFIRMED && !watch.has("feedCardId"));
        } catch (Exception unreadable) {
            return false;
        }
    }

    static synchronized void activate(Context context) {
        if (owner == null) owner = context.getApplicationContext();
        scheduleRecovery(0);
    }

    private static synchronized void scheduleRecovery(long delayMs) {
        if (owner == null || recoveryScheduled) return;
        recoveryScheduled = true;
        if (delayMs <= 0) MAIN.post(DiningPollBooking::recoverEffects);
        else MAIN.postDelayed(DiningPollBooking::recoverEffects, delayMs);
    }

    private static void recoverEffects() {
        Context context = owner;
        if (context == null) {
            synchronized (DiningPollBooking.class) {
                recoveryScheduled = false;
            }
            return;
        }
        IO.execute(
                () -> {
                    boolean pending = false;
                    try {
                        JSONObject all = ledger(context);
                        for (Iterator<String> ids = all.keys(); ids.hasNext(); ) {
                            String id = ids.next();
                            JSONObject watch = null;
                            try {
                                watch = all.getJSONObject(id);
                                if (!watch.optBoolean("active", true)) continue;
                                State state = state(watch);
                                if (watch.optBoolean("dispatching")
                                        && state.phase() == Phase.STARTING) {
                                    watch.put(
                                            "state",
                                            DiningPollBookingCodec.state(
                                                    submissionUnknown(state, state.booking().key())
                                                            .state()));
                                    save(context, watch);
                                }
                                if (state.phase() == Phase.CONFIRMED) {
                                    if (!watch.has("feedCardId")) {
                                        DiningBookingContinuation.drain(context, watch);
                                        if (!watch.has("feedCardId")) pending = true;
                                    }
                                    DiningBookingCalendar.track(context, watch);
                                }
                            } catch (Exception failure) {
                                if (needsEffectRecovery(watch)) pending = true;
                                android.util.Log.w("TakuDining", "Poll recovery deferred", failure);
                            }
                        }
                    } catch (Exception failure) {
                        pending = true;
                        android.util.Log.w(
                                "TakuDining", "Booking effect recovery deferred", failure);
                    } finally {
                        synchronized (DiningPollBooking.class) {
                            if (pending && owner != null) {
                                MAIN.postDelayed(DiningPollBooking::recoverEffects, 2_000);
                            } else {
                                recoveryScheduled = false;
                            }
                        }
                    }
                });
    }

    static void submittedPoll(Context context, String intentId) {
        Context app = context.getApplicationContext();
        execute(
                () -> {
                    try {
                        watch(app, DiningPollSend.sentIntent(app, intentId));
                    } catch (Exception deferred) {
                        android.util.Log.w("TakuDining", "Poll watch deferred");
                    }
                });
    }

    static void restore(Context context) {
        Context app = context.getApplicationContext();
        activate(app);
        execute(
                () -> {
                    try {
                        for (JSONObject intent :
                                DiningPollSend.recentSentIntents(
                                        app, System.currentTimeMillis() - WATCH_LIFETIME_MS)) {
                            try {
                                watch(app, intent);
                            } catch (Exception skipped) {
                                android.util.Log.w("TakuDining", "Stored poll watch unavailable");
                            }
                        }
                    } catch (Exception deferred) {
                        android.util.Log.w("TakuDining", "Poll watch recovery deferred");
                    }
                });
    }

    static boolean current(JSONObject watch, long now) {
        long sentAt = watch.optLong("sentAt", watch.optLong("createdAt"));
        return sentAt > 0 && sentAt <= now && now - sentAt <= WATCH_LIFETIME_MS;
    }

    static JSONObject call(Context context, String method, JSONObject args) throws Exception {
        if (method.equals("dining/poll/state")) {
            PanelProtocol.exact(args, Set.of());
            return snapshot(context);
        }
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("POLL_ASYNC_REQUIRED");
        return serial(() -> operate(context, method, args));
    }

    static JSONObject operate(Context context, String method, JSONObject args) throws Exception {
        if (method.equals("dining/poll/watch")) {
            PanelProtocol.exact(args, Set.of("intentId"));
            return watch(
                    context,
                    DiningPollSend.sentIntent(context, DiningPollSendState.uuid(args, "intentId")));
        }
        Set<String> fields =
                switch (method) {
                    case "dining/poll/respond" -> Set.of("id", "key", "accepted");
                    case "dining/poll/retry-confirmation", "dining/poll/dismiss-confirmation" ->
                            Set.of("id", "key");
                    case "dining/poll/booking-form" -> Set.of("id", "key", "revision");
                    case "dining/poll/submit-booking-form" ->
                            Set.of("id", "key", "revision", "values");
                    case "dining/poll/inspect",
                                    "dining/poll/verify-date",
                                    "dining/poll/begin-submit",
                                    "dining/poll/reconcile",
                                    "dining/poll/reconcile-date" ->
                            Set.of("id");
                    default -> throw new IOException("UNKNOWN_POLL_METHOD");
                };
        PanelProtocol.exact(args, fields);
        JSONObject watch = require(context, args.getString("id"));
        if (!watch.optBoolean("active", true) || !DiningPollSendObserver.handles(watch))
            throw new IOException("REQUEST_EXPIRED");
        DiningPollSendObserver.activate(context, watch.getString("id"));
        return switch (method) {
            case "dining/poll/respond" ->
                    DiningPollSendObserver.respond(context, watch, args, false);
            case "dining/poll/retry-confirmation" ->
                    DiningPollSendObserver.respond(context, watch, args, true);
            case "dining/poll/dismiss-confirmation" ->
                    DiningPollBookingConfirmation.dismiss(context, watch, args.getString("key"));
            case "dining/poll/inspect" -> DiningBookingRead.inspect(context, watch);
            case "dining/poll/reconcile" -> DiningBookingRead.reconcile(context, watch, false);
            case "dining/poll/reconcile-date" -> DiningBookingRead.reconcile(context, watch, true);
            case "dining/poll/verify-date" ->
                    DiningBookingRead.forward(watch, "booking-verify-date");
            case "dining/poll/begin-submit" ->
                    DiningBookingRead.forward(watch, "booking-begin-submit");
            case "dining/poll/booking-form" -> {
                BookingDetails details =
                        bookingDetailsTarget(
                                context,
                                watch.getString("id"),
                                args.getString("key"),
                                args.getString("revision"));
                yield DiningPollBookingForm.schema(details.restaurant(), details.schedule())
                        .put("address", details.address());
            }
            case "dining/poll/submit-booking-form" ->
                    submitBookingForm(
                            context,
                            watch.getString("id"),
                            args.getString("key"),
                            args.getString("revision"),
                            args.getJSONObject("values"));
            default -> throw new IOException("UNKNOWN_POLL_METHOD");
        };
    }

    record BookingDetails(String restaurant, String address, DiningPollSchedule schedule) {}

    static BookingDetails bookingDetailsTarget(
            Context context, String id, String key, String revision) throws Exception {
        JSONObject watch = require(context, id);
        DiningPollIslandModel.validateAction(watch, key, "details", revision);
        State state = state(watch);
        return new BookingDetails(
                state.offer().restaurant().name(),
                DiningPollSendObserver.candidateAddress(watch, state),
                DiningPollSchedule.read(watch.optJSONObject("bookingSchedule"))
                        .availableAt(Instant.now()));
    }

    static JSONObject submitBookingForm(
            Context context, String id, String key, String revision, JSONObject values)
            throws Exception {
        JSONObject watch = require(context, id);
        DiningPollIslandModel.validateAction(watch, key, "details", revision);
        var known =
                DiningPollSchedule.read(watch.optJSONObject("bookingSchedule"))
                        .availableAt(Instant.now());
        DiningPollSchedule schedule = DiningPollBookingForm.complete(known, values, Instant.now());
        DiningPollSendObserver.bookWithDetails(
                context, watch, key, !revision.isEmpty(), schedule.when(), schedule.people());
        return require(context, id);
    }

    private static JSONObject watch(Context context, JSONObject intent) throws Exception {
        String intentId = intent.getString("intentId");
        JSONObject all = ledger(context);
        JSONObject existing = null;
        for (Iterator<String> ids = all.keys(); ids.hasNext(); ) {
            JSONObject candidate = all.getJSONObject(ids.next());
            if (!intentId.equals(candidate.optString("sendIntentId"))) continue;
            if (existing != null) throw new IOException("POLL_WATCH_AMBIGUOUS");
            existing = candidate;
        }
        if (existing != null) {
            if (current(existing, System.currentTimeMillis())
                    && existing.optBoolean("active", true))
                DiningPollSendObserver.activate(context, existing.getString("id"));
            return existing;
        }
        String thread = intent.getString("sourceThreadId"), group = intent.getString("groupName");
        String id = watchId(intentId);
        List<Restaurant> options = new ArrayList<>();
        for (String name : DiningPollBookingCodec.strings(intent.getJSONArray("options")))
            options.add(new Restaurant(key("restaurant", name), name));
        Meal meal =
                new Meal(
                        id,
                        thread,
                        key("group", thread, group),
                        group,
                        key("poll", intentId),
                        intent.getString("question"),
                        null,
                        0,
                        options);
        JSONObject watch =
                new JSONObject()
                        .put("id", id)
                        .put("active", true)
                        .put("state", DiningPollBookingCodec.state(State.start(meal)))
                        .put("outbox", new JSONArray())
                        .put("createdAt", System.currentTimeMillis())
                        .put("sentAt", intent.getLong("sentAt"))
                        .put("sourceReply", intent.getJSONObject("sourceReply"))
                        .put("sendIntentId", intentId)
                        .put("pollExecutionScreen", intent.getJSONObject("binding"))
                        .put("anchor", intent.getString("anchor"))
                        .put("sendConfirmed", true);
        if (!current(watch, System.currentTimeMillis()))
            throw new IOException("POLL_WATCH_EXPIRED");
        prefill(watch, intent);
        save(context, watch);
        DiningPollSendObserver.activate(context, id);
        return watch;
    }

    private static void prefill(JSONObject watch, JSONObject intent) {
        try {
            JSONObject result = NativeClient.exchange(DiningPollSendSource.readRequest(intent));
            DiningPollSendSource.verify(intent, result);
            JSONObject reply = result.getJSONObject("reply"), addresses = new JSONObject();
            State state = state(watch);
            for (Restaurant option : state.meal().restaurants()) {
                try {
                    addresses.put(
                            option.id(),
                            DiningPollReceiptTarget.candidateAddress(
                                    state.meal().sourceThreadId(),
                                    option.name(),
                                    intent.getJSONObject("sourceReply"),
                                    reply));
                } catch (Exception missing) {
                    // A missing branch address cannot authorize an automatic checkout for that
                    // candidate.
                }
            }
            watch.put("bookingAddresses", addresses);
            JSONObject card = reply.getJSONObject("card").getJSONObject("request");
            Instant now = Instant.now();
            DiningPollSchedule schedule =
                    DiningPollSchedule.prefill(card.optString("summary"), now);
            if (schedule.complete() && addresses.length() == state.meal().restaurants().size()) {
                watch.put("bookingSchedule", schedule.fields());
                watch.put(
                        "state",
                        DiningPollBookingCodec.state(
                                withSchedule(state, schedule.when(), schedule.people(), now)));
            }
        } catch (Exception unavailable) {
            android.util.Log.w("TakuDining", "Optional booking details unavailable");
        }
    }

    static void apply(Context context, JSONObject watch, Change change) throws Exception {
        JSONArray outbox = watch.getJSONArray("outbox");
        for (Effect effect : change.effects()) {
            outbox.put(
                    new JSONObject()
                            .put("key", effect.key())
                            .put("kind", effect.kind().name())
                            .put("decisionKey", effect.decisionKey())
                            .put("done", false));
        }
        watch.put("state", DiningPollBookingCodec.state(change.state()));
        save(context, watch);
        DiningBookingContinuation.drain(context, watch);
    }

    static void revoke(Context context, JSONObject watch) throws Exception {
        State state = state(watch);
        if (state.phase() != Phase.OFFERED) return;
        State next =
                new State(
                        state.meal(),
                        Phase.WATCHING,
                        state.votes(),
                        null,
                        null,
                        null,
                        state.offerSequence(),
                        state.declined(),
                        null,
                        false,
                        null,
                        false,
                        false);
        watch.put("state", DiningPollBookingCodec.state(next));
        save(context, watch);
    }

    private static JSONObject snapshot(Context context) throws Exception {
        JSONObject result =
                new JSONObject()
                        .put("capabilities", DiningCapabilities.snapshot())
                        .put("schema", "taku.dining-poll-booking/1")
                        .put("watches", ledger(context))
                        .put("observing", owner != null)
                        .put("visibleKey", DiningPollIsland.visibleKey(context))
                        .put("error", prefs(context).getString("error", ""))
                        .put("presentationHost", "systemui-island")
                        .put("separateWindow", false);
        ActivityManager.RunningTaskInfo visible = DiningPollUi.visible(context);
        if (visible != null && visible.topActivity != null) {
            result.put(
                    "visibleSource",
                    new JSONObject()
                            .put("package", visible.topActivity.getPackageName())
                            .put("activity", visible.topActivity.getClassName())
                            .put("displayId", DiningPollUi.display(visible))
                            .put("taskId", visible.taskId));
        }
        return result;
    }
}
