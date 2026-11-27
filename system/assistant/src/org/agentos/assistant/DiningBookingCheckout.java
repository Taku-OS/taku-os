package org.agentos.assistant;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native checkout guard for the one journaled OpenTable submit attempt. */
final class DiningBookingCheckout {
    private static final String PACKAGE = "com.opentable";
    private static final String VIEW = PACKAGE + ":id/";
    private static final String CONFIRM = VIEW + "confirmation_continue_btn";
    private static final Set<String> CHECKOUT =
            Set.of(
                    "confirm_reservation_details",
                    "confirm_reservation_scroll_view",
                    "confirmation_container",
                    "confirmation_fragment",
                    "reservation_details_container");
    private static final Set<String> SUMMARY =
            Set.of("confirm_reservation_details", "reservation_details_container");
    private static final Pattern PARTY =
            Pattern.compile(
                    "(?iu)^(?:table for |party of"
                            + " )?([1-9][0-9]?)\\s*(?:people|person|guests?|人)?$");
    private static final String TIME_TEXT = "(?:上午|下午)?\\s*[0-9]{1,2}:[0-9]{2}(?:\\s*(?:AM|PM))?";
    private static final Pattern TIME =
            Pattern.compile("(?iu)^(?:(上午|下午)\\s*)?([0-9]{1,2}):([0-9]{2})(?:\\s*(AM|PM))?$");
    private static final Pattern DATETIME =
            Pattern.compile("(?iu)^(.+?)(?:[T·,]|\\s+(?:at\\s+)?|(?<=日))(" + TIME_TEXT + ")$");
    private static final Pattern SHORT_CHINESE_DATE =
            Pattern.compile("^([0-9]{1,2})月([0-9]{1,2})日$");
    private static final Pattern WEEKDAY =
            Pattern.compile(
                    "(?iu)^(?:mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?),?\\s+");

    record Target(String restaurant, String address, LocalDate date, LocalTime time, int people) {}

    record Control(
            AccessibilityNodeInfo root,
            AccessibilityNodeInfo button,
            Rect bounds,
            String label,
            List<String> summary) {}

    private static final Set<String> CONFIRM_LABELS =
            Set.of(
                    "confirm reservation",
                    "complete reservation",
                    "confirm booking",
                    "complete booking",
                    "确认预订",
                    "完成预订");
    private static final Set<String> BLOCKED_IDS =
            Set.of(
                    "confirmation_gpay_btn",
                    "confirmation_payment_details",
                    "no_changes_btn",
                    "history_root",
                    "upcoming_reso_root");
    private static final Pattern MONEY_TERMS =
            Pattern.compile(
                    "(?iu)\\b(deposits?|fees?|penalt(?:y|ies)|charg(?:e|ed|es|ing)|prepay(?:ment)?|credit"
                        + " card|payment|commitment to pay|non.?refundable|cancellation policy)\\b"
                        + "|(?:USD|EUR|GBP|CAD|AUD|CNY)\\s*\\d|[$€£¥]\\s*\\d|订金|定金|押金|付款|支付|费用|罚金|收费");

    private DiningBookingCheckout() {}

    static Control verify(AccessibilityNodeInfo root, Target target, LocalDate provenDate)
            throws IOException {
        List<AccessibilityNodeInfo> controls =
                root.findAccessibilityNodeInfosByViewId(CONFIRM).stream()
                        .filter(
                                node ->
                                        node.getWindowId() == root.getWindowId()
                                                && node.isVisibleToUser()
                                                && PACKAGE.contentEquals(node.getPackageName()))
                        .toList();
        if (controls.size() != 1) throw new IOException("OPENTABLE_CHECKOUT_NOT_VISIBLE");
        AccessibilityNodeInfo control = controls.get(0);
        if (!confirmControl(control, root.getWindowId()))
            throw new IOException("OPENTABLE_CHECKOUT_NOT_VISIBLE");
        var summaries = new ArrayList<AccessibilityNodeInfo>();
        inspect(root, root.getWindowId(), 0, new int[1], summaries);
        var leaves =
                summaries.stream()
                        .filter(
                                node ->
                                        summaries.stream()
                                                .noneMatch(
                                                        other ->
                                                                !other.equals(node)
                                                                        && under(other, node)))
                        .toList();
        if (leaves.size() != 1) throw unavailable();
        List<String> fields = new ArrayList<>();
        collectSummary(leaves.get(0), fields, 0);
        verifySummary(fields, target, provenDate);
        Rect bounds = new Rect();
        control.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) throw unavailable();
        return new Control(root, control, bounds, label(control), List.copyOf(fields));
    }

    static boolean click(Control expected) throws IOException {
        AccessibilityNodeInfo button = expected.button();
        if (!confirmControl(button, expected.root().getWindowId()))
            throw new IOException("OPENTABLE_CHECKOUT_CONTROL_CHANGED");
        Rect bounds = new Rect();
        button.getBoundsInScreen(bounds);
        if (!expected.label().equals(label(button)) || !expected.bounds().equals(bounds))
            throw new IOException("OPENTABLE_CHECKOUT_CONTROL_CHANGED");
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean confirmControl(AccessibilityNodeInfo button, int window) {
        return button.refresh()
                && button.isVisibleToUser()
                && button.getWindowId() == window
                && CONFIRM.equals(button.getViewIdResourceName())
                && PACKAGE.contentEquals(button.getPackageName())
                && button.isClickable()
                && button.isEnabled()
                && inCheckout(button)
                && button.getActionList().stream()
                        .anyMatch(action -> action.getId() == AccessibilityNodeInfo.ACTION_CLICK)
                && CONFIRM_LABELS.contains(label(button).toLowerCase(Locale.ROOT));
    }

    private static boolean under(AccessibilityNodeInfo node, AccessibilityNodeInfo parent) {
        for (int i = 0; i < 40 && node != null; i++, node = node.getParent()) {
            if (parent.equals(node)) return true;
        }
        return false;
    }

    private static void collectSummary(AccessibilityNodeInfo node, List<String> fields, int depth)
            throws IOException {
        if (node == null || !node.isVisibleToUser()) return;
        if (depth > 25 || fields.size() >= 12) throw unavailable();
        if (Set.of("dining_mode_header_seating", "confirmation_continue_btn").contains(id(node)))
            return;
        String text =
                DiningPollUi.value(node.getText()).replace('\u00a0', ' ').replace('\u202f', ' ');
        if (!text.isEmpty()) {
            if (node.isEditable() || text.length() > 350 || text.lines().count() != 1)
                throw unavailable();
            fields.add(text);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++)
            collectSummary(node.getChild(i), fields, depth + 1);
    }

    /**
     * Only one structured summary can authorize the target; incidental or duplicate labels fail.
     */
    static void verifySummary(List<String> fields, Target target, LocalDate proof)
            throws IOException {
        if (fields.size() < 4
                || fields.size() > 5
                || !DiningRestaurantIdentity.sameName(target.restaurant(), fields.get(0)))
            throw unavailable();
        try {
            DiningPollReceiptTarget.verifyAddress(target.address(), fields.get(1));
        } catch (IOException changed) {
            throw unavailable();
        }
        List<String> when = new ArrayList<>();
        Integer party = null;
        for (String field : fields.subList(2, fields.size())) {
            Matcher count = PARTY.matcher(field);
            if (!count.matches()) {
                when.add(field);
                continue;
            }
            if (party != null) throw unavailable();
            party = Integer.parseInt(count.group(1));
        }
        if (party == null || party != target.people()) throw unavailable();
        String dateLabel;
        String timeLabel;
        if (when.size() == 1) {
            var combined = DATETIME.matcher(when.get(0));
            if (!combined.matches()) throw unavailable();
            dateLabel = combined.group(1).strip();
            timeLabel = combined.group(2).strip();
        } else if (when.size() == 2) {
            dateLabel = when.get(0);
            timeLabel = when.get(1);
        } else throw unavailable();
        LocalTime time = time(timeLabel);
        if (!target.time().equals(time)) throw unavailable();
        LocalDate full = fullDate(dateLabel);
        if (full != null) {
            if (!target.date().equals(full)) throw unavailable();
            return;
        }
        MonthDay partial = monthDay(dateLabel);
        if (partial == null
                || !target.date().equals(proof)
                || !partial.equals(MonthDay.from(proof))) throw unavailable();
    }

    private static LocalDate fullDate(String label) {
        LocalDate known = DiningPollDateProof.selectedDate(label);
        if (known != null) return known;
        for (String format : List.of("MMM d, uuuu", "EEE, MMM d, uuuu", "MMMM d uuuu")) {
            try {
                return LocalDate.parse(
                        label,
                        DateTimeFormatter.ofPattern(format, Locale.ENGLISH)
                                .withResolverStyle(java.time.format.ResolverStyle.STRICT));
            } catch (DateTimeException ignored) {
            }
        }
        return null;
    }

    private static MonthDay monthDay(String label) {
        Matcher chinese = SHORT_CHINESE_DATE.matcher(label.replaceFirst("(?:周|星期)[一二三四五六日天]$", ""));
        if (chinese.matches()) {
            try {
                return MonthDay.of(
                        Integer.parseInt(chinese.group(1)), Integer.parseInt(chinese.group(2)));
            } catch (DateTimeException invalid) {
                return null;
            }
        }
        String date = WEEKDAY.matcher(label).replaceFirst("");
        for (String format : List.of("MMM d", "MMMM d")) {
            try {
                return MonthDay.parse(
                        date,
                        DateTimeFormatter.ofPattern(format, Locale.ENGLISH)
                                .withResolverStyle(java.time.format.ResolverStyle.STRICT));
            } catch (DateTimeException ignored) {
            }
        }
        return null;
    }

    private static LocalTime time(String label) {
        Matcher time = TIME.matcher(label);
        if (!time.matches() || time.group(1) != null && time.group(4) != null) return null;
        int hour = Integer.parseInt(time.group(2));
        String period = time.group(1) != null ? time.group(1) : time.group(4);
        if (period != null) {
            if (hour < 1 || hour > 12) return null;
            hour = hour % 12 + ("下午".equals(period) || "PM".equalsIgnoreCase(period) ? 12 : 0);
        }
        try {
            return LocalTime.of(hour, Integer.parseInt(time.group(3)));
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    private static IOException unavailable() {
        return new IOException("OPENTABLE_CHECKOUT_TARGET_UNVERIFIED");
    }

    private static boolean inCheckout(AccessibilityNodeInfo node) {
        for (int i = 0; i < 30 && node != null; i++, node = node.getParent()) {
            if (CHECKOUT.contains(id(node))) return true;
        }
        return false;
    }

    private static void inspect(
            AccessibilityNodeInfo node,
            int window,
            int depth,
            int[] count,
            List<AccessibilityNodeInfo> summaries)
            throws IOException {
        if (node == null || !node.isVisibleToUser()) return;
        if (window != node.getWindowId()
                || !PACKAGE.contentEquals(node.getPackageName())
                || depth > 40
                || ++count[0] > 2_000) throw new IOException("OPENTABLE_CHECKOUT_NOT_VISIBLE");
        if (requiresReview(
                        id(node),
                        DiningPollUi.value(node.getText()),
                        DiningPollUi.value(node.getContentDescription()))
                || node.isScrollable()
                        && node.getActionList().stream()
                                .anyMatch(
                                        action ->
                                                action.getId()
                                                                == AccessibilityNodeInfo
                                                                        .ACTION_SCROLL_FORWARD
                                                        || action.getId()
                                                                == AccessibilityNodeInfo
                                                                        .ACTION_SCROLL_BACKWARD))
            throw new IOException("OPENTABLE_TERMS_REQUIRE_USER_REVIEW");
        if (SUMMARY.contains(id(node))) summaries.add(node);
        for (int i = 0; i < node.getChildCount(); i++)
            inspect(node.getChild(i), window, depth + 1, count, summaries);
    }

    static boolean requiresReview(String id, String text, String description) {
        return BLOCKED_IDS.contains(id)
                || MONEY_TERMS.matcher(text).find()
                || MONEY_TERMS.matcher(description).find();
    }

    private static String label(AccessibilityNodeInfo node) {
        String text = DiningPollUi.value(node.getText());
        return text.isEmpty() ? DiningPollUi.value(node.getContentDescription()) : text;
    }

    private static String id(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        return id != null && id.startsWith(VIEW) ? id.substring(VIEW.length()) : "";
    }
}
