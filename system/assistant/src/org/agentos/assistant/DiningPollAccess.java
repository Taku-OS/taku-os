package org.agentos.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Shared implementation behind the authorized open-access and broker routes. */
final class DiningPollAccess {
    private DiningPollAccess() {}

    static JSONObject execute(Context context, String method, JSONObject args) throws Exception {
        if (method.startsWith("dining/poll/calendar-")) return DiningBookingCalendar.call(context, method, args);
        if (method.startsWith("dining/poll/ride-")) return DiningBookingRide.call(context, method, args);
        return switch (method) {
            case "dining/poll/send-register" -> {
                PanelProtocol.exact(
                        args,
                        Set.of(
                                "intentId",
                                "sourceThreadId",
                                "sourceReply",
                                "groupName",
                                "question",
                                "options"));
                yield DiningPollSend.register(context, args);
            }
            case "dining/poll/send-status" -> {
                PanelProtocol.exact(args, Set.of("intentId"));
                yield DiningPollSend.status(context, args);
            }
            case "dining/poll/send-bind" -> forward(context, "bind", args);
            case "dining/poll/send" -> forward(context, "send", args);
            case "dining/poll/send-inspect" -> forward(context, "inspect", args);
            default -> DiningPollBooking.call(context, method, args);
        };
    }

    private static JSONObject forward(Context context, String action, JSONObject args)
            throws Exception {
        PanelProtocol.exact(args, Set.of("intentId", "executionThreadId"));
        return DiningPollSend.forward(context, action, args);
    }

    /** Keep the operation's cleanup receipt outside the result on both success and failure. */
    static JSONObject brokerCallback(Context context, JSONObject args) throws Exception {
        Path directory = DiningPollSendState.cleanupTarget(args).getParentFile().toPath();
        var lease = DiningPollOperationLock.acquire(directory.resolve("input.lock"));
        DiningPollSendAutomation.beginOperation();
        try {
            JSONObject envelope =
                    new JSONObject().put("operationToken", args.optString("operationToken"));
            try {
                if (Files.exists(directory.resolve("cancel")))
                    throw new IOException("POLL_INPUT_CANCELLED");
                JSONObject result =
                        DiningBookingRead.action(args)
                                ? DiningBookingRead.nativeCall(context, args)
                                : DiningPollSend.nativeCall(context, args);
                envelope.put("ok", true).put("result", result);
            } catch (Exception failure) {
                String code = failureCode(failure);
                if (!DiningBookingRead.action(args) && !"observe".equals(args.opt("action")))
                    DiningPollSend.recordFailure(context, args, code);
                envelope.put("ok", false).put("code", code);
            }
            try {
                DiningPollSend.acknowledgeCleanup(args);
                envelope.put("cleanupConfirmed", true);
            } catch (Exception failure) {
                envelope.put("cleanupConfirmed", false).put("cleanupCode", failureCode(failure));
            }
            return envelope;
        } finally {
            if (DiningPollSendAutomation.cleanupConfirmed()) lease.close();
        }
    }

    private static String failureCode(Exception failure) {
        String code = failure.getMessage();
        return code != null && code.matches("[A-Z][A-Z0-9_]{1,179}") ? code : "POLL_UNAVAILABLE";
    }
}
