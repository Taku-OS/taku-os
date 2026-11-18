package org.agentos.environment.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.agentos.environment.CountryObservation;
import org.agentos.environment.NetworkCountryObserver;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** One main-looper observer shared by all MCP clients of this provider process. */
public final class CountryProvider implements AutoCloseable {
    private static final int HISTORY_LIMIT = 64;
    private static final int SUBSCRIPTION_LIMIT = 64;
    private static final long TTL_MILLIS = 10 * 60_000;
    private final String generation = UUID.randomUUID().toString();
    private final ArrayDeque<JSONObject> history = new ArrayDeque<>();
    private final Map<String, Subscription> subscriptions = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PrintWriter output = new PrintWriter(System.out, false, StandardCharsets.UTF_8);
    private final NetworkCountryObserver observer;
    private final NetworkCountryObserver.Subscription observationSubscription;
    private JSONObject latest;
    private long sequence;
    private boolean closed;

    private CountryProvider(Context context) {
        observer = new NetworkCountryObserver(context);
        observationSubscription = observer.subscribe(this::sampled);
    }

    public static void main(String[] args) {
        CountryProvider provider = null;
        int exitCode = 0;
        try {
            if (args.length != 0) throw new IllegalArgumentException("CountryProvider takes no arguments");
            provider = new CountryProvider(initializeFrameworkContext());
            CountryProvider running = provider;
            Thread reader = new Thread(running::readRequests, "country-mcp-stdin");
            reader.setDaemon(true);
            reader.start();
            Looper.loop();
        } catch (Exception error) {
            System.err.println("Country provider failed: " + error);
            exitCode = 1;
        } finally {
            if (provider != null) provider.close();
        }
        // ActivityThread starts Binder threads; EOF must terminate this app_process too.
        System.exit(exitCode);
    }

    private static Context initializeFrameworkContext() throws ReflectiveOperationException {
        Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        // app_process does not run the zygote initialization that installs the telephony service manager.
        Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
        if (initializer.getMethod("getTelephonyServiceManager").invoke(null) == null) {
            Class<?> manager = Class.forName("android.os.TelephonyServiceManager");
            initializer.getMethod("setTelephonyServiceManager", manager)
                    .invoke(null, manager.getConstructor().newInstance());
        }
        return (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
    }

    private void sampled(CountryObservation observation) {
        latest = json("countryIso", observation.countryIso(), "source", observation.source(),
                "sourceDescription", observation.sourceDescription(), "availability", observation.availability().name(),
                "reason", observation.reason().name(), "observedAtMillis", observation.observedAtMillis(),
                "observedElapsedRealtimeMillis", observation.observedElapsedRealtimeMillis(),
                "continuousSinceElapsedRealtimeMillis", observation.continuousSinceElapsedRealtimeMillis(),
                "stable", observation.stable(), "providerGeneration", generation, "sequence", ++sequence);
        history.addLast(latest);
        if (history.size() > HISTORY_LIMIT) history.removeFirst();
    }

    private void readRequests() {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isBlank()) continue;
                String request = line;
                if (!handler.post(() -> receive(request))) break;
            }
        } catch (IOException error) {
            System.err.println("Country provider stdin failed: " + error);
        } finally {
            handler.post(this::finish);
        }
    }

    private void receive(String line) {
        if (closed) return;
        Object parsed;
        try {
            JSONTokener tokens = new JSONTokener(line);
            parsed = tokens.nextValue();
            if (tokens.nextClean() != 0) throw new JSONException("Trailing JSON");
        } catch (JSONException error) {
            send(rpcError(JSONObject.NULL, -32700, "Invalid JSON"));
            return;
        }
        if (!(parsed instanceof JSONObject)) {
            send(rpcError(JSONObject.NULL, -32600, "Request must be an object"));
            return;
        }
        JSONObject request = (JSONObject) parsed;
        Object id = request.opt("id");
        boolean notification = !request.has("id");
        if (id == null) id = JSONObject.NULL;
        if (!"2.0".equals(request.opt("jsonrpc")) || !(request.opt("method") instanceof String)
                || !(id == JSONObject.NULL || id instanceof String || id instanceof Number)) {
            send(rpcError(JSONObject.NULL, -32600, "Invalid JSON-RPC request"));
            return;
        }
        String method = (String) request.opt("method");
        try {
            Object rawParams = request.has("params") ? request.opt("params") : new JSONObject();
            if (!(rawParams instanceof JSONObject)) throw new RpcError(-32602, "params must be an object");
            JSONObject result = dispatch(method, (JSONObject) rawParams);
            if (!notification) send(json("jsonrpc", "2.0", "id", id, "result", result));
        } catch (RpcError error) {
            if (!notification) send(rpcError(id, error.code, error.getMessage()));
        } catch (Exception error) {
            System.err.println("Country provider request failed: " + error);
            if (!notification) send(rpcError(id, -32603, "Internal provider error"));
        }
    }

    private JSONObject dispatch(String method, JSONObject params) throws RpcError {
        switch (method) {
            case "initialize":
                return json("protocolVersion", "2024-11-05", "capabilities", json("tools", json("listChanged", false)),
                        "serverInfo", json("name", "org.taku.environment.country", "version", "0.1.0"));
            case "ping":
            case "notifications/initialized":
                return new JSONObject();
            case "tools/list":
                return json("tools", tools());
            case "tools/call":
                try {
                    requireFields(params, Set.of("name", "arguments"));
                    Object name = params.opt("name");
                    if (!(name instanceof String) || ((String) name).isBlank()) {
                        throw new ToolError("INVALID_ARGUMENTS", "name must be a nonempty string");
                    }
                    Object args = params.has("arguments") ? params.opt("arguments") : new JSONObject();
                    if (!(args instanceof JSONObject)) throw new ToolError("INVALID_ARGUMENTS", "arguments must be an object");
                    return toolResult(call((String) name, (JSONObject) args), false);
                } catch (ToolError error) {
                    return toolResult(json("error", json("code", error.code, "message", error.getMessage())), true);
                }
            default:
                throw new RpcError(-32601, "Unknown method: " + method);
        }
    }

    private JSONObject call(String name, JSONObject args) throws ToolError {
        switch (name) {
            case "country_read": {
                requireFields(args, Set.of("refresh"));
                Object refresh = args.has("refresh") ? args.opt("refresh") : Boolean.FALSE;
                if (!(refresh instanceof Boolean)) throw new ToolError("INVALID_ARGUMENTS", "refresh must be a boolean");
                if ((Boolean) refresh) observer.refresh();
                return latest;
            }
            case "country_subscribe": {
                requireFields(args, Set.of());
                long now = SystemClock.elapsedRealtime();
                subscriptions.values().removeIf(item -> expired(item, now));
                if (subscriptions.size() >= SUBSCRIPTION_LIMIT) {
                    throw new ToolError("SUBSCRIPTION_CAPACITY", "At most 64 active subscriptions are allowed");
                }
                String id = generation + ":" + UUID.randomUUID();
                subscriptions.put(id, new Subscription(sequence, now));
                return json("subscriptionId", id, "snapshot", latest, "ttlMs", TTL_MILLIS);
            }
            case "country_poll": {
                String id = subscriptionId(args);
                Subscription subscription = subscriptions.get(id);
                long now = SystemClock.elapsedRealtime();
                if (subscription == null || expired(subscription, now)) {
                    subscriptions.remove(id);
                    throw new ToolError("SUBSCRIPTION_INVALID", "Unknown, expired, or previous-generation subscription; subscribe again");
                }
                long oldest = history.getFirst().optLong("sequence");
                long dropped = Math.max(0, oldest - subscription.cursor - 1);
                JSONArray events = new JSONArray();
                for (JSONObject sample : history) if (sample.optLong("sequence") > subscription.cursor) events.put(sample);
                subscription.cursor = sequence;
                subscription.lastPoll = now;
                return json("subscriptionId", id, "providerGeneration", generation, "sequence", sequence,
                        "events", events, "dropped", dropped, "ttlMs", TTL_MILLIS);
            }
            case "country_unsubscribe": {
                String id = subscriptionId(args);
                Subscription subscription = subscriptions.remove(id);
                boolean removed = subscription != null && !expired(subscription, SystemClock.elapsedRealtime());
                return json("subscriptionId", id, "removed", removed);
            }
            default:
                throw new ToolError("UNKNOWN_TOOL", "Unknown tool: " + name);
        }
    }

    private static boolean expired(Subscription subscription, long now) {
        return now - subscription.lastPoll >= TTL_MILLIS;
    }

    private static String subscriptionId(JSONObject args) throws ToolError {
        requireFields(args, Set.of("subscriptionId"));
        Object id = args.opt("subscriptionId");
        if (!(id instanceof String) || ((String) id).isBlank()) {
            throw new ToolError("INVALID_ARGUMENTS", "subscriptionId must be a nonempty string");
        }
        return (String) id;
    }

    private static void requireFields(JSONObject value, Set<String> allowed) throws ToolError {
        for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String field = keys.next();
            if (!allowed.contains(field)) throw new ToolError("INVALID_ARGUMENTS", "Unknown field: " + field);
        }
    }

    private static JSONArray tools() {
        JSONObject empty = schema(new JSONObject(), new JSONArray());
        JSONObject subscription = schema(json("subscriptionId", json("type", "string", "minLength", 1, "pattern", "\\S")),
                new JSONArray().put("subscriptionId"));
        return new JSONArray()
                .put(tool("country_read", "Read the last cellular network country snapshot; refresh=true reads TelephonyManager now.",
                        schema(json("refresh", json("type", "boolean", "default", false)), new JSONArray())))
                .put(tool("country_subscribe", "Create a poll cursor and return the current snapshot. Subscription expires after 10 minutes without polling.", empty))
                .put(tool("country_poll", "Read new samples without waiting, with independent cursor and accurate dropped count. Renews subscription TTL.", subscription))
                .put(tool("country_unsubscribe", "Remove only this subscription. Repeated removal returns removed=false.", subscription));
    }

    private static JSONObject schema(JSONObject properties, JSONArray required) {
        return json("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static JSONObject tool(String name, String description, JSONObject schema) {
        return json("name", name, "description", description, "toolset", "environment.country", "inputSchema", schema,
                "capabilities", new JSONArray().put("environment.country"), "effects", new JSONArray(), "requirements", new JSONArray());
    }

    private static JSONObject toolResult(JSONObject value, boolean error) {
        return json("content", new JSONArray().put(json("type", "text", "text", value.toString())),
                "structuredContent", value, "isError", error);
    }

    private static JSONObject rpcError(Object id, int code, String message) {
        return json("jsonrpc", "2.0", "id", id, "error", json("code", code, "message", message));
    }

    private void send(JSONObject response) {
        output.println(response.toString());
        output.flush();
        if (output.checkError()) finish();
    }

    private static JSONObject json(Object... fields) {
        JSONObject value = new JSONObject();
        try {
            for (int index = 0; index < fields.length; index += 2) value.put((String) fields[index], fields[index + 1]);
        } catch (JSONException error) {
            throw new IllegalArgumentException(error);
        }
        return value;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        observationSubscription.close();
        observer.close();
        subscriptions.clear();
        history.clear();
    }

    private void finish() {
        close();
        // Android's main Looper cannot quit; process exit also stops its Binder threads.
        System.exit(0);
    }

    private static final class Subscription {
        long cursor;
        long lastPoll;
        Subscription(long cursor, long lastPoll) { this.cursor = cursor; this.lastPoll = lastPoll; }
    }

    private static final class ToolError extends Exception {
        final String code;
        ToolError(String code, String message) { super(message); this.code = code; }
    }

    private static final class RpcError extends Exception {
        final int code;
        RpcError(int code, String message) { super(message); this.code = code; }
    }
}
