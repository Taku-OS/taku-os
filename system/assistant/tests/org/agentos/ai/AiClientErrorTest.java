package org.agentos.ai;

import java.io.IOException;
import org.json.JSONObject;

public final class AiClientErrorTest {
    public static void main(String[] args) throws Exception {
        checkRateLimit(new JSONObject().put("code", "rate_limited").put("message", "稍后重试"), true);
        checkRateLimit(new JSONObject().put("message", "This app has made too many requests. Try again later"), true);
        checkRateLimit(new JSONObject().put("code", "quota").put("message", "Busy right now. Please try again later"), false);
        checkRateLimit(new JSONObject().put("message", "Unknown system AI operation"), false);
        System.out.println("AI client error tests passed");
    }

    private static void checkRateLimit(JSONObject response, boolean limited) throws Exception {
        try {
            AiClient.result(response);
            throw new AssertionError("Expected a failed AI response");
        } catch (IOException failure) {
            if ((failure instanceof AiClient.RateLimited) != limited
                    || !response.getString("message").equals(failure.getMessage())) {
                throw new AssertionError(failure);
            }
        }
    }
}
