package org.agentos.assistant;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns requested OpenTable navigation into a verified native action, without relaying raw input.
 */
final class DiningBookingNavigation {
    private static final String PACKAGE = "com.opentable";
    private static final String RESOURCE = PACKAGE + ":id/";
    private static final int CLICK = AccessibilityNodeInfo.ACTION_CLICK;
    private static final int WRITE = AccessibilityNodeInfo.ACTION_SET_TEXT;
    private static final int FORWARD = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
    private static final int BACKWARD = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
    private static final Set<String> CHECKOUT =
            Set.of(
                    "confirm_reservation_details",
                    "confirm_reservation_scroll_view",
                    "confirmation_container",
                    "confirmation_fragment",
                    "confirmation_continue_btn",
                    "confirmation_gpay_btn",
                    "confirmation_payment_details",
                    "checkout_app_bar");
    private static final Set<String> SAFE_PAGE =
            Set.of(
                    "rest_profile_root",
                    "search_results_fragment",
                    "combined_search_list_fragment",
                    "combined_search_header",
                    "profile_fragment_container",
                    "home_container",
                    "home_feed_recycler",
                    "history_fragment",
                    "history_root",
                    "autocomplete_container",
                    "autocomplete_fragment_container",
                    "search_fragment_container",
                    "main_fragment",
                    "full_availability_root",
                    "full_availability_party_size_scroll_view",
                    "bottom_sheet_dtp",
                    "calendar_picker",
                    "calendar_view",
                    "date_picker");
    private static final Set<String> SEARCH_EDITORS =
            Set.of(
                    "search_edit",
                    "search_edit_text",
                    "search_input",
                    "search_src_text",
                    "autocomplete_search");
    private static final Set<String> NAV_TAP =
            Set.of(
                    "search_edit",
                    "search_edit_text",
                    "search_input",
                    "search_cell_restaurant_name",
                    "search_results_term_and_location",
                    "rest_profile_dtp",
                    "dtp_button",
                    "bottom_sheet_date",
                    "bottom_sheet_time",
                    "bottom_sheet_party_size",
                    "reservation_headers",
                    "header_label",
                    "autocomplete_item",
                    "action_profile",
                    "upcoming_reso_root",
                    "upcoming_reso_card",
                    "upcoming_reso_title",
                    "rest_profile_date_button",
                    "bottom_sheet_done",
                    "action_search");
    private static final Set<String> NAV_LABELS =
            Set.of(
                    "search",
                    "search restaurants",
                    "profile",
                    "account",
                    "reservations",
                    "my reservations",
                    "view reservations",
                    "find a table",
                    "see all times",
                    "apply",
                    "done",
                    "搜索",
                    "查找餐厅",
                    "预约",
                    "查看预约",
                    "完成",
                    "应用");
    private static final Set<String> BACK = Set.of("navigate up", "back", "close", "返回", "关闭");
    private static final Set<String> BACK_IDS =
            Set.of("", "back", "up", "action_back", "action_bar_back");
    private static final Set<String> EDIT_DATE = Set.of("date", "choose date", "日期", "选择日期");
    private static final Set<String> EDIT_DATE_IDS =
            Set.of("modify_detail_info_date", "bottom_sheet_date", "date_picker");
    private static final Set<String> CALENDAR =
            Set.of("calendar_picker", "calendar_view", "date_picker");
    private static final Set<String> PARTY =
            Set.of(
                    "party_size_radio_group",
                    "party_size_horizontal_scroll_view",
                    "full_availability_party_size_scroll_view",
                    "bottom_sheet_party_size");
    private static final String RECEIPT = "reservation_details_container";
    private static final Set<String> CONFIRMED = Set.of("reservation confirmed", "预订已确认");
    private static final Set<String> MANAGE = Set.of("manage booking", "管理预订");
    private static final Set<String> MODIFY = Set.of("modify booking", "修改预订");
    private static final Set<String> FINAL_LABELS =
            Set.of(
                    "confirm reservation",
                    "complete reservation",
                    "confirm booking",
                    "complete booking",
                    "book now",
                    "reserve now",
                    "book with commitment to pay",
                    "确认预订",
                    "完成预订");
    private static final Set<String> CANCEL_LABELS =
            Set.of("cancel reservation", "cancel booking", "取消预订");

    private DiningBookingNavigation() {}

    interface Root {
        AccessibilityNodeInfo get() throws Exception;
    }

    interface Guard {
        void check() throws Exception;
    }

    private record Row(
            AccessibilityNodeInfo node,
            String id,
            String label,
            Set<String> scopes,
            Rect bounds,
            boolean enabled,
            boolean editable,
            boolean password,
            boolean focused,
            Set<Integer> actions) {}

    private record Page(
            AccessibilityNodeInfo root, List<Row> rows, Set<String> ids, Set<String> labels) {}

    private record Plan(AccessibilityNodeInfo root, Row row, int action) {}

    static void apply(Root root, Guard guard, JSONObject input) throws Exception {
        guard.check();
        Plan first = plan(snapshot(root.get()), input);
        Thread.sleep(80);
        guard.check();
        Page page = snapshot(root.get());
        Plan verified = plan(page, input);
        if (!first.equals(verified)) throw denied();
        Row row = verified.row();
        if (!row.node().refresh()
                || !row.equals(row(row.node(), row.scopes(), page.root().getWindowId())))
            throw denied();
        guard.check();
        boolean applied;
        if (verified.action() == WRITE) {
            Bundle text = new Bundle();
            text.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    input.getString("text"));
            applied = row.node().performAction(WRITE, text);
        } else applied = row.node().performAction(verified.action());
        if (!applied) throw new IOException("OPENTABLE_NAVIGATION_UNCONFIRMED");
    }

    private static Plan plan(Page page, JSONObject input) throws Exception {
        String tool = input.getString("tool");
        boolean editing = page.ids().contains("modify_detail_info");
        boolean checkout =
                !Collections.disjoint(page.ids(), CHECKOUT)
                        || !Collections.disjoint(page.labels(), FINAL_LABELS);
        boolean safePage = !Collections.disjoint(page.ids(), SAFE_PAGE) && !checkout && !editing;
        List<Row> candidates;
        int action;
        switch (tool) {
            case "tap" -> {
                int x = coordinate(input, "x", 720), y = coordinate(input, "y", 1280);
                if (input.length() != 3) throw denied();
                candidates =
                        page.rows().stream()
                                .filter(
                                        row ->
                                                row.actions().contains(CLICK)
                                                        && row.bounds().contains(x, y))
                                .toList();
                if (candidates.size() != 1
                        || !safeTap(page, candidates.get(0), safePage, checkout, editing))
                    throw denied();
                action = CLICK;
            }
            case "type_text" -> {
                Object value = input.opt("text");
                if (!safePage
                        || input.length() != 2
                        || !(value instanceof String text)
                        || text.isBlank()
                        || text.length() > 180
                        || text.codePoints().anyMatch(Character::isISOControl)) throw denied();
                candidates =
                        page.rows().stream()
                                .filter(
                                        row ->
                                                SEARCH_EDITORS.contains(row.id())
                                                        && row.editable()
                                                        && row.focused()
                                                        && !row.password()
                                                        && row.actions().contains(WRITE))
                                .toList();
                action = WRITE;
            }
            case "swipe" -> {
                int x = coordinate(input, "fromX", 720), y = coordinate(input, "fromY", 1280);
                int toX = coordinate(input, "toX", 720), toY = coordinate(input, "toY", 1280);
                coordinate(input, "durationMs", 5_001);
                if (!safePage || input.length() != 6) throw denied();
                int dx = toX - x, dy = toY - y;
                boolean horizontal = Math.abs(dx) > Math.abs(dy);
                int delta = horizontal ? dx : dy;
                if (Math.abs(delta) < 24) throw denied();
                action = delta < 0 ? FORWARD : BACKWARD;
                int movement = action;
                candidates =
                        page.rows().stream()
                                .filter(
                                        row ->
                                                row.actions().contains(movement)
                                                        && row.bounds().contains(x, y)
                                                        && row.bounds().contains(toX, toY)
                                                        && (!horizontal
                                                                || !Collections.disjoint(
                                                                        row.scopes(), PARTY)))
                                .toList();
            }
            default -> throw denied();
        }
        if (candidates.size() != 1 || !candidates.get(0).enabled()) throw denied();
        return new Plan(page.root(), candidates.get(0), action);
    }

    private static boolean safeTap(
            Page page, Row row, boolean safePage, boolean checkout, boolean editing) {
        String id = row.id(), label = row.label();
        Set<String> scopes = row.scopes();
        if (rejectsTap(id, label)) return false;
        if (BACK.contains(label) && BACK_IDS.contains(id)) return true;
        if (checkout || !Collections.disjoint(scopes, CHECKOUT)) return false;
        if (editing) {
            boolean unchanged =
                    page.rows().stream()
                                    .anyMatch(n -> n.id().equals("continue_btn") && !n.enabled())
                            && page.rows().stream()
                                    .anyMatch(n -> n.id().equals("no_changes_btn") && n.enabled());
            return unchanged
                    && (id.equals("no_changes_btn")
                            || Collections.disjoint(page.ids(), CALENDAR)
                                    && (EDIT_DATE_IDS.contains(id) || EDIT_DATE.contains(label)));
        }
        if (scopes.contains(RECEIPT)
                && !Collections.disjoint(page.labels(), CONFIRMED)
                && MANAGE.contains(label)) return true;
        if (!Collections.disjoint(page.labels(), MANAGE)
                && !Collections.disjoint(page.labels(), CANCEL_LABELS)
                && MODIFY.contains(label)) return true;
        if (!safePage || Collections.disjoint(scopes, SAFE_PAGE)) return false;
        if (NAV_TAP.contains(id) || NAV_LABELS.contains(label)) return true;
        if (!Collections.disjoint(scopes, CALENDAR))
            return label.matches("[1-9]|[12][0-9]|3[01]")
                    || Set.of("previous month", "next month", "上个月", "下个月").contains(label);
        if (!Collections.disjoint(scopes, PARTY))
            return label.matches("[1-9][0-9]?(?: people| guests?|人)?");
        return (scopes.contains("rest_profile_root") || scopes.contains("full_availability_root"))
                && label.matches("(?:[01]?[0-9]|2[0-3]):[0-5][0-9](?: ?[ap]m)?");
    }

    static boolean rejectsTap(String id, String label) {
        return FINAL_LABELS.contains(label)
                || CANCEL_LABELS.contains(label)
                || CHECKOUT.contains(id)
                || id.equals("continue_btn")
                || DiningBookingCheckout.requiresReview("", label, "");
    }

    private static Page snapshot(AccessibilityNodeInfo root) throws IOException {
        List<Row> rows = new ArrayList<>();
        collect(root, root.getWindowId(), Set.of(), rows, 0);
        Set<String> ids = new HashSet<>(), labels = new HashSet<>();
        for (Row row : rows) {
            ids.add(row.id());
            labels.add(row.label());
        }
        return new Page(root, rows, ids, labels);
    }

    private static void collect(
            AccessibilityNodeInfo node, int window, Set<String> parents, List<Row> rows, int depth)
            throws IOException {
        if (node == null || !node.isVisibleToUser()) return;
        if (depth > 40 || rows.size() >= 2_000) throw denied();
        Set<String> scopes = new HashSet<>(parents);
        String id = id(node);
        if (!id.isEmpty()) scopes.add(id);
        Set<String> frozen = Set.copyOf(scopes);
        rows.add(row(node, frozen, window));
        for (int i = 0; i < node.getChildCount(); i++)
            collect(node.getChild(i), window, frozen, rows, depth + 1);
    }

    private static Row row(AccessibilityNodeInfo node, Set<String> scopes, int window)
            throws IOException {
        if (!node.isVisibleToUser()
                || node.getWindowId() != window
                || !PACKAGE.contentEquals(node.getPackageName())) throw denied();
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String label = DiningPollUi.value(node.getText());
        if (label.isEmpty()) label = DiningPollUi.value(node.getContentDescription());
        Set<Integer> actions = new HashSet<>();
        for (var action : node.getActionList()) {
            if (action.getId() != CLICK || node.isClickable()) actions.add(action.getId());
        }
        return new Row(
                node,
                id(node),
                label.toLowerCase(Locale.ROOT),
                scopes,
                bounds,
                node.isEnabled(),
                node.isEditable(),
                node.isPassword(),
                node.isFocused(),
                Set.copyOf(actions));
    }

    private static String id(AccessibilityNodeInfo node) {
        String id = node.getViewIdResourceName();
        return id != null && id.startsWith(RESOURCE) ? id.substring(RESOURCE.length()) : "";
    }

    private static int coordinate(JSONObject input, String field, int limit) throws IOException {
        Object value = input.opt(field);
        if (!(value instanceof Integer number) || number < 0 || number >= limit) throw denied();
        return number;
    }

    private static IOException denied() {
        return new IOException("OPENTABLE_NAVIGATION_NOT_VERIFIED");
    }
}
