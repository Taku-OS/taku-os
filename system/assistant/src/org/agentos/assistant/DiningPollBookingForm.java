package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Authoritative generated form for the current poll offer. */
final class DiningPollBookingForm {
    enum Field {
        DATE("date", "date", "Date"),
        TIME("time", "time", "Restaurant local time"),
        ZONE("zone", "time-zone", "Restaurant time zone"),
        PEOPLE("people", "integer", "Party size");
        final String key, type, label;

        Field(String key, String type, String label) {
            this.key = key;
            this.type = type;
            this.label = label;
        }
    }

    static Set<Field> missing(DiningPollSchedule known) {
        Set<Field> fields = EnumSet.noneOf(Field.class);
        if (known.date() == null) fields.add(Field.DATE);
        if (known.time() == null) fields.add(Field.TIME);
        if (known.zone() == null) fields.add(Field.ZONE);
        if (known.people() == null) fields.add(Field.PEOPLE);
        return fields;
    }

    static JSONObject schema(String restaurant, DiningPollSchedule known) throws Exception {
        JSONArray fields = new JSONArray();
        for (Field field : missing(known)) {
            JSONObject input =
                    new JSONObject()
                            .put("id", field.key)
                            .put("type", field.type)
                            .put("label", field.label)
                            .put("required", true);
            if (field == Field.PEOPLE) input.put("min", 1).put("max", 99);
            fields.put(input);
        }
        return new JSONObject()
                .put("restaurant", restaurant)
                .put("known", known.fields())
                .put("fields", fields);
    }

    static DiningPollSchedule complete(DiningPollSchedule known, JSONObject values, Instant now)
            throws Exception {
        Set<Field> missing = missing(known);
        if (missing.isEmpty()) throw new IOException("REQUEST_EXPIRED");
        if (values.length() != missing.size()) throw new IOException("INVALID_BOOKING_FIELDS");
        for (Field field : missing) {
            if (!values.has(field.key)) throw new IOException("INVALID_BOOKING_FIELDS");
        }
        DiningPollSchedule schedule = known.merge(DiningPollSchedule.read(values));
        if (!schedule.complete()) throw new IOException("INVALID_BOOKING_FIELDS");
        try {
            if (!schedule.when().toInstant().isAfter(now)) throw new IOException("MEAL_IN_PAST");
        } catch (DateTimeException invalid) {
            throw new IOException("MEAL_TIME_AMBIGUOUS", invalid);
        }
        return schedule;
    }

    private DiningPollBookingForm() {}
}
