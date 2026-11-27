package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;

import android.app.ActivityManager;
import android.content.Context;
import android.os.UserManager;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Reads the existing reservation in the active task's broker-owned OpenTable screen. */
final class DiningBookingRead {
    private static final Set<String> BINDING =
            Set.of(
                    "bookingKey",
                    "bookingTaskId",
                    "executionThreadId",
                    "turnId",
                    "screenKey",
                    "generation",
                    "displayId",
                    "userId",
                    "taskId");

    private DiningBookingRead() {}

    static boolean action(JSONObject args) {
        return Set.of(
                        "booking-inspect",
                        "booking-verify-date",
                        "booking-begin-submit",
                        "booking-reconcile",
                        "booking-reconcile-date",
                        "booking-safe-input")
                .contains(args.optString("action"));
    }

    static JSONObject forward(JSONObject watch, String action) throws Exception {
        State state = DiningPollBooking.state(watch);
        if (state.booking() == null || state.booking().taskId() == null) {
            throw new IOException("BOOKING_NOT_STARTED");
        }
        JSONObject request =
                new JSONObject()
                        .put("op", "diningPollInput")
                        .put("action", action)
                        .put("watchId", watch.getString("id"))
                        .put("intentId", watch.getString("sendIntentId"))
                        .put("executionThreadId", state.meal().sourceThreadId())
                        .put("bookingTaskId", state.booking().taskId())
                        .put("bookingKey", state.booking().key());
        if (reconciling(action)) {
            JSONObject binding = retainedBinding(watch);
            if (binding != null) request.put("binding", binding);
        }
        return NativeClient.exchange(request);
    }

    private static JSONObject retainedBinding(JSONObject watch) throws Exception {
        JSONObject original = watch.optJSONObject("openTableSubmission");
        if (original == null) original = watch.optJSONObject("dateProof");
        if (original == null || original.optInt("displayId", -1) <= 0) return null;
        JSONObject result = new JSONObject();
        for (String field :
                Set.of("screenKey", "generation", "turnId", "taskId", "displayId", "userId")) {
            if (!original.has(field)) return null;
            result.put(field, original.get(field));
        }
        return result;
    }

    private static boolean reconciling(String action) {
        return "booking-reconcile".equals(action) || "booking-reconcile-date".equals(action);
    }

    static JSONObject inspect(Context context, JSONObject watch) throws Exception {
        if (DiningPollBooking.state(watch).phase() == Phase.CONFIRMED) {
            DiningBookingContinuation.drain(context, watch);
            return watch;
        }
        return forward(watch, "booking-inspect").getJSONObject("watch");
    }

    static JSONObject reconcile(Context context, JSONObject watch, boolean dateOnly)
            throws Exception {
        if (DiningPollBooking.state(watch).phase() == Phase.CONFIRMED) {
            DiningBookingContinuation.drain(context, watch);
            return watch;
        }
        JSONObject result =
                forward(watch, dateOnly ? "booking-reconcile-date" : "booking-reconcile");
        return dateOnly ? result : result.getJSONObject("watch");
    }

    static JSONObject nativeCall(Context context, JSONObject target) throws Exception {
        DiningPollSendState.cleanupTarget(target);
        String action = target.getString("action");
        boolean navigation = "booking-safe-input".equals(action), recovery = reconciling(action);
        if (recovery != Boolean.TRUE.equals(target.opt("readOnlyBooking"))
                || !recovery && target.has("readOnlyForeground")) throw changed();
        JSONObject watch =
                navigation
                        ? navigationWatch(context, target)
                        : DiningPollBooking.require(context, target.getString("watchId"));
        State state = DiningPollBooking.state(watch);
        if (!watch.optBoolean("active", true)
                || !navigation
                        && !watch.getString("sendIntentId").equals(target.getString("intentId")))
            throw changed();
        check(context, target, state);
        if (recovery
                && DiningBookingClaim.androidUser(watch, android.os.Process.myUid() / 100_000)
                        != target.getInt("userId")) throw changed();
        if (recovery && !Boolean.TRUE.equals(target.opt("readOnlyForeground"))) {
            JSONObject retained = retainedBinding(watch);
            if (retained == null) throw changed();
            for (var it = retained.keys(); it.hasNext(); ) {
                String field = it.next();
                if (!retained.get(field).equals(target.opt(field))) throw changed();
            }
        }
        return switch (action) {
            case "booking-verify-date", "booking-reconcile-date" ->
                    verifyDate(context, target, watch, state);
            case "booking-begin-submit" -> beginSubmit(context, target, watch, state);
            case "booking-inspect", "booking-reconcile" ->
                    inspectReceipt(context, target, watch, state);
            case "booking-safe-input" -> navigation(context, target, state);
            default -> throw new IOException("UNKNOWN_POLL_METHOD");
        };
    }

    private static JSONObject navigationWatch(Context context, JSONObject target) throws Exception {
        if (!target.getString("bookingTaskId").equals(target.opt("requestId"))) throw changed();
        JSONObject ledger = DiningPollBooking.ledger(context), found = null;
        for (var it = ledger.keys(); it.hasNext(); ) {
            JSONObject candidate = ledger.getJSONObject(it.next());
            if (!target.getString("bookingTaskId").equals(candidate.optString("taskId"))) continue;
            Booking booking = DiningPollBooking.state(candidate).booking();
            if (booking == null
                    || !booking.key().equals(target.optString("bookingKey"))
                    || !booking.meal()
                            .sourceThreadId()
                            .equals(target.optString("executionThreadId"))) continue;
            if (found != null) throw changed();
            found = candidate;
        }
        if (found == null) throw changed();
        return found;
    }

    private static JSONObject navigation(Context context, JSONObject target, State state)
            throws Exception {
        if (state.phase() != Phase.WORKING && state.phase() != Phase.UNKNOWN) throw changed();
        int display = target.getInt("displayId");
        DiningPollUi.read(
                ui -> {
                    DiningBookingNavigation.apply(
                            () -> DiningPollUi.root(ui, display, "com.opentable"),
                            () -> check(context, target, state),
                            target.getJSONObject("input"));
                    return null;
                });
        return new JSONObject().put("inputApplied", true);
    }

    private static JSONObject beginSubmit(
            Context context, JSONObject target, JSONObject watch, State state) throws Exception {
        if (state.phase() != Phase.WORKING
                || watch.has("openTableSubmission")
                || !state.meal().when().toInstant().isAfter(Instant.now())) {
            throw new IOException("BOOKING_SUBMISSION_UNAVAILABLE");
        }
        int display = target.getInt("displayId");
        var expected =
                new DiningBookingCheckout.Target(
                        state.booking().restaurant().name(),
                        watch.getString("bookingAddress"),
                        state.meal().when().toLocalDate(),
                        state.meal().when().toLocalTime(),
                        state.meal().people());
        JSONObject dateProof = watch.optJSONObject("dateProof");
        LocalDate provenDate =
                recentProof(dateProof, target, state, System.currentTimeMillis())
                        ? LocalDate.parse(dateProof.getString("date"))
                        : null;
        return DiningPollUi.read(
                ui -> {
                    var first =
                            DiningBookingCheckout.verify(
                                    DiningPollUi.root(ui, display, "com.opentable"),
                                    expected,
                                    provenDate);
                    Thread.sleep(100);
                    var stable =
                            DiningBookingCheckout.verify(
                                    DiningPollUi.root(ui, display, "com.opentable"),
                                    expected,
                                    provenDate);
                    if (!first.equals(stable))
                        throw new IOException("OPENTABLE_CHECKOUT_CONTROL_CHANGED");
                    check(context, target, state);
                    String watchId = watch.getString("id"),
                            nonce = target.getString("operationToken");
                    JSONObject attempt =
                            binding(target)
                                    .put("attemptedAt", System.currentTimeMillis())
                                    .put("operationToken", nonce)
                                    .put("outcome", "unknown");
                    DiningPollBooking.claimCheckout(
                            context, watchId, state.booking(), attempt, target.getInt("userId"));
                    try {
                        var current =
                                DiningBookingCheckout.verify(
                                        DiningPollUi.root(ui, display, "com.opentable"),
                                        expected,
                                        provenDate);
                        if (!stable.equals(current))
                            throw new IOException("OPENTABLE_CHECKOUT_CONTROL_CHANGED");
                        check(context, target, state);
                        if (!DiningBookingCheckout.click(current))
                            throw new IOException("BOOKING_SUBMISSION_UNKNOWN");
                        DiningPollBooking.confirmCheckoutDispatch(context, watchId, nonce);
                        return new JSONObject()
                                .put("submissionRecorded", true)
                                .put("clickDispatched", true)
                                .put("reservationVerified", false);
                    } catch (Exception failure) {
                        throw new IOException("BOOKING_SUBMISSION_UNKNOWN", failure);
                    }
                });
    }

    private static JSONObject binding(JSONObject target) throws Exception {
        JSONObject value = new JSONObject();
        for (String field : BINDING) value.put(field, target.get(field));
        return value.put("readOnlyBooking", target.optBoolean("readOnlyBooking"))
                .put("readOnlyForeground", target.optBoolean("readOnlyForeground"));
    }

    private static JSONObject verifyDate(
            Context context, JSONObject target, JSONObject watch, State state) throws Exception {
        int display = target.getInt("displayId");
        DiningPollDateProof.Facts facts =
                DiningPollUi.read(ui -> DiningPollDateProof.read(ui, display));
        check(context, target, state);
        if (!facts.date().equals(state.meal().when().toLocalDate())
                || !facts.time().equals(state.meal().when().toLocalTime())
                || facts.people() != state.meal().people()
                || !DiningRestaurantIdentity.sameName(
                        facts.restaurant(), state.booking().restaurant().name())) {
            throw new IOException("RESERVATION_DATE_TARGET_CHANGED");
        }
        DiningPollReceiptTarget.verifyAddress(watch.optString("bookingAddress"), facts.address());
        JSONObject proof =
                binding(target)
                        .put("date", facts.date().toString())
                        .put("time", facts.time().toString())
                        .put("restaurant", facts.restaurant())
                        .put("address", facts.address())
                        .put("people", facts.people())
                        .put("capturedAt", System.currentTimeMillis());
        watch.put("dateProof", proof);
        DiningPollBooking.save(context, watch);
        return new JSONObject().put("dateVerified", true).put("proof", proof);
    }

    private static JSONObject inspectReceipt(
            Context context, JSONObject target, JSONObject watch, State state) throws Exception {
        int display = target.getInt("displayId");
        DiningPollUi.VerifiedReceipt read;
        try {
            read = DiningPollUi.receipt(state, display);
        } catch (IOException failure) {
            if (!"RECEIPT_FULL_DATE_REQUIRED".equals(failure.getMessage())) throw failure;
            JSONObject proof = watch.optJSONObject("dateProof");
            if (!recentProof(proof, target, state, System.currentTimeMillis())) {
                throw new IOException("RECEIPT_FULL_DATE_REQUIRED");
            }
            read = DiningPollUi.receipt(state, display, LocalDate.parse(proof.getString("date")));
            if (!DiningRestaurantIdentity.sameName(
                            read.receipt().restaurantName(), proof.getString("restaurant"))
                    || !read.address().equals(proof.getString("address"))
                    || read.receipt().people() != proof.getInt("people")
                    || !read.receipt()
                            .when()
                            .toLocalTime()
                            .toString()
                            .equals(proof.getString("time"))) {
                throw new IOException("RESERVATION_DATE_CONTEXT_CHANGED");
            }
        }
        DiningPollReceiptTarget.verifyAddress(watch.optString("bookingAddress"), read.address());
        check(context, target, state);
        Change confirmation = confirmed(state, read.receipt(), Instant.now());
        watch.put("restaurantAddress", read.address())
                .put("existingCardId", read.existingCardId())
                .put("openTableReceiptBinding", binding(target));
        DiningPollBooking.apply(context, watch, confirmation);
        return new JSONObject().put("watch", watch);
    }

    private static boolean recentProof(JSONObject proof, JSONObject target, State state, long now) {
        if (proof == null
                || proof.optBoolean("readOnlyBooking") != target.optBoolean("readOnlyBooking")
                || proof.optBoolean("readOnlyForeground")
                        != target.optBoolean("readOnlyForeground")) return false;
        for (String field : BINDING) {
            if (proof.opt(field) == null || !proof.opt(field).equals(target.opt(field)))
                return false;
        }
        long capturedAt = proof.optLong("capturedAt");
        return state.booking().key().equals(proof.optString("bookingKey"))
                && state.meal().when().toLocalDate().toString().equals(proof.optString("date"))
                && state.meal().when().toLocalTime().toString().equals(proof.optString("time"))
                && capturedAt >= state.booking().consentAt().toEpochMilli()
                && capturedAt <= now
                && now - capturedAt <= 5 * 60_000;
    }

    private static void check(Context context, JSONObject target, State state) throws Exception {
        if (Thread.currentThread().isInterrupted()
                || new File(target.getString("cancelPath")).exists()
                || context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) {
            throw new IOException("BOOKING_RECEIPT_CANCELLED");
        }
        boolean foreground = Boolean.TRUE.equals(target.opt("readOnlyForeground"));
        int display = target.optInt("displayId", -1), user = target.optInt("userId", -1);
        if (state.booking() == null
                || !state.booking().key().equals(target.optString("bookingKey"))
                || !java.util.Objects.equals(
                        state.booking().taskId(), target.optString("bookingTaskId"))
                || !state.meal().sourceThreadId().equals(target.optString("executionThreadId"))
                || !"com.opentable".equals(target.opt("packageName"))
                || target.optBoolean("legacy", true)
                || !Set.of(Phase.WORKING, Phase.UNKNOWN).contains(state.phase())
                || display < 0
                || foreground != (display == 0)
                || user != android.os.Process.myUid() / 100_000
                || foreground
                        && (!target.optBoolean("readOnlyBooking")
                                || !context.getSystemService(UserManager.class).isUserForeground()))
            throw changed();
        ActivityManager.RunningTaskInfo first = null;
        int visibleOpenTable = 0;
        for (ActivityManager.RunningTaskInfo task :
                context.getSystemService(ActivityManager.class).getRunningTasks(100)) {
            if (DiningPollUi.display(task) != display
                    || task.topActivity == null
                    || foreground && !task.isVisible()) continue;
            if (first == null) first = task;
            if (!foreground) break;
            String topApp = task.topActivity.getPackageName(),
                    baseApp = task.baseActivity == null ? "" : task.baseActivity.getPackageName();
            if ("com.opentable".equals(topApp) || "com.opentable".equals(baseApp))
                visibleOpenTable++;
        }
        if (first == null) throw new IOException("BOOKING_RECEIPT_NOT_VISIBLE");
        if (first.taskId != target.getInt("taskId")
                || first.getClass().getField("userId").getInt(first) != user
                || !"com.opentable".equals(first.topActivity.getPackageName())
                || foreground
                        && (visibleOpenTable != 1
                                || first.baseActivity == null
                                || !"com.opentable".equals(first.baseActivity.getPackageName())))
            throw changed();
    }

    private static IOException changed() {
        return new IOException("BOOKING_RECEIPT_TARGET_CHANGED");
    }
}
