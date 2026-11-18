package org.agentos.lab;

import org.json.JSONObject;

/** Offline protocol tests; the fake transport cannot make a network request. */
public final class JevDecisionClientTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static JSONObject question() throws Exception {
        return new JSONObject().put("suggest", new JSONObject().put("type", "choice")
                .put("criteria", new JSONObject().put("show", "Useful").put("wait", "Unclear")));
    }
    private static JSONObject answer(String choice) throws Exception {
        return new JSONObject().put("answers", new JSONObject().put("suggest", new JSONObject()
                .put("type", "choice").put("choice", choice).put("confidence", .91)
                .put("probabilities", new JSONObject().put("show", .91).put("wait", .09))));
    }
    public static void main(String[] args) throws Exception {
        final boolean[] called = {false};
        JevDecisionClient client = new JevDecisionClient(request -> {
            called[0] = true;
            check("fixture/jev".equals(request.getString("model")), "catalog model lost");
            check("meeting".equals(request.getJSONObject("state").getString("text")), "object state lost");
            check(!request.has("key") && !request.has("authorization"), "provider secret in decision");
            return answer("show");
        });
        JSONObject state = new JSONObject().put("text", "meeting");
        client.decide("fixture/jev", state, question(), true);
        called[0] = false;
        try { client.decide("fixture/jev", state, question(), false); throw new AssertionError("unauthorized"); }
        catch (SecurityException expected) { check(!called[0], "called before authorization"); }
        try { client.decide("", state, question(), true); throw new AssertionError("empty model"); }
        catch (IllegalArgumentException expected) { check(!called[0], "called with empty model"); }
        try { new JevDecisionClient(q -> answer("unknown")).decide("fixture/jev", state, question(), true);
            throw new AssertionError("unknown choice"); }
        catch (IllegalStateException expected) {}
        try { new JevDecisionClient(q -> answer("show").put("answers", new JSONObject()))
                .decide("fixture/jev", state, question(), true); throw new AssertionError("missing answer"); }
        catch (IllegalStateException expected) {}
        System.out.println("PASS JevDecisionClientTest: catalog model, object state, auth and typed answers");
    }
}
