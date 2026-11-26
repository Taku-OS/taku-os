package org.agentos.lab;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/** Only an explicit loaded Upcoming scope plus its explicit empty state attests visible absence. */
final class UberUpcomingPolicy {
    record Label(String text, boolean scoped) {}
    private static final Set<String> UPCOMING = Set.of("upcoming", "upcoming trips", "upcoming rides",
            "your reservations", "reserved rides", "已预约行程", "已預約行程", "预约行程", "預約行程",
            "即将到来的行程", "即將到來的行程", "即將搭乘的行程");
    private static final Set<String> PAST = Set.of("past", "past trips", "past rides", "过去行程", "過往行程", "過去行程");
    private static final Set<String> EMPTY = Set.of("no upcoming trips", "no upcoming rides",
            "you have no upcoming trips", "you have no upcoming rides", "you don't have any upcoming trips",
            "you don’t have any upcoming trips", "暂无预约行程", "暫無預約行程", "没有即将到来的行程",
            "沒有即將到來的行程", "你尚未预约行程", "你尚未預約行程");

    static JSONObject classify(List<Label> labels, boolean loading, boolean editable, int windowId) throws Exception {
        String heading = "", empty = "";
        for (Label label : labels) {
            String text = normalize(label.text());
            if (label.scoped() && PAST.contains(text)) return result("unknown", "past-trips-scope", "", "", windowId);
            if (label.scoped() && UPCOMING.contains(text)) heading = label.text();
            if (EMPTY.contains(text)) empty = label.text();
        }
        if (loading || editable) return result("unknown", loading ? "view-loading" : "editing-view", heading, "", windowId);
        if (heading.isEmpty()) return result("unknown", "upcoming-scope-unverified", "", "", windowId);
        if (empty.isEmpty()) return result("review_required", "upcoming-not-explicitly-empty", heading, "", windowId);
        return result("upcoming_empty", "explicit-loaded-upcoming-empty", heading, empty, windowId);
    }

    static JSONObject unknown(String reason) throws Exception { return result("unknown", reason, "", "", -1); }

    private static JSONObject result(String status, String reason, String heading, String empty, int window) throws Exception {
        return new JSONObject().put("status", status).put("reason", reason).put("heading", heading)
                .put("emptyState", empty).put("windowId", window).put("scope", "native-visible-upcoming")
                .put("packageName", "com.ubercab").put("orderStatus", "not-attested");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("[\\p{Z}\\s]+", " ")
                .strip().toLowerCase(Locale.ROOT);
    }
}
