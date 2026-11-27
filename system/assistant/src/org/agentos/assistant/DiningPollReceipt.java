package org.agentos.assistant;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a confirmed receipt from the native OpenTable reservation details screen. */
final class DiningPollReceipt {
    private static final String OPEN_TABLE = "com.opentable";
    private static final String ASSISTANT = "org.agentos.assistant";
    private static final String DETAILS = OPEN_TABLE + ":id/reservation_details_container";
    private static final Pattern PARTY =
            Pattern.compile("(?i)^(?:party of )?([1-9][0-9]?)(?: (?:people|guests?))?$");
    private static final Pattern PARTY_AND_DATE = Pattern.compile("^([1-9][0-9]?)\\s+(.+)$");
    private static final Pattern CHINESE_DATE =
            Pattern.compile(
                    "^(?:([0-9]{4})年)?([0-9]{1,2})月([0-9]{1,2})日(?:周[一二三四五六日天]|星期[一二三四五六日天])?"
                            + "\\s*(?:at\\s+)?(上午|下午)?\\s*([0-9]{1,2}):([0-9]{2})$");
    private static final Pattern ENGLISH_DATE =
            Pattern.compile(
                    "(?i)^(?:(?:Mon(?:day)?|Tue(?:sday)?|Wed(?:nesday)?|Thu(?:rsday)?|Fri(?:day)?|Sat(?:urday)?|Sun(?:day)?)"
                        + ",?\\s+)?(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?"
                        + "|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+([0-9]{1,2})"
                        + "(?:,?\\s+([0-9]{4}))?\\s*(?:at\\s+|,\\s*)?([0-9]{1,2}):([0-9]{2})\\s*(AM|PM)?$");

    private record DateTime(Integer year, int month, int day, LocalTime time) {}

    private record Snapshot(int windowId, AccessibilityNodeInfo root, Receipt receipt) {}

    private record Fields(String dateTime, int partySize, String address) {}

    record Receipt(
            String restaurant,
            String dateTime,
            int partySize,
            String address,
            Integer year,
            int month,
            int day,
            LocalTime time) {
        String draftText() {
            String date = (year == null ? "" : year + "年") + month + "月" + day + "日";
            return String.format(
                    Locale.ROOT,
                    "订位已确认：%s，%s %02d:%02d（餐厅当地时间），%d人。",
                    restaurant,
                    date,
                    time.getHour(),
                    time.getMinute(),
                    partySize);
        }
    }

    static boolean matchesDate(Receipt receipt, java.time.ZonedDateTime target) {
        return receipt.year() != null
                && receipt.year() == target.getYear()
                && receipt.month() == target.getMonthValue()
                && receipt.day() == target.getDayOfMonth()
                && receipt.time().equals(target.toLocalTime());
    }

    private DiningPollReceipt() {}

    static Receipt read(UiAutomation ui) throws IOException, InterruptedException {
        return read(ui, 0);
    }

    static Receipt read(UiAutomation ui, int displayId) throws IOException, InterruptedException {
        return read(ui, displayId, null);
    }

    static Receipt read(UiAutomation ui, int displayId, LocalDate provenDate)
            throws IOException, InterruptedException {
        Snapshot first = snapshot(ui, displayId, provenDate);
        Thread.sleep(100);
        Snapshot second = snapshot(ui, displayId, provenDate);
        if (first.windowId() != second.windowId()
                || !first.root().equals(second.root())
                || !first.receipt().equals(second.receipt()))
            throw failed("DINING_RESERVATION_RECEIPT_CHANGED");
        return second.receipt();
    }

    private static Snapshot snapshot(UiAutomation ui, int displayId, LocalDate provenDate)
            throws IOException {
        List<AccessibilityWindowInfo> windows = ui.getWindowsOnAllDisplays().get(displayId);
        AccessibilityWindowInfo top = null;
        AccessibilityNodeInfo root = null;
        boolean ambiguous = false;
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo candidate = window.getRoot();
                if (candidate == null) continue;
                if (candidate != null && ASSISTANT.contentEquals(value(candidate.getPackageName())))
                    continue;
                if (top == null || window.getLayer() > top.getLayer()) {
                    top = window;
                    root = candidate;
                    ambiguous = false;
                } else if (window.getLayer() == top.getLayer()) ambiguous = true;
            }
        }
        if (ambiguous
                || root == null
                || !root.refresh()
                || root.getWindowId() != top.getId()
                || !OPEN_TABLE.contentEquals(value(root.getPackageName())))
            throw failed("DINING_RESERVATION_RECEIPT_WINDOW_UNVERIFIED");

        Rect bounds = new Rect();
        top.getBoundsInScreen(bounds);
        AccessibilityNodeInfo container = null;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(DETAILS)) {
            if (!DETAILS.equals(node.getViewIdResourceName()) || !owned(node, top.getId(), bounds))
                continue;
            if (container != null) throw failed("DINING_RESERVATION_RECEIPT_DETAILS_AMBIGUOUS");
            container = node;
        }
        if (container == null) throw failed("DINING_RESERVATION_RECEIPT_DETAILS_UNAVAILABLE");

        List<String> texts = new ArrayList<>();
        collectText(container, top.getId(), bounds, texts, 0);
        return new Snapshot(top.getId(), root, parse(texts, provenDate));
    }

    private static void collectText(
            AccessibilityNodeInfo node, int windowId, Rect bounds, List<String> texts, int depth)
            throws IOException {
        if (depth > 40 || texts.size() > 120)
            throw failed("DINING_RESERVATION_RECEIPT_DETAILS_AMBIGUOUS");
        if (!owned(node, windowId, bounds)) return;
        // OpenTable places an optional seating summary between time and Manage booking.
        // Exclude only that native role; unknown extra fields must still fail parsing.
        if ((OPEN_TABLE + ":id/dining_mode_header_seating").equals(node.getViewIdResourceName()))
            return;
        CharSequence text = node.getText();
        if (text != null && !text.toString().isBlank()) {
            texts.add(text.toString().replace('\u00a0', ' ').replace('\u202f', ' ').strip());
            return;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) collectText(child, windowId, bounds, texts, depth + 1);
        }
    }

    private static boolean owned(AccessibilityNodeInfo node, int windowId, Rect windowBounds) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return node.getWindowId() == windowId
                && OPEN_TABLE.contentEquals(value(node.getPackageName()))
                && node.isVisibleToUser()
                && !bounds.isEmpty()
                && Rect.intersects(windowBounds, bounds);
    }

    static Receipt parse(List<String> texts) throws IOException {
        return parse(texts, null);
    }

    static Receipt parse(List<String> texts, LocalDate provenDate) throws IOException {
        int status = marker(texts, "Reservation confirmed", "预订已确认");
        int manage = marker(texts, "Manage booking", "管理预订");
        if (status < 1 || manage <= status + 1)
            throw failed("DINING_RESERVATION_RECEIPT_NOT_CONFIRMED");
        String restaurant = texts.get(status - 1);
        if (!plain(restaurant, 160) || !Character.isLetterOrDigit(restaurant.codePointAt(0)))
            throw failed("DINING_RESERVATION_RECEIPT_RESTAURANT_INVALID");
        Fields result = null;
        for (int start = status + 1; start < manage && start <= status + 2; start++) {
            String address = start == status + 1 ? "" : texts.get(status + 1);
            if (!address.isEmpty() && !plain(address, 240))
                throw failed("DINING_RESERVATION_RECEIPT_ADDRESS_INVALID");
            Fields candidate = fields(texts, start, manage, address);
            if (candidate == null) continue;
            if (result != null) throw failed("DINING_RESERVATION_RECEIPT_DETAILS_AMBIGUOUS");
            result = candidate;
        }
        if (result == null) throw failed("DINING_RESERVATION_RECEIPT_FIELDS_INVALID");
        DateTime date = resolveDate(result.dateTime(), provenDate);
        return new Receipt(
                restaurant,
                result.dateTime(),
                result.partySize(),
                result.address(),
                date.year(),
                date.month(),
                date.day(),
                date.time());
    }

    private static Fields fields(List<String> texts, int start, int end, String address) {
        String dateTime;
        Matcher party;
        if (start + 2 == end) {
            party = PARTY.matcher(texts.get(start));
            if (!party.matches()) return null;
            dateTime = texts.get(start + 1);
        } else if (start + 1 == end) {
            party = PARTY_AND_DATE.matcher(texts.get(start));
            if (!party.matches()) return null;
            dateTime = party.group(2);
        } else return null;
        if (dateTime(dateTime) == null && relative(dateTime) == null) return null;
        return new Fields(dateTime, Integer.parseInt(party.group(1)), address);
    }

    /** In Modify booking, the chosen native calendar date must match the original summary. */
    static LocalTime timeForDate(String label, LocalDate nativeDate) throws IOException {
        return resolveDate(label, nativeDate).time();
    }

    private static DateTime resolveDate(String label, LocalDate provenDate) throws IOException {
        DateTime absolute = dateTime(label);
        if (absolute != null && absolute.year() != null) {
            if (provenDate != null
                    && !LocalDate.of(absolute.year(), absolute.month(), absolute.day())
                            .equals(provenDate)) {
                throw failed("RESERVATION_DATE_CONTEXT_CHANGED");
            }
            return absolute;
        }
        Relative weekday = absolute == null ? relative(label) : null;
        if (absolute == null && weekday == null)
            throw failed("DINING_RESERVATION_RECEIPT_FIELDS_INVALID");
        if (provenDate == null) throw failed("RECEIPT_FULL_DATE_REQUIRED");

        if (absolute != null
                ? absolute.month() != provenDate.getMonthValue()
                        || absolute.day() != provenDate.getDayOfMonth()
                : weekday.day() != provenDate.getDayOfWeek()) {
            throw failed("RESERVATION_DATE_CONTEXT_CHANGED");
        }
        LocalTime time = absolute != null ? absolute.time() : weekday.time();
        return new DateTime(
                provenDate.getYear(), provenDate.getMonthValue(), provenDate.getDayOfMonth(), time);
    }

    record Relative(DayOfWeek day, LocalTime time) {}

    static Relative relative(String value) {
        value = value.replace('\u00a0', ' ').replace('\u202f', ' ');
        Matcher chinese =
                Pattern.compile(
                                "^(?:星期|周)([一二三四五六日天])\\s*(?:at\\s*)?(上午|下午)?\\s*([0-9]{1,2}):([0-9]{2})$")
                        .matcher(value);
        Matcher english =
                Pattern.compile(
                                "(?i)^(Mon(?:day)?|Tue(?:sday)?|Wed(?:nesday)?|Thu(?:rsday)?|Fri(?:day)?|Sat(?:urday)?|Sun(?:day)?),?\\s*(?:at\\s*)?([0-9]{1,2}):([0-9]{2})\\s*(AM|PM)?$")
                        .matcher(value);
        try {
            if (chinese.matches()) {
                int day = "一二三四五六日天".indexOf(chinese.group(1)) + 1;
                if (day == 8) day = 7;
                DateTime time =
                        checked(null, 1, 1, chinese.group(3), chinese.group(4), chinese.group(2));
                return time == null ? null : new Relative(DayOfWeek.of(day), time.time());
            }
            if (english.matches()) {
                int day =
                        List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")
                                        .indexOf(
                                                english.group(1)
                                                        .substring(0, 3)
                                                        .toLowerCase(Locale.ROOT))
                                + 1;
                DateTime time =
                        checked(null, 1, 1, english.group(2), english.group(3), english.group(4));
                return time == null ? null : new Relative(DayOfWeek.of(day), time.time());
            }
        } catch (DateTimeException invalid) {
            return null;
        }
        return null;
    }

    private static DateTime dateTime(String value) {
        Matcher chinese = CHINESE_DATE.matcher(value);
        Matcher english = ENGLISH_DATE.matcher(value);
        try {
            if (chinese.matches()) {
                return checked(
                        chinese.group(1),
                        Integer.parseInt(chinese.group(2)),
                        Integer.parseInt(chinese.group(3)),
                        chinese.group(5),
                        chinese.group(6),
                        chinese.group(4));
            }
            if (english.matches()) {
                String prefix = english.group(1).substring(0, 3).toUpperCase(Locale.ROOT);
                for (Month month : Month.values()) {
                    if (month.name().startsWith(prefix))
                        return checked(
                                english.group(3),
                                month.getValue(),
                                Integer.parseInt(english.group(2)),
                                english.group(4),
                                english.group(5),
                                english.group(6));
                }
            }
        } catch (DateTimeException invalid) {
            return null;
        }
        return null;
    }

    private static DateTime checked(
            String yearText,
            int month,
            int day,
            String hourText,
            String minuteText,
            String period) {
        Integer year = yearText == null ? null : Integer.valueOf(yearText);
        MonthDay date = MonthDay.of(month, day);
        if (year != null && (year < 1 || !date.isValidYear(year))) return null;
        int hour = Integer.parseInt(hourText);
        if (period != null) {
            if (hour < 1 || hour > 12) return null;
            hour = hour % 12 + (period.equals("下午") || period.equalsIgnoreCase("PM") ? 12 : 0);
        }
        return new DateTime(year, month, day, LocalTime.of(hour, Integer.parseInt(minuteText)));
    }

    private static int marker(List<String> texts, String english, String chinese)
            throws IOException {
        int index = -1;
        for (int current = 0; current < texts.size(); current++) {
            String text = texts.get(current);
            if (!english.equalsIgnoreCase(text) && !chinese.equals(text)) continue;
            if (index >= 0) throw failed("DINING_RESERVATION_RECEIPT_DETAILS_AMBIGUOUS");
            index = current;
        }
        return index;
    }

    private static boolean plain(String text, int max) {
        return !text.isBlank()
                && text.length() <= max
                && text.codePoints()
                        .noneMatch(
                                code ->
                                        Character.isISOControl(code)
                                                || Character.getType(code) == Character.FORMAT
                                                || Character.getType(code)
                                                        == Character.LINE_SEPARATOR
                                                || Character.getType(code)
                                                        == Character.PARAGRAPH_SEPARATOR
                                                || code == '<'
                                                || code == '>');
    }

    private static CharSequence value(CharSequence text) {
        return text == null ? "" : text;
    }

    private static IOException failed(String code) {
        return new IOException(code);
    }
}
