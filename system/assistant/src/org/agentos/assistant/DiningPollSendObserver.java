package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Passively reads the exact sent poll while the broker owns the observation. */
final class DiningPollSendObserver {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POLLS = Executors.newSingleThreadExecutor();
    private static final DiningObservationActivation ACTIVE = new DiningObservationActivation();
    private static volatile Context owner;

    static synchronized void activate(Context context, String id) {
        ACTIVE.activate(id);
        DiningPollBooking.activate(context);
        if (owner != null) return;
        owner = context.getApplicationContext();
        MAIN.post(DiningPollSendObserver::tick);
    }

    static boolean handles(JSONObject watch) {
        return watch.has("sendIntentId") && watch.has("pollExecutionScreen");
    }

    static JSONObject respond(Context context, JSONObject watch, JSONObject args, boolean retry)
            throws Exception {
        String key = args.getString("key");
        if (!retry && !args.getBoolean("accepted")) {
            if (DiningPollBookingConfirmation.busy())
                throw new IOException("CONFIRMATION_IN_PROGRESS");
            DiningPollConfirmationState.validate(watch, key, false);
            DiningPollBooking.apply(
                    context,
                    watch,
                    DiningPollBookingFlow.respond(
                            DiningPollBooking.state(watch), key, false, Instant.now()));
            DiningPollIsland.hide(context, key);
            return watch;
        }
        return accept(context, watch, key, retry, null, 0);
    }

    static void bookWithDetails(
            Context context,
            JSONObject watch,
            String key,
            boolean retry,
            ZonedDateTime when,
            int people)
            throws Exception {
        schedule(DiningPollBooking.state(watch), key, when, people, Instant.now());
        accept(context, watch, key, retry, when, people);
    }

    private static JSONObject accept(
            Context context,
            JSONObject watch,
            String key,
            boolean retry,
            ZonedDateTime when,
            int people)
            throws Exception {
        if (when == null && !DiningPollBooking.state(watch).meal().hasSchedule())
            throw new IOException("MEAL_DETAILS_REQUIRED");
        DiningPollConfirmationGate.Ticket ticket =
                DiningPollBookingConfirmation.begin(context, watch, key, retry);
        try {
            JSONObject proof = readForConfirmation(watch, when != null);
            update(context, watch, proof);
            State state = DiningPollBooking.state(watch);
            if (state.offer() == null || !state.offer().key().equals(key))
                throw new IOException("REQUEST_EXPIRED");
            String address = candidateAddress(watch, state);
            if (when == null) {
                JSONObject addresses = watch.optJSONObject("bookingAddresses");
                if (addresses == null
                        || !address.equals(addresses.optString(state.offer().restaurant().id())))
                    throw new IOException("BOOKING_ADDRESS_NOT_PREVIEWED");
            }
            if (when != null) state = schedule(state, key, when, people, Instant.now());
            Change booking = DiningPollBookingFlow.respond(state, key, true, Instant.now());
            watch.put("bookingAddress", address);
            DiningPollBooking.apply(context, watch, booking);
            DiningPollBookingConfirmation.finish(context, watch, ticket);
            return watch;
        } catch (Exception failure) {
            DiningPollBookingConfirmation.failed(context, watch, ticket, failure);
            throw failure;
        }
    }

    static String candidateAddress(JSONObject watch, State state) throws Exception {
        JSONObject source = watch.getJSONObject("sourceReply");
        JSONObject reply =
                NativeClient.exchange(
                                new JSONObject()
                                        .put("op", "mainReply")
                                        .put("action", "read")
                                        .put("threadId", state.meal().sourceThreadId())
                                        .put("turnId", source.getString("turnId"))
                                        .put("replyId", source.getString("replyId")))
                        .getJSONObject("reply");
        return DiningPollReceiptTarget.candidateAddress(
                state.meal().sourceThreadId(), state.offer().restaurant().name(), source, reply);
    }

    private static JSONObject readForConfirmation(JSONObject watch, boolean returningFromDetails)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8_000;
        while (true) {
            try {
                return read(watch);
            } catch (IOException unavailable) {
                String code = unavailable.getMessage();
                boolean transientRead =
                        "DINING_POLL_OBSERVER_BUSY".equals(code)
                                || "POLL_EDITOR_BUSY".equals(code)
                                || (returningFromDetails
                                        && ("DINING_POLL_SCREEN_UNAVAILABLE".equals(code)
                                                || "DINING_POLL_FOREGROUND_NOT_VISIBLE".equals(code)
                                                || "POLL_SOURCE_NOT_VISIBLE".equals(code)));
                if (SystemClock.elapsedRealtime() >= deadline || !transientRead) throw unavailable;
                Thread.sleep(160);
            }
        }
    }

    private static JSONObject read(JSONObject watch) throws Exception {
        JSONObject binding = watch.getJSONObject("pollExecutionScreen");
        JSONObject target = new JSONObject();
        for (String field :
                List.of("screenKey", "generation", "turnId", "taskId", "displayId", "userId"))
            target.put(field, binding.get(field));
        return NativeClient.exchange(
                new JSONObject()
                        .put("op", "diningPollInput")
                        .put("action", "observe")
                        .put("intentId", watch.getString("sendIntentId"))
                        .put("executionThreadId", binding.getString("executionThreadId"))
                        .put("binding", target));
    }

    private static void tick() {
        Context context = owner;
        POLLS.execute(
                () -> {
                    try {
                        if (!context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                                && !DiningPollBookingConfirmation.busy()) scan(context);
                    } catch (Exception unavailable) {
                        android.util.Log.w("TakuDining", "Poll observation deferred");
                    } finally {
                        MAIN.postDelayed(DiningPollSendObserver::tick, 2_000);
                    }
                });
    }

    private static void scan(Context context) throws Exception {
        JSONObject all = DiningPollBooking.ledger(context);
        for (Iterator<String> ids = all.keys(); ids.hasNext(); ) {
            JSONObject watch = all.getJSONObject(ids.next());
            if (DiningPollBookingConfirmation.busy()) return;
            if (!ACTIVE.contains(watch.getString("id"))
                    || !handles(watch)
                    || !watch.optBoolean("active", true)) continue;
            if (!DiningPollBooking.current(watch, System.currentTimeMillis())) {
                DiningPollIsland.hide(context, DiningPollConfirmationState.offer(watch));
                continue;
            }
            State state = DiningPollBooking.state(watch);
            if (!List.of(Phase.WATCHING, Phase.OFFERED).contains(state.phase())) continue;
            if (state.offer() != null
                    && DiningPollBookingDetailsActivity.preserves(
                            watch.getString("id"), state.offer().key())) continue;
            if (state.meal().hasSchedule()
                    && !state.meal().when().toInstant().isAfter(Instant.now())) {
                DiningPollIsland.hide(context, DiningPollConfirmationState.offer(watch));
                continue;
            }
            try {
                scanWatch(context, watch);
            } catch (Exception unavailable) {
                android.util.Log.w("TakuDining", "One poll observation was deferred");
            }
        }
    }

    private static void scanWatch(Context context, JSONObject snapshot) throws Exception {
        String id = snapshot.getString("id");
        JSONObject watch =
                DiningPollBooking.serial(
                        () -> {
                            JSONObject current = DiningPollBooking.require(context, id);
                            DiningPollBookingConfirmation.recover(context, current);
                            if (!current.optBoolean("sendConfirmed")) {
                                recordSent(
                                        current,
                                        DiningPollSend.sentIntent(
                                                context, current.getString("sendIntentId")));
                                DiningPollBooking.save(context, current);
                            }
                            return current;
                        });
        boolean refreshingRetry = DiningPollConfirmationState.refreshableFailure(watch);
        if (DiningPollConfirmationState.blocks(watch) && !refreshingRetry) {
            DiningPollIsland.hide(context, DiningPollConfirmationState.offer(watch));
            return;
        }

        long revision = DiningPollBookingConfirmation.revision();
        if (!DiningPollBookingConfirmation.beginRead()) return;
        JSONObject proof;
        try {
            proof = read(watch);
        } catch (Exception unavailable) {
            DiningPollBookingConfirmation.endRead();
            DiningPollBooking.serial(
                    () -> {
                        JSONObject current = DiningPollBooking.require(context, id);
                        if (!DiningPollBookingConfirmation.sameRevision(revision)) return null;
                        String key = DiningPollConfirmationState.offer(current);
                        if (DiningPollBookingDetailsActivity.preserves(id, key)) return null;
                        if (key.isEmpty()
                                || DiningPollSendState.keepBusyOffer(
                                        unavailable.getMessage(),
                                        current.optLong("lastObservedAt"),
                                        System.currentTimeMillis(),
                                        sameTaskVisible(context, current))) {
                            return null;
                        }
                        if (!DiningPollConfirmationState.blocks(current))
                            DiningPollBooking.revoke(context, current);
                        DiningPollIsland.hide(context, key);
                        return null;
                    });
            return;
        }
        DiningPollBookingConfirmation.endRead();
        DiningPollBooking.serial(
                () -> {
                    JSONObject current = DiningPollBooking.require(context, id);
                    if (!DiningPollBookingConfirmation.sameRevision(revision)) return null;
                    if (refreshingRetry) {
                        // Refresh just the view of the same poll; only an explicit Retry may
                        // attempt a booking.
                        if (!DiningPollConfirmationState.refreshableFailure(current)) return null;
                        observation(context, current, proof);
                        DiningPollBooking.save(context, current);
                    } else {
                        if (DiningPollConfirmationState.blocks(current)) return null;
                        update(context, current, proof);
                    }
                    String currentKey = DiningPollConfirmationState.offer(current);
                    if (!currentKey.isEmpty()) {
                        if (sameTaskVisible(context, current)) {
                            MAIN.post(() -> DiningPollIsland.render(context, id));
                        } else DiningPollIsland.hide(context, currentKey);
                    }
                    return null;
                });
    }

    private static boolean sameTaskVisible(Context context, JSONObject watch) {
        try {
            JSONObject bound = watch.getJSONObject("pollExecutionScreen");
            JSONObject intent = DiningPollSend.sentIntent(context, watch.getString("sendIntentId"));
            bound =
                    DiningPollSendState.recentObservationScreen(
                            watch, intent, System.currentTimeMillis());
            if (bound == null) return false;
            if (!context.getSystemService(UserManager.class).isUserForeground()
                    || android.os.Process.myUid() / 100_000 != bound.getInt("userId")) return false;
            Set<Integer> displays = new HashSet<>();
            for (ActivityManager.RunningTaskInfo task :
                    context.getSystemService(ActivityManager.class).getRunningTasks(100)) {
                int display = DiningPollUi.display(task);
                if (task.topActivity == null || !displays.add(display)) continue;
                if (task.taskId == bound.getInt("taskId")
                        && display == bound.getInt("displayId")
                        && "com.whatsapp".equals(task.topActivity.getPackageName())
                        && task.isVisible()
                        && task.getClass().getField("userId").getInt(task)
                                == bound.getInt("userId")) {
                    return true;
                }
            }
        } catch (Exception unavailable) {
            return false;
        }
        return false;
    }

    private static void recordSent(JSONObject watch, JSONObject intent) throws Exception {
        watch.put("anchor", intent.getString("anchor")).put("sendConfirmed", true);
    }

    private static Instant observation(Context context, JSONObject watch, JSONObject proof)
            throws Exception {
        if (!watch.getString("sendIntentId").equals(proof.getString("intentId"))
                || !watch.getString("anchor").equals(proof.getString("anchor"))) {
            throw new IOException("POLL_MESSAGE_CHANGED");
        }
        long capturedAt = proof.getLong("capturedAt");
        JSONObject intent = DiningPollSend.sentIntent(context, watch.getString("sendIntentId"));
        if (DiningPollSendState.usesQuestionCode(intent)) {
            watch.put(
                    "lastObservationScreen", DiningPollSendState.observationScreen(intent, proof));
        }
        watch.put("lastObservedAt", capturedAt)
                .put("groupFingerprint", proof.getString("groupFingerprint"));
        return Instant.ofEpochMilli(capturedAt);
    }

    private static void update(Context context, JSONObject watch, JSONObject proof)
            throws Exception {
        State state = DiningPollBooking.state(watch);
        if (!List.of(Phase.WATCHING, Phase.OFFERED).contains(state.phase())) return;
        Instant at = observation(context, watch, proof);
        JSONArray values = proof.getJSONArray("counts");
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) counts.add(values.getInt(i));
        long sequence = state.votes() == null ? 1 : state.votes().sequence() + 1;
        Votes votes =
                new Votes(
                        state.meal().groupId(),
                        state.meal().pollId(),
                        state.meal().pollQuestion(),
                        state.meal().restaurants().stream().map(Restaurant::id).toList(),
                        counts,
                        null,
                        true,
                        sequence,
                        new Evidence(
                                Source.WHATSAPP_POLL_UI,
                                proof.getString("anchor") + ":" + sequence,
                                at));
        Change observed = observe(state, votes, Instant.now());
        DiningPollBooking.apply(context, watch, observed);
        if (state.offer() != null
                && (observed.state().offer() == null
                        || !state.offer().key().equals(observed.state().offer().key()))) {
            DiningPollIsland.hide(context, state.offer().key());
        }
    }
}
