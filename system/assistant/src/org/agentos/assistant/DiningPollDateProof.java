package org.agentos.assistant;

import android.app.UiAutomation;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.IOException;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/** Reads the unchanged original date in OpenTable's real Modify booking calendar. No input path. */
final class DiningPollDateProof {
    record Facts(LocalDate date, String restaurant, String address, int people, LocalTime time) {}

    private record Snapshot(int window, Facts facts) {}

    static Facts read(UiAutomation ui, int display) throws Exception {
        Snapshot first = snapshot(ui, display);
        Thread.sleep(100);
        Snapshot second = snapshot(ui, display);
        if (!first.equals(second)) throw new IOException("RESERVATION_DATE_CHANGED");
        return second.facts();
    }

    private static Snapshot snapshot(UiAutomation ui, int display) throws Exception {
        AccessibilityNodeInfo root = DiningPollUi.root(ui, display, "com.opentable");
        one(root, "modify_reservation_sub_title");
        AccessibilityNodeInfo original = one(root, "modify_detail_info");
        String restaurant = text(one(original, "modify_detail_title"));
        if (one(root, "continue_btn").isEnabled() || !one(root, "no_changes_btn").isEnabled())
            throw new IOException("RESERVATION_EDIT_NOT_UNCHANGED");
        String party = text(one(root, "party_size_count"));
        Matcher count =
                Pattern.compile("(?i)^([1-9][0-9]?)\\s*(?:people|guests?|人)$").matcher(party);
        if (!count.matches()) throw new IOException("RESERVATION_DATE_PARTY_UNVERIFIED");
        int people = Integer.parseInt(count.group(1));
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        walk(one(root, "calendar_picker"), all, 0);
        List<LocalDate> selected = new ArrayList<>();
        for (var n : all)
            if (n.isSelected()
                    && n.getWindowId() == root.getWindowId()
                    && "com.opentable".contentEquals(n.getPackageName())
                    && "android.widget.CheckedTextView".contentEquals(n.getClassName())) {
                LocalDate date = selectedDate(DiningPollUi.value(n.getContentDescription()));
                if (date != null) selected.add(date);
            }
        if (selected.size() != 1) throw new IOException("RESERVATION_SELECTED_DATE_UNVERIFIED");
        LocalDate date = selected.get(0);
        // The same generic text id is also used by seating and other fields. Read the
        // original reservation's named rows rather than classifying every page string.
        String address = text(one(one(original, "dining_mode_location_address"), "tvwi_textview"));
        boolean originalParty =
                text(one(one(original, "modify_detail_info_party_size"), "tvwi_textview"))
                        .equals(Integer.toString(people));
        if (!originalParty || address.isEmpty() || restaurant.isEmpty())
            throw new IOException("RESERVATION_DATE_CONTEXT_UNVERIFIED");
        LocalTime time =
                DiningPollReceipt.timeForDate(
                        text(one(one(original, "modify_detail_info_date"), "tvwi_textview")), date);
        return new Snapshot(root.getWindowId(), new Facts(date, restaurant, address, people, time));
    }

    static LocalDate selectedDate(String value) {
        Matcher chinese =
                Pattern.compile("^([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日(?:星期[一二三四五六日天])?$")
                        .matcher(value);
        try {
            if (chinese.matches())
                return LocalDate.of(
                        Integer.parseInt(chinese.group(1)),
                        Integer.parseInt(chinese.group(2)),
                        Integer.parseInt(chinese.group(3)));
        } catch (DateTimeException invalid) {
            return null;
        }
        for (String format :
                List.of(
                        "EEEE, MMMM d, uuuu",
                        "MMMM d, uuuu",
                        "d MMMM uuuu",
                        "EEE, MMM d, uuuu",
                        "uuuu-MM-dd"))
            try {
                return LocalDate.parse(
                        value,
                        DateTimeFormatter.ofPattern(format, Locale.ENGLISH)
                                .withResolverStyle(ResolverStyle.STRICT));
            } catch (DateTimeParseException ignored) {
            }
        return null;
    }

    private static String text(AccessibilityNodeInfo n) {
        return DiningPollUi.value(n.getText());
    }

    private static List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root, String id) {
        String full = "com.opentable:id/" + id;
        return root.findAccessibilityNodeInfosByViewId(full).stream()
                .filter(
                        n ->
                                n.isVisibleToUser()
                                        && n.getWindowId() == root.getWindowId()
                                        && full.equals(n.getViewIdResourceName())
                                        && "com.opentable".contentEquals(n.getPackageName()))
                .toList();
    }

    private static AccessibilityNodeInfo one(AccessibilityNodeInfo root, String id)
            throws IOException {
        var found = nodes(root, id);
        if (found.size() != 1) throw new IOException("RESERVATION_DATE_CONTEXT_UNVERIFIED");
        return found.get(0);
    }

    private static void walk(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> all, int depth)
            throws IOException {
        if (n == null || !n.isVisibleToUser()) return;
        if (depth > 45 || all.size() > 1600) throw new IOException("RESERVATION_DATE_AMBIGUOUS");
        all.add(n);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), all, depth + 1);
    }
}
