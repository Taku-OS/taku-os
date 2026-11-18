package org.agentos.lab;

import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.Executors;

/** Production adapter over real loopback HTTP. The upstream and all credentials are synthetic. */
public final class JevMagpieTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void reject(Checked action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); } catch (AssertionError failure) { throw failure; }
        catch (Exception expected) { checks++; }
    }
    interface Checked { void run() throws Exception; }
    static JSONObject question() throws Exception {
        return new JSONObject().put("suggest", new JSONObject().put("type", "choice")
                .put("criteria", new JSONObject().put("show", "Useful").put("wait", "Unclear")));
    }
    static JSONObject call(String model, Object state) throws Exception {
        return new JSONObject().put("action", "decide").put("authorized", true)
                .put("model", model).put("state", state).put("questions", question());
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("taku-magpie-decisions-");
        Path store = root.resolve("config/magpie/providers.json"); Files.createDirectories(store.getParent());
        String secret = "sk-or-v1-0123456789abcdefghijklmnopqr", web = "a".repeat(64);
        Files.writeString(root.resolve("web.key"), web);
        JSONObject original = new JSONObject().put("providers", new JSONArray().put(
                new JSONObject().put("id", "existing").put("preset", "deepseek").put("key", "existing-fixture")))
                .put("groups", new JSONArray().put(new JSONObject().put("id", "keep-me")));
        Files.writeString(store, original.toString());
        AtomicInteger posts = new AtomicInteger(), gets = new AtomicInteger(), imports = new AtomicInteger();
        AtomicReference<String> mode = new AtomicReference<>("ok"), forwarded = new AtomicReference<>("");
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath(), answer;
                int status = 200;
                if (path.equals("/v1/models")) {
                    gets.incrementAndGet();
                    check("1".equals(exchange.getRequestHeaders().getFirst("X-Magpie-Deciders")), "decision header missing");
                    check("Bearer taku-android".equals(exchange.getRequestHeaders().getFirst("Authorization")), "wrong local identity");
                    answer = new JSONObject().put("data", new JSONArray()
                            .put(new JSONObject().put("id", "chat/jev-router"))
                            .put(new JSONObject().put("id", "fixture/jev").put("kind", "decision").put("magpie_label", "Jev fixture")
                                    .put("modalities", new JSONObject().put("input", new JSONArray().put("text"))))
                            .put(new JSONObject().put("id", "fixture/vision").put("kind", "decision")
                                    .put("modalities", new JSONObject().put("input", new JSONArray().put("text").put("image"))))
                            .put(new JSONObject().put("id", "fixture/typesafe/jev-1.14").put("kind", "decision"))
                            .put(new JSONObject().put("id", "fixture/unverified").put("kind", "decision"))).toString();
                } else if (path.equals("/api/providers")) {
                    check(("magpie_web_" + server.getAddress().getPort() + "=" + web)
                            .equals(exchange.getRequestHeaders().getFirst("Cookie")), "provider management cookie missing");
                    if (mode.get().equals("management-down")) { status = 503; answer = "{}"; }
                    else answer = new JSONObject().put("providers", new JSONArray().put(new JSONObject().put("id", "fixture")
                            .put("deciders", new JSONArray().put("typesafe/jev-1.14"))
                            .put("models", new JSONArray().put(new JSONObject().put("id", "typesafe/jev-1.14").put("images", true))
                                    .put(new JSONObject().put("id", "unverified").put("images", true))))).toString();
                } else if (path.equals("/api/groups")) {
                    check(("magpie_web_" + server.getAddress().getPort() + "=" + web)
                            .equals(exchange.getRequestHeaders().getFirst("Cookie")), "group management cookie missing");
                    if (mode.get().equals("management-down")) { status = 503; answer = "{}"; }
                    else answer = new JSONObject().put("groups", new JSONArray()
                            .put(new JSONObject().put("ref", "group/vision").put("name", "Vision group").put("decides", true).put("ready", true).put("members", new JSONArray().put("fixture/vision")))
                            .put(new JSONObject().put("ref", "group/mixed").put("decides", true).put("ready", true).put("members", new JSONArray().put("fixture/vision").put("fixture/jev")))
                            .put(new JSONObject().put("ref", "group/legacy").put("decides", true).put("ready", true).put("members", new JSONArray().put("fixture/typesafe/jev-1.14")))
                            .put(new JSONObject().put("ref", "group/unverified").put("decides", true).put("ready", true).put("members", new JSONArray().put("fixture/unverified")))
                            .put(new JSONObject().put("ref", "group/unknown").put("decides", true).put("ready", true).put("members", new JSONArray().put("missing/model")))
                            .put(new JSONObject().put("ref", "group/chat").put("ready", true).put("members", new JSONArray().put("chat/jev-router")))).toString();
                } else if (path.equals("/v1/systemone")) {
                    posts.incrementAndGet();
                    JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), "UTF-8"));
                    check(!body.has("key") && !body.has("api_key") && !body.has("authorization"), "provider key in decision");
                    forwarded.set(body.get("state").toString());
                    if (mode.get().equals("refused")) { status = 429; answer = secret; }
                    else if (mode.get().equals("invalid")) answer = "{\"answers\":{}}";
                    else answer = "{\"answers\":{\"suggest\":{\"type\":\"choice\",\"choice\":\"show\",\"confidence\":0.9,\"probabilities\":{\"show\":0.9,\"wait\":0.1}}}}";
                } else if (path.equals("/api/provider/save")) {
                    imports.incrementAndGet();
                    check(("magpie_web_" + server.getAddress().getPort() + "=" + web)
                            .equals(exchange.getRequestHeaders().getFirst("Cookie")), "admin cookie missing");
                    JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), "UTF-8"));
                    check(body.getBoolean("new") && secret.equals(body.getString("key")), "legacy import changed key or updates existing provider");
                    if (!mode.get().equals("not-persisted")) {
                        JSONObject stored = new JSONObject(Files.readString(store));
                        stored.getJSONArray("providers").put(body);
                        Files.writeString(store, stored.toString());
                    }
                    answer = "{}";
                } else { status = 404; answer = "{}"; }
                byte[] bytes = answer.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable error) { serverFailure.set(error); }
            finally { exchange.close(); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        MagpieDecisions gateway = new MagpieDecisions(url, url, root.toFile());
        try {
            JSONArray models = gateway.models();
            check(models.length() == 8, "incorrect decision catalog or groups");
            check(!models.getJSONObject(0).getBoolean("imageInput"), "text capability lost");
            check(models.getJSONObject(1).getBoolean("imageInput"), "image capability lost");
            check(MagpieDecisions.find(models, "group/vision").getBoolean("imageInput"), "vision group capability lost");
            check(MagpieDecisions.find(models, "group/vision").getBoolean("textInput"), "decision group text capability lost");
            check(!MagpieDecisions.find(models, "group/mixed").getBoolean("imageInput"), "group fallback must be image capable");
            check(MagpieDecisions.find(models, "group/mixed").getBoolean("textInput"), "decision group must retain common text capability");
            check(MagpieDecisions.find(models, "group/legacy").getBoolean("imageInput"), "management image capability lost");
            JSONObject unverified = MagpieDecisions.find(models, "group/unverified");
            check(unverified.getBoolean("textInput"), "text-only legacy route disappeared");
            check(!unverified.getBoolean("imageInput") && !unverified.getBoolean("inputKnown"), "unknown image support was invented");
            reject(() -> MagpieDecisions.find(models, "group/unknown"), "unknown group member accepted");
            mode.set("management-down");check(gateway.models().length() == 4, "direct models unavailable without management");mode.set("ok");
            int before = gets.get();
            reject(() -> gateway.control(call("fixture/jev", "meeting").put("authorized", false)), "unauthorized accepted");
            check(before == gets.get() && posts.get() == 0, "unauthorized call accessed gateway");
            reject(() -> gateway.control(call("missing/jev", "meeting")), "missing model accepted");
            check(posts.get() == 0, "unknown model sent upstream");
            JSONObject text = new JSONObject().put("text", "周六下午六点在西湖见");
            gateway.control(call("fixture/jev", text));
            check(forwarded.get().equals(text.toString()), "Chinese object state changed");
            gateway.control(call("group/unverified", text));
            check(forwarded.get().equals(text.toString()), "legacy route did not forward text");
            JSONArray image = new JSONArray().put("合成页面").put(new JSONObject().put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", "data:image/png;base64,fixture")));
            int previous = posts.get();
            reject(() -> gateway.control(call("fixture/jev", image)), "text model received image");
            reject(() -> gateway.control(call("group/unverified", image)), "unknown-image route received image");
            check(previous == posts.get(), "unsupported image sent upstream");
            gateway.control(call("fixture/vision", image));
            check(forwarded.get().equals(image.toString()), "image state changed");
            gateway.control(call("group/vision", image));
            reject(() -> gateway.control(call("group/mixed", image)), "mixed group received image");
            mode.set("invalid"); reject(() -> gateway.control(call("fixture/jev", text)), "invalid answer accepted");
            mode.set("refused");
            try { gateway.control(call("fixture/jev", text)); throw new AssertionError("429 accepted"); }
            catch (IOException expected) { check(!expected.getMessage().contains(secret) && expected.getMessage().contains("429"), "vendor error leaked"); }
            mode.set("not-persisted");
            reject(() -> gateway.control(new JSONObject().put("action", "importLegacy").put("key", secret)), "unpersisted credential accepted");
            check(Files.readString(store).equals(original.toString()), "failed migration changed existing config");
            mode.set("ok");
            JSONObject imported = gateway.control(new JSONObject().put("action", "importLegacy").put("key", secret));
            check(imported.getBoolean("imported") && !imported.toString().contains(secret), "import leaks credential");
            int added = imports.get();
            gateway.control(new JSONObject().put("action", "importLegacy").put("key", secret));
            check(imports.get() == added, "retry duplicated provider");
            JSONObject stored = new JSONObject(Files.readString(store));
            check(stored.getJSONArray("providers").length() == 2, "existing provider replaced");
            check(stored.getJSONArray("groups").toString().equals(original.getJSONArray("groups").toString()), "groups changed");
            if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
            System.out.println("PASS JevMagpieTest: " + checks + " checks; real HTTP, synthetic gateway and credentials");
        } finally {
            server.stop(0);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
