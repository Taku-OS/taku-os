package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Data for the public SystemUI v2 basic card; owns only booking copy and actions. */
final class DiningPollIslandModel {
    static JSONObject presentation(JSONObject watch, boolean failed) throws Exception {
        JSONObject state = watch.getJSONObject("state"), meal = state.getJSONObject("meal");
        String key = DiningPollConfirmationState.offer(watch);
        if (key.isEmpty() || !watch.optBoolean("active", true))
            throw new IOException("REQUEST_EXPIRED");
        boolean scheduled = meal.has("when") && meal.has("people");
        JSONObject actions = new JSONObject();
        String title, revision = "";
        if (failed) {
            JSONObject record = watch.getJSONObject("confirmation");
            if (!key.equals(record.optString("key"))
                    || !"failed".equals(record.optString("status"))
                    || record.optBoolean("dismissed")) throw new IOException("REQUEST_EXPIRED");
            revision = DiningPollConfirmationState.uiRevision(record);
            title = record.optString("message", "The reservation has not started.");
            if (record.optBoolean("retryable") && !DiningPollConfirmationState.submitted(watch))
                actions.put(
                        scheduled ? "retry" : "details",
                        scheduled ? "Retry" : "Enter booking details");
            actions.put("dismiss", "Dismiss");
        } else {
            if (!DiningPollConfirmationState.mayOffer(watch, key))
                throw new IOException("REQUEST_EXPIRED");
            String restaurant = state.getJSONObject("offer").getString("restaurant"), name = null;
            JSONArray options = meal.getJSONArray("restaurants");
            for (int i = 0; i < options.length(); i++)
                if (restaurant.equals(options.getJSONObject(i).getString("id")))
                    name = options.getJSONObject(i).getString("name");
            if (name == null) throw new IOException("INVALID_STORED_TARGET");
            title = "Book " + name + "?";
            actions.put(
                            scheduled ? "book" : "details",
                            scheduled ? "Book on OpenTable" : "Enter booking details")
                    .put("decline", "Not now");
        }
        String schedule;
        if (scheduled) {
            ZonedDateTime when = ZonedDateTime.parse(meal.getString("when"));
            String option = state.getJSONObject("offer").getString("restaurant");
            JSONObject addresses = watch.optJSONObject("bookingAddresses");
            String address = addresses == null ? "" : addresses.optString(option);
            schedule =
                    (address.isBlank() ? "" : address + "\n")
                            + when.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm VV"))
                            + " · "
                            + meal.getInt("people")
                            + " guests";
        } else
            schedule =
                    missingDetails(DiningPollSchedule.read(watch.optJSONObject("bookingSchedule")));
        String token =
                "dining:"
                        + watch.getString("id")
                        + ":"
                        + key
                        + ":"
                        + (failed ? "failed:" + revision : "offer");
        JSONArray ids = new JSONArray();
        for (String id : new String[] {"book", "details", "decline", "retry", "dismiss"})
            if (actions.has(id)) ids.put(id);
        int titleEnd = Math.min(80, title.length());
        if (titleEnd > 0 && Character.isHighSurrogate(title.charAt(titleEnd - 1))) titleEnd--;
        JSONObject expanded =
                new JSONObject()
                        .put("template", "basic")
                        .put("title", title.substring(0, titleEnd))
                        .put("body", title + "\n" + schedule)
                        .put("actions", ids.put("open"))
                        .put("primaryAction", ids.getString(0))
                        .put("tapAction", "open");
        JSONObject payload =
                new JSONObject()
                        .put("version", 2)
                        .put("expanded", expanded)
                        .put("timeoutS", 28_800);
        return new JSONObject()
                .put("watchId", watch.getString("id"))
                .put("key", key)
                .put("failed", failed)
                .put("revision", revision)
                .put("threadId", meal.getString("sourceThreadId"))
                .put("token", token)
                .put("actions", actions)
                .put("payload", payload);
    }

    private static String missingDetails(DiningPollSchedule known) {
        var missing =
                DiningPollBookingForm.missing(known).stream()
                        .map(field -> field.label.toLowerCase(java.util.Locale.ROOT))
                        .toList();
        return missing.isEmpty()
                ? "Review booking details."
                : "To book, add " + String.join(", ", missing) + ".";
    }

    static void validateAction(JSONObject watch, String key, String action, String revision)
            throws Exception {
        if (key == null
                || action == null
                || !key.equals(DiningPollConfirmationState.offer(watch))
                || !watch.optBoolean("active", true)) throw new IOException("REQUEST_EXPIRED");
        if (action.equals("details")) {
            if (watch.getJSONObject("state").getJSONObject("meal").has("when") || revision == null)
                throw new IOException("REQUEST_EXPIRED");
            if (!revision.isEmpty()) {
                JSONObject record = watch.optJSONObject("confirmation");
                if (record == null
                        || !DiningPollConfirmationState.uiRevision(record).equals(revision))
                    throw new IOException("REQUEST_EXPIRED");
            }
            DiningPollConfirmationState.validate(watch, key, !revision.isEmpty());
            return;
        }
        if (action.equals("book") || action.equals("decline")) {
            DiningPollConfirmationState.validate(watch, key, false);
            return;
        }
        if (!action.equals("retry") && !action.equals("dismiss"))
            throw new IOException("INVALID_DINING_ACTION");
        JSONObject record = watch.getJSONObject("confirmation");
        if (!key.equals(record.optString("key"))
                || !"failed".equals(record.optString("status"))
                || record.optBoolean("dismissed")
                || !DiningPollConfirmationState.uiRevision(record).equals(revision))
            throw new IOException("REQUEST_EXPIRED");
        if (action.equals("retry")) {
            if (!record.optBoolean("retryable"))
                throw new IOException("CONFIRMATION_RETRY_UNAVAILABLE");
            DiningPollConfirmationState.validate(watch, key, true);
        }
    }
}
