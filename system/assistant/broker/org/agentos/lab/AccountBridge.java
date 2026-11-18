package org.agentos.lab;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.nio.charset.StandardCharsets;

/** Read-only RAM snapshot endpoint. No task queue, model calls, or credential file access. */
final class AccountBridge {
    static void start() throws Exception {
        LocalServerSocket server = new LocalServerSocket("taku-agent-account");
        Thread reader =
                new Thread(
                        () -> {
                            for (; ; ) {
                                try (LocalSocket client = server.accept()) {
                                    int uid = client.getPeerCredentials().getUid();
                                    if (uid != 0 && uid != 1000) continue;
                                    client.setSoTimeout(2000);
                                    client.getOutputStream()
                                            .write(
                                                    AgentAccount.snapshot()
                                                            .getBytes(StandardCharsets.UTF_8));
                                } catch (Exception ignored) {
                                    // Do not log account payloads, one-time codes, or authorization
                                    // URLs.
                                }
                            }
                        },
                        "taku-account-view");
        reader.setDaemon(true);
        reader.start();
    }
}
