package org.agentos.assistant;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evidence that the declared Feed action actually launched its exact app and target. */
final class DiningFeedDispatch {
    private static final Pattern CURSOR =
            Pattern.compile(
                    "([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}):(0|[1-9][0-9]{0,18})");

    private DiningFeedDispatch() {}

    static JSONObject intent(
            JSONObject event,
            String cardId,
            String actionId,
            String type,
            long notBefore,
            long now) {
        Object at = event.opt("at");
        if (!(at instanceof Long || at instanceof Integer)) return null;
        long time = ((Number) at).longValue();
        if (time < notBefore
                || time > now + 5000
                || !"event".equals(event.optString("type"))
                || !"taku.feed-event/1".equals(event.optString("schema"))
                || !"action".equals(event.optString("op"))
                || !"dispatched".equals(event.optString("phase"))
                || !"declared".equals(event.optString("actionSource"))
                || !type.equals(event.optString("intentType"))
                || !actionId.equals(event.optString("actionId"))
                || !cardId.equals(event.optString("cardId"))
                || !ordered(event.optString("acceptedCursor"), event.optString("cursor")))
            return null;
        return event.optJSONObject("intent");
    }

    private static boolean ordered(String accepted, String dispatched) {
        Matcher before = CURSOR.matcher(accepted);
        Matcher after = CURSOR.matcher(dispatched);
        if (!before.matches() || !after.matches() || !before.group(1).equals(after.group(1)))
            return false;
        try {
            return Long.parseLong(before.group(2)) < Long.parseLong(after.group(2));
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
