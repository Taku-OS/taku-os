package org.agentos.assistant;

import android.app.ActivityManager;
import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only evidence for the exact saved event opened from its current Feed card. */
final class DiningBookingRideUi {
    private static final String CALENDAR = "com.google.android.calendar";

    record Probe(boolean matched, String reason, int taskId) {}

    private DiningBookingRideUi() {}

    static String when(JSONObject meal) throws Exception {
        return Instant.ofEpochMilli(meal.getLong("start"))
                .atZone(ZoneId.of(meal.getString("zone")))
                .format(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.ENGLISH));
    }

    static Probe details(Context context, JSONObject meal) {
        ActivityManager.RunningTaskInfo before = DiningPollUi.visible(context);
        if (before == null) return new Probe(false, "calendar-foreground-unavailable", -1);
        if (!calendar(before)) return new Probe(false, "calendar-not-visible", -1);
        try {
            String reason =
                    DiningPollUi.read(
                            ui -> {
                                AccessibilityNodeInfo root = DiningPollUi.root(ui, 0, CALENDAR);
                                List<String> texts = new ArrayList<>();
                                collect(root, texts, 0);
                                boolean edit =
                                        root
                                                .findAccessibilityNodeInfosByViewId(
                                                        CALENDAR + ":id/info_action_edit")
                                                .stream()
                                                .anyMatch(AccessibilityNodeInfo::isVisibleToUser);
                                return DiningBookingRidePolicy.matchDetails(meal, texts, edit);
                            });
            ActivityManager.RunningTaskInfo after = DiningPollUi.visible(context);
            if (!calendar(after) || before.taskId != after.taskId) {
                return new Probe(false, "calendar-screen-changed", -1);
            }
            return new Probe("matching-event-details".equals(reason), reason, before.taskId);
        } catch (Exception unavailable) {
            return new Probe(false, "calendar-details-unavailable", -1);
        }
    }

    private static boolean calendar(ActivityManager.RunningTaskInfo task) {
        return task != null
                && task.topActivity != null
                && DiningPollUi.display(task) == 0
                && CALENDAR.equals(task.topActivity.getPackageName());
    }

    private static void collect(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null
                || depth > 32
                || out.size() >= 700
                || !node.isVisibleToUser()
                || node.isEditable()
                || node.isPassword()
                || node.isAccessibilityDataSensitive()) return;
        CharSequence value = node.getText() == null ? node.getContentDescription() : node.getText();
        if (value != null && !value.toString().isBlank()) out.add(value.toString().strip());
        for (int i = 0; i < node.getChildCount() && out.size() < 700; i++)
            collect(node.getChild(i), out, depth + 1);
    }
}
