package org.agentos.assistant;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.text.format.DateFormat;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only evidence for the exact saved event opened from its current Feed card. */
final class DiningBookingRideUi {
    private static final String CALENDAR = "com.google.android.calendar";

    private static final Set<String> TRANSIENT_PACKAGES =
            Set.of(
                    "org.agentos.assistant",
                    "com.android.systemui",
                    "com.android.permissioncontroller",
                    "com.google.android.permissioncontroller");
    private static final Set<String> NAVIGATION =
            Set.of("show navigation menu", "open navigation menu", "显示导航菜单", "打开导航菜单");
    private static final Set<String> OVERVIEW_ACTIONS =
            Set.of("search", "search calendar", "today", "搜索", "搜索日历", "今天");

    private record Surface(int taskId, String component, String pkg, String eventPath) {
        String key() {
            return component + "|" + eventPath;
        }
    }

    private record CalendarRead(boolean detail, boolean overview) {}

    private DiningBookingRideUi() {}

    static String when(JSONObject meal) throws Exception {
        return Instant.ofEpochMilli(meal.getLong("start"))
                .atZone(ZoneId.of(meal.getString("zone")))
                .format(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.ENGLISH));
    }

    static DiningBookingRidePolicy.Observation details(
            Context context, JSONObject meal, long eventId) {
        Surface before = foreground(context);
        if (before == null) return unavailable();
        if (!CALENDAR.equals(before.pkg())) {
            return !TRANSIENT_PACKAGES.contains(before.pkg()) && before.equals(foreground(context))
                    ? observation(DiningBookingRidePolicy.Screen.EXIT, before, "foreground:")
                    : unavailable();
        }
        try {
            String expectedPath = "/events/" + eventId;
            boolean exactEvent = expectedPath.equals(before.eventPath());
            boolean matchingEvent = exactEvent || before.eventPath().isBlank();
            boolean clock24 = DateFormat.is24HourFormat(context);
            CalendarRead read =
                    DiningPollUi.read(
                            ui -> {
                                AccessibilityNodeInfo root = DiningPollUi.root(ui, 0, CALENDAR);
                                List<String> texts = new ArrayList<>(), actions = new ArrayList<>();
                                collect(root, texts, actions, 0);
                                boolean edit =
                                        root
                                                .findAccessibilityNodeInfosByViewId(
                                                        CALENDAR + ":id/info_action_edit")
                                                .stream()
                                                .anyMatch(AccessibilityNodeInfo::isVisibleToUser);
                                boolean detail =
                                        matchingEvent
                                                && "matching-event-details"
                                                        .equals(
                                                                DiningBookingRidePolicy
                                                                        .matchDetails(
                                                                                meal,
                                                                                texts,
                                                                                edit,
                                                                                exactEvent,
                                                                                clock24));
                                boolean overview =
                                        !edit
                                                && actions.stream().anyMatch(NAVIGATION::contains)
                                                && actions.stream()
                                                        .anyMatch(OVERVIEW_ACTIONS::contains);
                                return new CalendarRead(detail, overview);
                            });
            if (!before.equals(foreground(context))) return unavailable();
            if (read.detail())
                return observation(DiningBookingRidePolicy.Screen.DETAILS, before, "details:");
            if (read.overview())
                return observation(DiningBookingRidePolicy.Screen.EXIT, before, "overview:");
        } catch (Exception unavailable) {
            return unavailable();
        }
        return unavailable();
    }

    private static Surface foreground(Context context) {
        ActivityManager.RunningTaskInfo task;
        try {
            task = DiningPollUi.visible(context);
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (task == null
                || task.topActivity == null
                || task.taskId <= 0
                || !task.isVisible()
                || DiningPollUi.display(task) != 0) return null;
        Uri data = task.baseIntent == null ? null : task.baseIntent.getData();
        String path = data == null ? null : data.getEncodedPath();
        String eventPath =
                data != null
                                && "content".equals(data.getScheme())
                                && "com.android.calendar".equals(data.getAuthority())
                                && path != null
                                && path.matches("/events/[1-9][0-9]*")
                        ? path
                        : "";
        return new Surface(
                task.taskId,
                task.topActivity.flattenToShortString(),
                task.topActivity.getPackageName(),
                eventPath);
    }

    private static DiningBookingRidePolicy.Observation observation(
            DiningBookingRidePolicy.Screen screen, Surface surface, String kind) {
        return new DiningBookingRidePolicy.Observation(
                screen, kind + surface.key(), surface.taskId());
    }

    private static DiningBookingRidePolicy.Observation unavailable() {
        return new DiningBookingRidePolicy.Observation(
                DiningBookingRidePolicy.Screen.UNAVAILABLE, "", -1);
    }

    private static void collect(
            AccessibilityNodeInfo node, List<String> out, List<String> actions, int depth) {
        if (node == null
                || depth > 32
                || out.size() >= 700
                || !node.isVisibleToUser()
                || node.isEditable()
                || node.isPassword()
                || node.isAccessibilityDataSensitive()) return;
        CharSequence value = node.getText() == null ? node.getContentDescription() : node.getText();
        if (value != null && !value.toString().isBlank()) out.add(value.toString().strip());
        if (node.isClickable()) {
            CharSequence label =
                    node.getContentDescription() == null ? value : node.getContentDescription();
            if (label != null) actions.add(label.toString().strip().toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < node.getChildCount() && out.size() < 700; i++)
            collect(node.getChild(i), out, actions, depth + 1);
    }
}
