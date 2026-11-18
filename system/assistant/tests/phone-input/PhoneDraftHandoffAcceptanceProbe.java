package org.agentos.lab;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Only the test entry point is packaged: PhoneDraftHandoff must resolve from the production jar. */
public final class PhoneDraftHandoffAcceptanceProbe {
    public static void main(String[] args) {
        try {
            byte[] bytes = System.in.readNBytes(32769);
            if (bytes.length > 32768) throw new IllegalArgumentException("INPUT_LIMIT");
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONObject result = PhoneDraftHandoff.prepare(request.getString("packageName"),
                    request.getString("conversationTitle"), request.getString("text"));
            System.out.println(result.toString());
            System.exit(0);
        } catch (Exception error) {
            String code = error.getMessage();
            System.out.println("ERROR: " + (code != null && code.matches("[A-Z_]+") ? code : "PROBE_FAILED"));
            System.exit(1);
        }
    }
}
