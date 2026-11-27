package org.agentos.assistant;

import static org.agentos.assistant.DiningPollBookingFlow.*;

import org.json.*;

import java.time.*;
import java.util.*;

/**
 * Private durable representation. Agent callers use the controller, never restore arbitrary State.
 */
final class DiningPollBookingCodec {
    static JSONObject meal(Meal m) throws Exception {
        JSONArray options = new JSONArray();
        for (Restaurant r : m.restaurants())
            options.put(new JSONObject().put("id", r.id()).put("name", r.name()));
        JSONObject value =
                new JSONObject()
                        .put("id", m.id())
                        .put("sourceThreadId", m.sourceThreadId())
                        .put("groupId", m.groupId())
                        .put("groupName", m.groupName())
                        .put("pollId", m.pollId())
                        .put("pollQuestion", m.pollQuestion())
                        .put("restaurants", options);
        if (m.hasSchedule()) value.put("when", m.when().toString()).put("people", m.people());
        return value;
    }

    static Meal meal(JSONObject o) throws Exception {
        List<Restaurant> options = new ArrayList<>();
        JSONArray a = o.getJSONArray("restaurants");
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            options.add(new Restaurant(r.getString("id"), r.getString("name")));
        }
        boolean scheduled = o.has("when");
        if (scheduled != o.has("people")) throw new Invalid("INVALID_STORED_SCHEDULE");
        return new Meal(
                o.getString("id"),
                o.getString("sourceThreadId"),
                o.getString("groupId"),
                o.getString("groupName"),
                o.getString("pollId"),
                o.getString("pollQuestion"),
                scheduled ? ZonedDateTime.parse(o.getString("when")) : null,
                scheduled ? o.getInt("people") : 0,
                options);
    }

    private static JSONObject evidence(Evidence e) throws Exception {
        return new JSONObject()
                .put("source", e.source().name())
                .put("id", e.observationId())
                .put("at", e.capturedAt().toString());
    }

    private static Evidence evidence(JSONObject o) throws Exception {
        return new Evidence(
                Source.valueOf(o.getString("source")),
                o.getString("id"),
                Instant.parse(o.getString("at")));
    }

    static JSONObject state(State s) throws Exception {
        JSONObject o =
                new JSONObject()
                        .put("meal", meal(s.meal()))
                        .put("phase", s.phase().name())
                        .put("offerSequence", s.offerSequence())
                        .put("declined", new JSONArray(s.declined()))
                        .put("sharePrepared", s.sharePrepared())
                        .put("calendarAccepted", s.calendarAccepted())
                        .put("calendarAsked", s.calendarAsked());
        if (s.shareKey() != null) o.put("shareKey", s.shareKey());
        if (s.calendarKey() != null) o.put("calendarKey", s.calendarKey());
        if (s.votes() != null) {
            Votes v = s.votes();
            o.put(
                    "votes",
                    new JSONObject()
                            .put("groupId", v.groupId())
                            .put("pollId", v.pollId())
                            .put("question", v.question())
                            .put("optionIds", new JSONArray(v.optionIds()))
                            .put("counts", new JSONArray(v.counts()))
                            .put("eligibleVoters", v.eligibleVoters())
                            .put("singleChoice", v.singleChoice())
                            .put("sequence", v.sequence())
                            .put("evidence", evidence(v.evidence())));
        }
        if (s.offer() != null)
            o.put(
                    "offer",
                    new JSONObject()
                            .put("key", s.offer().key())
                            .put("restaurant", s.offer().restaurant().id()));
        if (s.booking() != null) {
            Booking b = s.booking();
            o.put(
                    "booking",
                    new JSONObject()
                            .put("key", b.key())
                            .put("restaurant", b.restaurant().id())
                            .put("consentAt", b.consentAt().toString())
                            .put("taskId", b.taskId()));
        }
        if (s.receipt() != null) {
            Receipt r = s.receipt();
            o.put(
                    "receipt",
                    new JSONObject()
                            .put("status", r.status().name())
                            .put("bookingKey", r.bookingKey())
                            .put("restaurantId", r.restaurantId())
                            .put("restaurantName", r.restaurantName())
                            .put("when", r.when().toString())
                            .put("people", r.people())
                            .put("confirmationCode", r.confirmationCode())
                            .put("seating", r.seating())
                            .put("restaurantUrl", r.restaurantUrl())
                            .put("photoUrl", r.photoUrl())
                            .put("evidence", evidence(r.evidence())));
        }
        return o;
    }

    static State state(JSONObject o) throws Exception {
        Meal m = meal(o.getJSONObject("meal"));
        Votes v = null;
        Offer offer = null;
        Booking booking = null;
        Receipt receipt = null;
        if (o.has("votes")) {
            JSONObject a = o.getJSONObject("votes");
            List<Integer> counts = new ArrayList<>();
            JSONArray c = a.getJSONArray("counts");
            for (int i = 0; i < c.length(); i++) counts.add(c.getInt(i));
            v =
                    new Votes(
                            a.getString("groupId"),
                            a.getString("pollId"),
                            a.getString("question"),
                            strings(a.getJSONArray("optionIds")),
                            counts,
                            a.isNull("eligibleVoters") ? null : a.getInt("eligibleVoters"),
                            a.getBoolean("singleChoice"),
                            a.getLong("sequence"),
                            evidence(a.getJSONObject("evidence")));
        }
        if (o.has("offer")) {
            JSONObject a = o.getJSONObject("offer");
            offer = new Offer(a.getString("key"), restaurant(m, a.getString("restaurant")));
        }
        if (o.has("booking") && !m.hasSchedule()) throw new Invalid("INVALID_STORED_SCHEDULE");
        if (o.has("booking")) {
            JSONObject a = o.getJSONObject("booking");
            booking =
                    new Booking(
                            a.getString("key"),
                            restaurant(m, a.getString("restaurant")),
                            m,
                            Instant.parse(a.getString("consentAt")),
                            a.optString("taskId", null));
        }
        if (o.has("receipt")) {
            JSONObject a = o.getJSONObject("receipt");
            receipt =
                    new Receipt(
                            ReceiptStatus.valueOf(a.getString("status")),
                            a.getString("bookingKey"),
                            a.getString("restaurantId"),
                            a.getString("restaurantName"),
                            ZonedDateTime.parse(a.getString("when")),
                            a.getInt("people"),
                            a.getString("confirmationCode"),
                            a.getString("seating"),
                            a.getString("restaurantUrl"),
                            a.getString("photoUrl"),
                            evidence(a.getJSONObject("evidence")));
        }
        return new State(
                m,
                Phase.valueOf(o.getString("phase")),
                v,
                offer,
                booking,
                receipt,
                o.getLong("offerSequence"),
                new HashSet<>(strings(o.getJSONArray("declined"))),
                o.optString("shareKey", null),
                o.getBoolean("sharePrepared"),
                o.optString("calendarKey", null),
                o.getBoolean("calendarAccepted"),
                o.getBoolean("calendarAsked"));
    }

    private static Restaurant restaurant(Meal m, String id) {
        return m.restaurants().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new Invalid("INVALID_STORED_TARGET"));
    }

    static List<String> strings(JSONArray a) throws Exception {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) values.add(a.getString(i));
        return values;
    }
}
