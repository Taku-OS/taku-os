package org.agentos.lab;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Calls the broker's real bounded parent entry point; production jar must come first on CLASSPATH. */
public final class PhoneTextInputAcceptanceProbe {
    public static void main(String[] args) {
        try {
            byte[] bytes = System.in.readNBytes(32769);
            if (bytes.length > 32768) throw new IllegalArgumentException("Input limit");
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            PhoneTextInput.enter(request.getInt("displayId"), request.getString("text"));
            System.out.println("OK");
            System.exit(0);
        } catch (Exception error) {
            System.out.println("ERROR: " + error.getMessage());
            System.exit(1);
        }
    }
}
