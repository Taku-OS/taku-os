package org.agentos.tests;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/** Prints only non-sensitive flags. This program cannot export challenge codes or URLs. */
public final class AccountBridgeProbe {
    public static void main(String[] args) {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("taku-agent-account"));
            if (socket.getPeerCredentials().getUid() != 0) throw new Exception();
            socket.setSoTimeout(3000);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            int n;
            while ((n = socket.getInputStream().read(b)) != -1) {
                bytes.write(b, 0, n);
                if (bytes.size() > 32768) throw new Exception();
            }
            JSONObject account = new JSONObject(bytes.toString("UTF-8"));
            System.out.println(
                    new JSONObject()
                            .put("serverUid", socket.getPeerCredentials().getUid())
                            .put("connected", account.optBoolean("connected"))
                            .put("phase", account.optString("phase"))
                            .put("challengePresent", !account.optString("url").isEmpty())
                            .toString());
        } catch (Exception denied) {
            System.out.println("Account endpoint unavailable or access denied.");
            System.exit(1);
        }
    }
}
