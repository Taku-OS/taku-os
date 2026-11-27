package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.UUID;

/** Receipt identity and successful Feed share dispatch; neither attests WhatsApp delivery. */
final class DiningBookingCalendarPolicy {
    static String latestWatch(JSONObject watches, String card) throws Exception {
        String winner = "";
        long latest = -1, published = -1;
        boolean tied = false;
        for (var ids = watches.keys(); ids.hasNext(); ) {
            JSONObject w = watches.optJSONObject(ids.next());
            if (w == null || !card.equals(w.optString("feedCardId"))) continue;
            String id = w.optString("id");
            if (id.isBlank()
                    || w.has("feedReceiptOwnerWatchId")
                            && !id.equals(w.opt("feedReceiptOwnerWatchId"))) continue;
            long at;
            try {
                identity(w);
                at = receiptAt(w);
            } catch (Exception unconfirmed) {
                continue;
            }
            long publication = w.optLong("feedPublishedAt");
            if (at > latest || at == latest && publication > published) {
                winner = w.getString("id");
                latest = at;
                published = publication;
                tied = false;
            } else if (at == latest
                    && publication == published
                    && !winner.equals(w.optString("id"))) tied = true;
        }
        if (tied || winner.isEmpty()) throw new IOException("DINING_CALENDAR_SHARE_CARD_AMBIGUOUS");
        return winner;
    }

    static String identity(JSONObject watch) throws Exception {
        JSONObject state = watch.getJSONObject("state"),
                receipt = state.getJSONObject("receipt"),
                booking = state.getJSONObject("booking"),
                meal = state.getJSONObject("meal");
        if (!watch.optBoolean("active", true)
                || !"CONFIRMED".equals(state.optString("phase"))
                || !"CONFIRMED".equals(receipt.optString("status"))
                || !receipt.getString("bookingKey").equals(booking.getString("key"))
                || !receipt.getString("when").equals(meal.getString("when"))
                || receipt.getInt("people") != meal.getInt("people")
                || !receipt.getString("restaurantId").equals(booking.getString("restaurant"))
                || !meal.optString("sourceThreadId").matches("[0-9a-fA-F-]{36}")
                || receipt.getJSONObject("evidence").optString("id").isBlank()
                || address(watch).isBlank()
                || !"OPENTABLE_RECEIPT_UI"
                        .equals(receipt.getJSONObject("evidence").optString("source")))
            throw new IOException("DINING_CALENDAR_RECEIPT_UNVERIFIED");
        DiningPollBookingFlow.State decoded = DiningPollBookingCodec.state(state);
        if (!DiningRestaurantIdentity.sameName(
                        decoded.booking().restaurant().name(), decoded.receipt().restaurantName())
                || decoded.receipt()
                        .evidence()
                        .capturedAt()
                        .isBefore(decoded.booking().consentAt()))
            throw new IOException("DINING_CALENDAR_RECEIPT_UNVERIFIED");
        UUID thread = UUID.fromString(meal.getString("sourceThreadId"));
        if (!thread.toString().equalsIgnoreCase(meal.getString("sourceThreadId")))
            throw new IOException("DINING_CALENDAR_RECEIPT_UNVERIFIED");
        String value =
                watch.getString("feedCardId")
                        + '\n'
                        + meal.getString("sourceThreadId")
                        + '\n'
                        + watch.getString("id")
                        + '\n'
                        + booking.getString("key")
                        + '\n'
                        + receipt.getString("restaurantId")
                        + '\n'
                        + receipt.getString("restaurantName")
                        + '\n'
                        + address(watch)
                        + '\n'
                        + receipt.getString("when")
                        + '\n'
                        + receipt.getInt("people")
                        + '\n'
                        + receipt.optString("confirmationCode")
                        + '\n'
                        + receipt.getJSONObject("evidence").getString("id")
                        + '\n'
                        + receipt.getJSONObject("evidence").getString("at");
        byte[] hash =
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    static boolean dispatched(JSONObject event, JSONObject entry, String expectedText, long now)
            throws Exception {
        JSONObject intent =
                DiningFeedDispatch.intent(
                        event,
                        entry.getString("cardId"),
                        "share",
                        "share-text",
                        entry.getLong("receiptAt"),
                        now);
        String hash =
                java.util.HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(expectedText.getBytes(StandardCharsets.UTF_8)));
        return intent != null
                && "com.whatsapp".equals(intent.optString("package"))
                && hash.equals(intent.optString("textSha256"));
    }

    // Preserve successful saves. An older offer was based on the picker alone.
    static boolean requireShareConfirmation(JSONObject entry) throws Exception {
        if (entry.has("eventId") || entry.optInt("shareProofVersion") == 1) return false;
        entry.put("shareProofVersion", 1)
                .put("phase", "waiting_share")
                .put("key", UUID.randomUUID().toString())
                .put("messageSent", false);
        for (String field :
                java.util.List.of(
                        "shareAcceptedAt",
                        "shareEventCursor",
                        "shareDispatchedAt",
                        "shareConfirmedCursor",
                        "shareConfirmedAt",
                        "feedCursor",
                        "error")) {
            entry.remove(field);
        }
        return true;
    }

    static boolean recordShareEvent(
            JSONObject entry, JSONObject event, String expectedText, long now) throws Exception {
        if (!"waiting_share".equals(entry.optString("phase"))) return false;
        if (dispatched(event, entry, expectedText, now)) {
            entry.put("shareEventCursor", event.getString("cursor"))
                    .put("shareDispatchedAt", event.getLong("at"));
            return true;
        }
        long dispatchedAt = entry.optLong("shareDispatchedAt");
        String confirmation = "confirm-dining-share";
        if (dispatchedAt < entry.getLong("receiptAt")
                || !"gui".equals(event.optString("origin"))
                || !DiningFeedDispatch.action(
                        event,
                        entry.getString("cardId"),
                        confirmation,
                        confirmation,
                        "accepted",
                        dispatchedAt,
                        now)
                || !DiningFeedDispatch.ordered(
                        entry.optString("shareEventCursor"), event.optString("cursor"))) {
            return false;
        }
        entry.put("phase", "offer")
                .put("shareConfirmedCursor", event.getString("cursor"))
                .put("shareConfirmedAt", event.getLong("at"))
                .put("shareConfirmedByUser", true);
        return true;
    }

    static JSONObject meal(JSONObject watch) throws Exception {
        return meal(watch, System.currentTimeMillis());
    }

    static JSONObject meal(JSONObject watch, long now) throws Exception {
        identity(watch);
        JSONObject state = watch.getJSONObject("state"), r = state.getJSONObject("receipt");
        ZonedDateTime when = ZonedDateTime.parse(r.getString("when"));
        String zone = when.getZone().getId();
        if (!ZoneId.getAvailableZoneIds().contains(zone))
            throw new IOException("DINING_CALENDAR_TIME_ZONE_INVALID");
        if (when.getZone().getRules().getValidOffsets(when.toLocalDateTime()).size() != 1)
            throw new IOException("DINING_LOCAL_TIME_AMBIGUOUS");
        long start = when.toInstant().toEpochMilli();
        if (start < now - 2 * 60 * 60_000L || start > now + 180 * 24 * 60 * 60_000L)
            throw new IOException("DINING_RESERVATION_NOT_UPCOMING");
        String canonical =
                normalize(r.getString("restaurantName"))
                        + "\n"
                        + normalize(address(watch))
                        + "\n"
                        + start
                        + "\n"
                        + r.getInt("people");
        byte[] hash =
                MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder();
        for (byte b : hash) id.append(String.format(Locale.ROOT, "%02x", b & 255));
        return new JSONObject()
                .put("id", id.substring(0, 24))
                .put("restaurant", r.getString("restaurantName"))
                .put("address", address(watch))
                .put("partySize", r.getInt("people"))
                .put("year", when.getYear())
                .put("month", when.getMonthValue())
                .put("day", when.getDayOfMonth())
                .put("hour", when.getHour())
                .put("minute", when.getMinute())
                .put("zone", zone)
                .put("start", start)
                .put("end", start + 90 * 60_000L)
                .put("verifiedAt", receiptAt(watch))
                .put("source", "native-opentable-poll-confirmation")
                .put("sourceThreadId", state.getJSONObject("meal").getString("sourceThreadId"))
                .put("sourceWatchId", watch.getString("id"))
                .put("sourceGroupName", state.getJSONObject("meal").getString("groupName"))
                .put("phase", "calendar_offer")
                .put("key", UUID.randomUUID().toString());
    }

    static String when(JSONObject meal) {
        return String.format(
                Locale.ROOT,
                "%04d-%02d-%02d · %02d:%02d · %d guests",
                meal.optInt("year"),
                meal.optInt("month"),
                meal.optInt("day"),
                meal.optInt("hour"),
                meal.optInt("minute"),
                meal.optInt("partySize"));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
                .replaceAll("[\\p{Z}\\s]+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    static String address(JSONObject watch) throws IOException {
        String booking = watch.optString("bookingAddress"),
                legacy = watch.optString("restaurantAddress");
        if (!booking.isBlank() && !legacy.isBlank()) {
            try {
                DiningPollReceiptTarget.verifyAddress(booking, legacy);
            } catch (IOException changed) {
                throw new IOException("DINING_CALENDAR_ADDRESS_CHANGED", changed);
            }
        }
        // Keep the observed receipt spelling stable for existing Calendar event and Feed
        // identities.
        return legacy.isBlank() ? booking : legacy;
    }

    static long receiptAt(JSONObject watch) throws Exception {
        return Instant.parse(
                        watch.getJSONObject("state")
                                .getJSONObject("receipt")
                                .getJSONObject("evidence")
                                .getString("at"))
                .toEpochMilli();
    }

    static void current(JSONObject entry, JSONObject watch, String key) throws Exception {
        if (!entry.getString("fingerprint").equals(identity(watch))
                || !entry.getString("key").equals(key)) throw new IOException("REQUEST_EXPIRED");
    }

    static boolean needsPolling(JSONObject entry) {
        if (entry == null) return false;
        String phase = entry.optString("phase");
        if (entry.has("eventId") && !entry.optBoolean("feedPublished")) return true;
        return !java.util.Set.of("saved", "dismissed").contains(phase);
    }

    static boolean permissionRequired(JSONObject entry) {
        return entry != null
                && (entry.optBoolean("permissionRequired")
                        || "DINING_CALENDAR_PERMISSION_REQUIRED".equals(entry.optString("error")));
    }

    static void recordPermissionRequired(JSONObject entry, JSONObject meal) throws Exception {
        entry.put("meal", meal);
        if (!entry.has("calendars")) entry.put("calendars", new JSONArray());
        entry.remove("calendarId");
        entry.remove("existingEventId");
        entry.put("permissionRequired", true)
                .put("error", "DINING_CALENDAR_PERMISSION_REQUIRED")
                .put("phase", "error");
    }

    static void clearPermissionRequired(JSONObject entry) throws Exception {
        entry.remove("permissionRequired");
        if ("error".equals(entry.optString("phase"))
                && "DINING_CALENDAR_PERMISSION_REQUIRED".equals(entry.optString("error"))) {
            entry.put("phase", "offer");
            entry.remove("error");
        }
    }
}
