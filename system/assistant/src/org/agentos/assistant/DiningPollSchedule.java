package org.agentos.assistant;

import org.json.JSONObject;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;

/** Independently known reservation details. An omitted field stays available for user input. */
record DiningPollSchedule(LocalDate date, LocalTime time, ZoneId zone, Integer people) {
    static final DiningPollSchedule EMPTY = new DiningPollSchedule(null, null, null, null);

    private static final Pattern SUMMARY =
            Pattern.compile(
                    "^Booking details: ([0-9]{4}-[0-9]{2}-[0-9]{2}) · ([0-9]{2}:[0-9]{2}) ·"
                        + " ([A-Za-z0-9_+/-]+) · ([1-9][0-9]?) guests$",
                    Pattern.MULTILINE);

    static DiningPollSchedule prefill(String summary, Instant now) {
        if (summary.length() > 4_000) return EMPTY;
        var match = SUMMARY.matcher(summary);
        if (!match.find()) return EMPTY;
        try {
            String zone = match.group(3);
            if (!ZoneId.getAvailableZoneIds().contains(zone)) return EMPTY;
            DiningPollSchedule schedule =
                    new DiningPollSchedule(
                            LocalDate.parse(match.group(1)),
                            LocalTime.parse(match.group(2)),
                            ZoneId.of(zone),
                            Integer.parseInt(match.group(4)));
            if (match.find() || !schedule.when().toInstant().isAfter(now)) return EMPTY;
            return schedule;
        } catch (DateTimeException invalid) {
            return EMPTY;
        }
    }

    static DiningPollSchedule from(ZonedDateTime when, int people) {
        return new DiningPollSchedule(
                when.toLocalDate(), when.toLocalTime(), when.getZone(), people);
    }

    static DiningPollSchedule read(JSONObject fields) {
        if (fields == null) return EMPTY;
        LocalDate date = null;
        LocalTime time = null;
        ZoneId zone = null;
        Integer people = null;
        try {
            date = LocalDate.parse(fields.getString("date"));
        } catch (Exception missing) {
        }
        try {
            time = LocalTime.parse(fields.getString("time"));
        } catch (Exception missing) {
        }
        try {
            String id = fields.getString("zone");
            if (ZoneId.getAvailableZoneIds().contains(id)) zone = ZoneId.of(id);
        } catch (Exception missing) {
        }
        if (fields.opt("people") instanceof Integer count && count >= 1 && count <= 99)
            people = count;
        return new DiningPollSchedule(date, time, zone, people);
    }

    JSONObject fields() throws Exception {
        return new JSONObject()
                .put("date", date == null ? null : date.toString())
                .put("time", time == null ? null : time.toString())
                .put("zone", zone == null ? null : zone.getId())
                .put("people", people);
    }

    boolean complete() {
        return date != null && time != null && zone != null && people != null;
    }

    ZonedDateTime when() {
        if (date == null || time == null || zone == null)
            throw new DateTimeException("Booking time is incomplete");
        LocalDateTime local = LocalDateTime.of(date, time);
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw new DateTimeException("Booking time is ambiguous");
        return ZonedDateTime.ofStrict(local, offsets.get(0), zone);
    }

    DiningPollSchedule availableAt(Instant now) {
        if (date == null) return this;
        ZoneId todayZone = zone == null ? ZoneId.of("UTC") : zone;
        LocalDate today = LocalDate.ofInstant(now, todayZone);
        if (date.isBefore(zone == null ? today.minusDays(1) : today))
            return new DiningPollSchedule(null, time, zone, people);
        if (time != null && zone != null) {
            try {
                if (!when().toInstant().isAfter(now))
                    return new DiningPollSchedule(null, time, zone, people);
            } catch (DateTimeException invalid) {
                return new DiningPollSchedule(date, null, zone, people);
            }
        }
        return this;
    }

    DiningPollSchedule merge(DiningPollSchedule source) {
        return new DiningPollSchedule(
                date == null ? source.date : date,
                time == null ? source.time : time,
                zone == null ? source.zone : zone,
                people == null ? source.people : people);
    }

    boolean agreesWith(ZonedDateTime when, int party) {
        return (date == null || date.equals(when.toLocalDate()))
                && (time == null || time.equals(when.toLocalTime()))
                && (zone == null || zone.equals(when.getZone()))
                && (people == null || people == party);
    }
}
