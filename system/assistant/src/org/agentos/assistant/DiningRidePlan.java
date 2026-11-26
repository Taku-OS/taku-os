package org.agentos.assistant;

import org.json.JSONObject;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** Explicit, editable pickup and restaurant-local departure time; no current-location fallback. */
final class DiningRidePlan {
    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
                    .withResolverStyle(ResolverStyle.STRICT);

    static JSONObject draft(JSONObject meal) throws Exception {
        JSONObject previous = meal.optJSONObject("ridePlan");
        if (previous != null) return new JSONObject(previous.toString());
        LocalDateTime suggested =
                Instant.ofEpochMilli(meal.getLong("start") - 30 * 60_000L)
                        .atZone(ZoneId.of(meal.getString("zone")))
                        .toLocalDateTime();
        return new JSONObject()
                .put("pickup", "")
                .put(
                        "departureLocal",
                        suggested.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
                .put("zone", meal.getString("zone"))
                .put("suggestedTime", true);
    }

    static JSONObject validate(JSONObject meal, String pickup, String departure, long now)
            throws Exception {
        if (pickup == null
                || pickup.codePoints()
                        .anyMatch(
                                code ->
                                        Character.isISOControl(code)
                                                || Character.getType(code) == Character.FORMAT))
            throw new IOException("DINING_RIDE_PICKUP_REQUIRED");
        pickup = pickup == null ? "" : pickup.strip().replaceAll("[\\p{Z}\\s]+", " ");
        if (pickup.length() < 3
                || pickup.length() > 240
                || pickup.codePoints().anyMatch(Character::isISOControl))
            throw new IOException("DINING_RIDE_PICKUP_REQUIRED");
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(departure, LOCAL);
        } catch (Exception bad) {
            throw new IOException("DINING_RIDE_TIME_INVALID");
        }
        ZoneId zone = ZoneId.of(meal.getString("zone"));
        if (zone.getRules().getValidOffsets(local).size() != 1)
            throw new IOException("DINING_RIDE_TIME_AMBIGUOUS");
        long at = local.atZone(zone).toInstant().toEpochMilli();
        if (at < now + 30 * 60_000L
                || at > now + 90L * 24 * 60 * 60_000L
                || at >= meal.getLong("start")) throw new IOException("DINING_RIDE_TIME_INVALID");
        return new JSONObject()
                .put("pickup", pickup)
                .put(
                        "departureLocal",
                        local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
                .put("pickupAt", at)
                .put("zone", zone.getId())
                .put("suggestedTime", false);
    }

    static boolean sameInput(JSONObject plan, String pickup, String departure) {
        if (pickup == null
                || departure == null
                || pickup.codePoints()
                        .anyMatch(
                                code ->
                                        Character.isISOControl(code)
                                                || Character.getType(code) == Character.FORMAT))
            return false;
        return plan.optString("pickup").equals(pickup.strip().replaceAll("[\\p{Z}\\s]+", " "))
                && plan.optString("departureLocal").equals(departure);
    }

    static String zoneLabel(JSONObject meal) {
        return switch (meal.optString("zone")) {
            case "America/New_York" -> "New York time";
            case "America/Los_Angeles" -> "Pacific time";
            default -> meal.optString("zone");
        };
    }

    private DiningRidePlan() {}
}
