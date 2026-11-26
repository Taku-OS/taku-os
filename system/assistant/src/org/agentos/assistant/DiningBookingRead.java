package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;

import android.app.ActivityManager;
import android.content.Context;

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
        return Set.of("booking-inspect", "booking-verify-date", "booking-begin-submit")
                .contains(args.optString("action"));
    }

    static JSONObject forward(JSONObject watch, String action) throws Exception {
        State state = DiningPollBooking.state(watch);
        if (state.booking() == null || state.booking().taskId() == null) {
            throw new IOException("BOOKING_NOT_STARTED");
        }
        return NativeClient.exchange(
                new JSONObject()
                        .put("op", "diningPollInput")
                        .put("action", action)
                        .put("watchId", watch.getString("id"))
                        .put("intentId", watch.getString("sendIntentId"))
                        .put("executionThreadId", state.meal().sourceThreadId())
                        .put("bookingTaskId", state.booking().taskId())
                        .put("bookingKey", state.booking().key()));
    }

    static JSONObject inspect(Context context, JSONObject watch) throws Exception {
        if (DiningPollBooking.state(watch).phase() == Phase.CONFIRMED) {
            DiningBookingContinuation.drain(context, watch);
            return watch;
        }
        return forward(watch, "booking-inspect").getJSONObject("watch");
    }

    static JSONObject nativeCall(Context context, JSONObject target) throws Exception {
        DiningPollSendState.cleanupTarget(target);
        JSONObject watch = DiningPollBooking.require(context, target.getString("watchId"));
        State state = DiningPollBooking.state(watch);
        if (!watch.getString("sendIntentId").equals(target.getString("intentId"))
                || !watch.optBoolean("active", true)) {
            throw new IOException("BOOKING_RECEIPT_TARGET_CHANGED");
        }
        check(context, target, state);
        return switch (target.getString("action")) {
            case "booking-verify-date" -> verifyDate(context, target, watch, state);
            case "booking-begin-submit" -> beginSubmit(context, target, watch, state);
            case "booking-inspect" -> inspectReceipt(context, target, watch, state);
            default -> throw new IOException("UNKNOWN_POLL_METHOD");
        };
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
                    JSONObject latest = DiningPollBooking.require(context, watch.getString("id"));
                    if (latest.has("openTableSubmission")
                            || !state.booking().equals(DiningPollBooking.state(latest).booking()))
                        throw new IOException("BOOKING_SUBMISSION_UNAVAILABLE");
                    JSONObject attempt =
                            binding(target)
                                    .put("attemptedAt", System.currentTimeMillis())
                                    .put("outcome", "unknown");
                    latest.put("openTableSubmission", attempt);
                    DiningPollBooking.save(context, latest);
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
                        attempt.put("outcome", "dispatched");
                        DiningPollBooking.save(context, latest);
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
        return value;
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
        watch.put("restaurantAddress", read.address()).put("existingCardId", read.existingCardId());
        DiningPollBooking.apply(context, watch, confirmation);
        return new JSONObject().put("watch", watch);
    }

    private static boolean recentProof(JSONObject proof, JSONObject target, State state, long now) {
        if (proof == null) return false;
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
        if (state.booking() == null
                || !state.booking().key().equals(target.optString("bookingKey"))
                || !java.util.Objects.equals(
                        state.booking().taskId(), target.optString("bookingTaskId"))
                || !state.meal().sourceThreadId().equals(target.optString("executionThreadId"))
                || target.optInt("displayId", -1) <= 0
                || !"com.opentable".equals(target.optString("packageName"))
                || !Set.of(Phase.WORKING, Phase.UNKNOWN).contains(state.phase())) {
            throw new IOException("BOOKING_RECEIPT_TARGET_CHANGED");
        }
        for (ActivityManager.RunningTaskInfo task :
                context.getSystemService(ActivityManager.class).getRunningTasks(100)) {
            if (DiningPollUi.display(task) != target.getInt("displayId")
                    || task.topActivity == null) continue;
            if ("com.opentable".equals(task.topActivity.getPackageName())
                    && task.taskId == target.getInt("taskId")
                    && task.getClass().getField("userId").getInt(task) == target.getInt("userId"))
                return;
            throw new IOException("BOOKING_RECEIPT_TARGET_CHANGED");
        }
        throw new IOException("BOOKING_RECEIPT_NOT_VISIBLE");
    }
}
