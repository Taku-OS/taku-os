package org.taku.go2lab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Fixed phone loopback API, independent of VPN HTTP proxies. */
final class AppApi {
  static JSONObject get(String path) throws Exception { return call(path, null); }
  static JSONObject post(String path, JSONObject value) throws Exception { return call(path, value); }
  private static JSONObject call(String path, JSONObject value) throws Exception {
    HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:18845/api/" + path).openConnection(Proxy.NO_PROXY);
    c.setConnectTimeout(1500); c.setReadTimeout(18000); c.setInstanceFollowRedirects(false);
    try {
      if (value != null) {
        byte[] body = value.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > 4096) throw new IOException("INVALID_REQUEST");
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
      }
      int status = c.getResponseCode();
      try (InputStream in = status == 200 ? c.getInputStream() : c.getErrorStream()) {
        if (in == null) throw new IOException("SERVICE_UNAVAILABLE");
        byte[] data = in.readNBytes(65537);
        if (data.length > 65536) throw new IOException("RESPONSE_TOO_LARGE");
        JSONObject result = new JSONObject(new String(data, StandardCharsets.UTF_8));
        if (status != 200) throw new IOException(result.optString("error", "SERVICE_UNAVAILABLE"));
        return result;
      }
    } finally { c.disconnect(); }
  }
}
