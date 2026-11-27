package org.agentos.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;

/** A native approval and the matching open-access request use one journal and gate. */
final class DiningPollBookingConfirmation {
    private static final DiningPollConfirmationGate GATE = new DiningPollConfirmationGate();

    private DiningPollBookingConfirmation() {}

    static boolean busy() {
        return GATE.busy();
    }

    static long revision() {
        return GATE.revision();
    }

    static boolean sameRevision(long revision) {
        return GATE.sameRevision(revision);
    }

    static boolean beginRead() {
        return GATE.beginRead();
    }

    static void endRead() {
        GATE.endRead();
    }

    static DiningPollConfirmationGate.Ticket begin(
            Context context, JSONObject watch, String key, boolean retry) throws Exception {
        recover(context, watch);
        DiningPollConfirmationState.validate(watch, key, retry);
        DiningPollConfirmationGate.Ticket ticket = GATE.claim(watch.getString("id"), key);
        if (ticket == null) throw new IOException("CONFIRMATION_IN_PROGRESS");
        try {
            watch.put(
                    "confirmation",
                    DiningPollConfirmationState.checking(
                            key, ticket.operation, System.currentTimeMillis()));
            DiningPollBooking.save(context, watch);
            DiningPollIsland.hideAndWait(context, key);
            GATE.awaitReaders(8_000);
            return ticket;
        } catch (Exception failure) {
            failed(context, watch, ticket, failure);
            throw failure;
        }
    }

    static void recover(Context context, JSONObject watch) throws Exception {
        if (DiningPollConfirmationState.recover(
                watch, GATE.active(watch.getString("id")), System.currentTimeMillis())) {
            DiningPollBooking.save(context, watch);
        }
    }

    static void finish(Context context, JSONObject watch, DiningPollConfirmationGate.Ticket ticket)
            throws Exception {
        try {
            watch.put(
                    "confirmation",
                    DiningPollConfirmationState.finished(
                            watch,
                            watch.getJSONObject("confirmation"),
                            System.currentTimeMillis()));
            DiningPollBooking.save(context, watch);
        } finally {
            GATE.release(ticket);
        }
    }

    static void failed(
            Context context,
            JSONObject watch,
            DiningPollConfirmationGate.Ticket ticket,
            Exception failure)
            throws Exception {
        try {
            JSONObject record = watch.getJSONObject("confirmation");
            String code = failure.getMessage();
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,179}")) code = "POLL_UNAVAILABLE";
            watch.put(
                    "confirmation",
                    DiningPollConfirmationState.failed(
                            watch, record, code, System.currentTimeMillis()));
            DiningPollBooking.save(context, watch);
        } finally {
            GATE.release(ticket);
        }
        DiningPollIsland.postFailure(context, watch.getString("id"));
    }

    static JSONObject dismiss(Context context, JSONObject watch, String key) throws Exception {
        JSONObject record = watch.optJSONObject("confirmation");
        if (record == null
                || !key.equals(DiningPollConfirmationState.offer(watch))
                || !key.equals(record.optString("key"))
                || !"failed".equals(record.optString("status"))) {
            throw new IOException("REQUEST_EXPIRED");
        }
        record.put("dismissed", true);
        DiningPollBooking.save(context, watch);
        DiningPollIsland.hide(context, key);
        return watch;
    }
}
