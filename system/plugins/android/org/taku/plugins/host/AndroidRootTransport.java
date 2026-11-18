package org.taku.plugins.host;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.taku.plugins.RootDaemonBridge;

public final class AndroidRootTransport implements RootDaemonBridge.Transport {
    public String request(String operation, String payload) throws Exception {
        JSONObject data = new JSONObject(payload).put("operation", operation);
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("taku-godmode", LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(45000);
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output.write(data.toString()); output.newLine(); output.flush();
            String line = input.readLine(); if (line == null) throw new IOException("service closed connection");
            return line;
        }
    }
}
