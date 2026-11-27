package org.agentos.lab;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Yelp's public search App Link, scoped to a held App on an independent display. */
final class PhoneYelpSearchRoute {
    static final String PACKAGE = "com.yelp.android";
    static final int MAX_FIELD_LENGTH = 200;
    private static final long COMMAND_TIMEOUT_MS = 4_000;
    private static final String ACTIVITY = ".search.ui.urlcatcher.ActivitySearchUrlCatcher";
    private static final String COMPONENT = PACKAGE + "/" + ACTIVITY;
    private static final String FULL_COMPONENT = PACKAGE + "/" + PACKAGE + ACTIVITY;
    private static final String VIEW = "android.intent.action.VIEW";
    private static final String BROWSABLE = "android.intent.category.BROWSABLE";

    private PhoneYelpSearchRoute() {}

    static String uri(JSONObject arguments) throws IOException {
        if (arguments.length() != 2) throw new IOException("YELP_SEARCH_ARGUMENTS_INVALID");
        return "https://www.yelp.com/search?find_desc=" + encodedField(arguments, "query")
                + "&find_loc=" + encodedField(arguments, "location");
    }

    private static String encodedField(JSONObject arguments, String name) throws IOException {
        if (!(arguments.opt(name) instanceof String value) || value.isBlank()
                || value.length() > MAX_FIELD_LENGTH || value.codePoints().anyMatch(codePoint ->
                        Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.SURROGATE))
            throw new IOException("YELP_SEARCH_ARGUMENTS_INVALID");
        return URLEncoder.encode(value.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    // The caller holds the execution screen's input lock and Yelp has been reserved for the thread.
    static JSONObject open(JSONObject arguments, PhoneExecutionScreens.Screen screen, JSONObject call) throws Exception {
        String uri = uri(arguments);
        if (screen.legacy || screen.displayId <= 0) throw new IOException("YELP_SEARCH_SCREEN_UNAVAILABLE");
        requireCurrentTurn(call);
        JSONObject app = PhoneAppCatalog.requireLaunchable(PACKAGE);
        if (app.getInt("userId") != screen.userId) throw new IOException("ANDROID_USER_CHANGED");

        File output = new File(TaskBroker.ROOT, "yelp-search-output");
        String user = Integer.toString(screen.userId);
        String resolved;
        try {
            resolved = BoundedCommand.run(output, COMMAND_TIMEOUT_MS, "/system/bin/cmd", "package",
                    "resolve-activity", "--brief", "--user", user, "-a", VIEW, "-c", BROWSABLE,
                    "-d", uri, "-p", PACKAGE);
        } catch (InterruptedException stopped) {
            throw stopped;
        } catch (Exception unavailable) {
            throw new IOException("YELP_SEARCH_ROUTE_UNAVAILABLE");
        }
        String component = resolved.strip().lines().reduce((first, last) -> last).orElse("");
        if (!component.equals(COMPONENT) && !component.equals(FULL_COMPONENT))
            throw new IOException("YELP_SEARCH_ROUTE_UNAVAILABLE");

        WorkspacePolicy.requireAppAllowed(PACKAGE);
        if (!PhoneExecutionScreens.current(screen)) throw new IOException("SCREEN_GENERATION_CHANGED");
        requireCurrentTurn(call);
        DiningPollInput.requireAutomationAvailable();
        String started;
        try {
            var command = new java.util.ArrayList<>(java.util.List.of("/system/bin/am", "start",
                    "--user", user, "--display", Integer.toString(screen.displayId),
                    "-a", VIEW, "-c", BROWSABLE, "-d", uri));
            boolean alreadyOpen = PhoneTasks.DEVICE.list().stream()
                    .anyMatch(task -> task.displayId() == screen.displayId && PACKAGE.equals(task.packageName()));
            if (!alreadyOpen) command.add("--activity-multiple-task");
            command.add("-n");
            command.add(COMPONENT);
            requireCurrentTurn(call);
            started = BoundedCommand.run(output, COMMAND_TIMEOUT_MS, command.toArray(String[]::new));
        } catch (InterruptedException stopped) {
            throw stopped;
        } catch (Exception uncertain) {
            throw new IOException("YELP_SEARCH_DISPATCH_UNKNOWN");
        }
        if (!started.startsWith("Starting: Intent") || started.lines().anyMatch(line ->
                line.startsWith("Error") || line.startsWith("Exception") || line.startsWith("Security exception")))
            throw new IOException("YELP_SEARCH_DISPATCH_UNKNOWN");

        return screen.json().put("action", "open_yelp_search").put("packageName", PACKAGE)
                .put("actionExecuted", true).put("requiresObservation", true).put("resultVerified", false);
    }

    private static void requireCurrentTurn(JSONObject call) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try (CleanupWork.Held held = NativeBridge.lock()) {
            if (!NativeBridge.phoneTurnActive(call)) throw new IOException("PHONE_TURN_STOPPED");
            if ("readOnly".equals(NativeBridge.phonePermissions.get(call.getString("threadId"))))
                throw new IOException("YELP_SEARCH_PERMISSION_REQUIRED");
        }
    }
}
