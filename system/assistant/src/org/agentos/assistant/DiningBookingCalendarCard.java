package org.agentos.assistant;

import org.json.*;

import java.time.*;
import java.util.*;

/** Uses the existing public Feed vocabulary; both native Home and Agents read this exact card. */
final class DiningBookingCalendarCard {
    private final JSONObject elements = new JSONObject();

    private JSONObject node(String id, String type, JSONObject props, String... children)
            throws Exception {
        JSONObject n =
                new JSONObject()
                        .put("type", type)
                        .put("props", props)
                        .put("children", new JSONArray(Arrays.asList(children)));
        elements.put(id, n);
        return n;
    }

    private JSONObject text(String id, String value, String variant, String tone) throws Exception {
        return node(
                id,
                "Text",
                new JSONObject().put("text", value).put("variant", variant).put("tone", tone));
    }

    static JSONObject calendar(JSONObject meal, long now) throws Exception {
        DiningBookingCalendarCard b = new DiningBookingCalendarCard();
        LocalDate date =
                LocalDate.of(meal.getInt("year"), meal.getInt("month"), meal.getInt("day"));
        b.node(
                "root",
                "Column",
                new JSONObject().put("gap", 12),
                "header",
                "week",
                "event",
                "confirm");
        b.node(
                "header",
                "AppHeader",
                new JSONObject().put("title", "Google Calendar").put("trailing", "Added"));
        b.node(
                "week",
                "Column",
                new JSONObject()
                        .put("padding", 12)
                        .put("gap", 10)
                        .put("radius", 16)
                        .put("fill", "content-100"),
                "month",
                "days");
        b.text(
                        "month",
                        date.getYear() + " / " + date.getMonthValue() + "",
                        "body-lg-medium",
                        "primary")
                .getJSONObject("props")
                .put("align", "center");
        String[] ids = new String[7];
        LocalDate sunday = date.minusDays(date.getDayOfWeek().getValue() % 7);
        String[] weekdays = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        for (int i = 0; i < 7; i++) {
            ids[i] = "day" + i;
            LocalDate d = sunday.plusDays(i);
            boolean selected = d.equals(date);
            b.node(
                    ids[i],
                    "Column",
                    new JSONObject().put("flex", 1).put("gap", 6).put("align", "center"),
                    "w" + i,
                    "d" + i);
            b.text("w" + i, weekdays[i], "body-sm", "tertiary");
            b.text(
                    "d" + i,
                    Integer.toString(d.getDayOfMonth()),
                    "body-lg-medium",
                    selected ? "brand" : "primary");
        }
        b.node("days", "Row", new JSONObject().put("gap", 4), ids);
        b.node(
                "event",
                "Column",
                new JSONObject()
                        .put("padding", 12)
                        .put("gap", 6)
                        .put("radius", 16)
                        .put("fill", "content-100"),
                "title",
                "time",
                "venue");
        b.text("title", meal.getString("restaurant"), "body-xl-medium", "primary");
        LocalTime end = LocalTime.of(meal.getInt("hour"), meal.getInt("minute")).plusMinutes(90);
        b.text(
                "time",
                String.format(
                        Locale.ROOT,
                        "%02d:%02d – %02d:%02d · %d guests",
                        meal.getInt("hour"),
                        meal.getInt("minute"),
                        end.getHour(),
                        end.getMinute(),
                        meal.getInt("partySize")),
                "body-sm",
                "tertiary");
        b.text("venue", meal.getString("address"), "body-sm", "tertiary");
        b.node(
                        "confirm",
                        "Button",
                        new JSONObject()
                                .put("label", "✓  View event")
                                .put("variant", "primary")
                                .put("size", "lg"))
                .put(
                        "on",
                        new JSONObject()
                                .put(
                                        "press",
                                        new JSONObject()
                                                .put("action", "dispatch")
                                                .put(
                                                        "params",
                                                        new JSONObject()
                                                                .put(
                                                                        "actionId",
                                                                        "open-calendar"))));
        JSONObject action =
                new JSONObject()
                        .put("label", "View event")
                        .put(
                                "intent",
                                new JSONObject()
                                        .put("type", "open-app")
                                        .put("package", "com.google.android.calendar")
                                        .put(
                                                "uri",
                                                "content://com.android.calendar/events/"
                                                        + meal.getLong("eventId")));
        return new JSONObject()
                .put("schema", "taku.feed-card/1")
                .put("id", "dining.calendar." + meal.getString("id"))
                .put(
                        "source",
                        new JSONObject()
                                .put("id", "agent")
                                .put("app", "Google Calendar")
                                .put("package", "com.google.android.calendar"))
                .put("layout", new JSONObject().put("span", "full").put("surface", "card"))
                .put("rank", 999999)
                .put("createdAt", now)
                .put("expiresAt", meal.getLong("end") + 24 * 60 * 60_000L)
                .put("pinned", false)
                .put(
                        "summary",
                        meal.getString("restaurant")
                                + " · "
                                + DiningBookingCalendarPolicy.when(meal)
                                + " · Added to Google Calendar")
                .put(
                        "pin",
                        new JSONObject()
                                .put("label", "Dining event")
                                .put(
                                        "icon",
                                        new JSONObject()
                                                .put("kind", "image")
                                                .put("src", "asset:feed/icons/check.svg")))
                .put("actions", new JSONObject().put("open-calendar", action))
                .put(
                        "ui",
                        new JSONObject()
                                .put("catalog", "taku-feed-1")
                                .put(
                                        "spec",
                                        new JSONObject()
                                                .put("root", "root")
                                                .put("elements", b.elements)));
    }
}
