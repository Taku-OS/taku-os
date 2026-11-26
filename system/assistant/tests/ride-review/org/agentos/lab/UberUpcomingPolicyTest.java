package org.agentos.lab;

import java.util.List;
import org.json.JSONObject;

public final class UberUpcomingPolicyTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static UberUpcomingPolicy.Label text(String value) { return new UberUpcomingPolicy.Label(value, false); }
    private static UberUpcomingPolicy.Label scope(String value) { return new UberUpcomingPolicy.Label(value, true); }
    private static String status(List<UberUpcomingPolicy.Label> rows, boolean loading, boolean editing) throws Exception {
        return UberUpcomingPolicy.classify(rows, loading, editing, 17).getString("status");
    }
    public static void main(String[] args) throws Exception {
        check("unknown".equals(status(List.of(scope("过去行程"), text("你近期沒有任何活動")), false, false)), "The actual QA past-empty page never attests Upcoming absence");
        check("unknown".equals(status(List.of(text("Upcoming"), text("No upcoming rides")), false, false)), "An unselected navigation label does not identify the current scope");
        check("unknown".equals(status(List.of(scope("Activity"), text("No recent activity")), false, false)), "Generic Activity empty is not an Upcoming result");
        check("unknown".equals(status(List.of(scope("Past trips"), scope("Upcoming"), text("No upcoming rides")), false, false)), "Conflicting current scopes are rejected");
        check("unknown".equals(status(List.of(scope("Upcoming"), text("No upcoming rides")), true, false)), "A loading view is not evidence of absence");
        check("unknown".equals(status(List.of(scope("Upcoming"), text("No upcoming rides")), false, true)), "An editor cannot establish order history");
        check("review_required".equals(status(List.of(scope("Upcoming"), text("Allora · Nov 29 · 18:45")), false, false)), "Visible reservations require actual review");
        check("review_required".equals(status(List.of(scope("Upcoming")), false, false)), "Lack of an empty label does not mean no rides");
        JSONObject empty = UberUpcomingPolicy.classify(List.of(scope("Upcoming rides"), text("You have no upcoming rides")), false, false, 17);
        check("upcoming_empty".equals(empty.getString("status")), "Only explicit loaded Upcoming empty can proceed under the task policy");
        check(empty.getInt("windowId") == 17 && "native-visible-upcoming".equals(empty.getString("scope")), "Evidence names the observed native scope");
        check("not-attested".equals(empty.getString("orderStatus")), "Visible empty is scoped evidence, never a global service audit");
        check("upcoming_empty".equals(status(List.of(scope("已预约行程"), text("暂无预约行程")), false, false)), "Equivalent explicit Chinese native scope is recognized");
        check("unknown".equals(UberUpcomingPolicy.unknown("native-view-unavailable").getString("status")), "Unavailable accessibility never becomes empty");
        System.out.println("UberUpcomingPolicyTest: " + checks + " checks passed");
    }
}
