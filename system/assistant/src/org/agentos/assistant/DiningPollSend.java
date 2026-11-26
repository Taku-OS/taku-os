package org.agentos.assistant;

import static org.agentos.assistant.DiningPollSendState.failure;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.accessibility.*;

import org.json.*;

import java.io.*;
import java.util.*;

/** A registered GUI send intent, bound native editor and one durable send attempt. */
final class DiningPollSend {
    private static final String PACKAGE = "com.whatsapp";
    private static final String ID = PACKAGE + ":id/";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dining-poll-send", 0);
    }

    private static JSONObject ledger(Context c) throws Exception {
        return new JSONObject(prefs(c).getString("intents", "{}"));
    }

    private static JSONObject require(Context c, String id) throws Exception {
        JSONObject intent = ledger(c).optJSONObject(id);
        if (intent == null) throw failure("POLL_SEND_INTENT_NOT_FOUND");
        return intent;
    }

    private static void save(Context c, JSONObject intent) throws Exception {
        JSONObject all = ledger(c);
        all.put(intent.getString("intentId"), intent);
        if (!prefs(c).edit().putString("intents", all.toString()).commit())
            throw failure("POLL_SEND_STATE_SAVE_FAILED");
    }

    static JSONObject register(Context context, JSONObject args) throws Exception {
        JSONObject fresh = DiningPollSendState.registration(args);
        String id = fresh.getString("intentId");
        synchronized (DiningPollSend.class) {
            JSONObject previous = ledger(context).optJSONObject(id);
            if (previous != null) return registeredState(previous, fresh);
        }
        JSONObject source = DiningPollSendSource.readRequest(fresh);
        DiningPollSendSource.verify(fresh, NativeClient.exchange(source));
        synchronized (DiningPollSend.class) {
            JSONObject all = ledger(context);
            JSONObject previous = all.optJSONObject(id);
            if (previous != null) return registeredState(previous, fresh);
            JSONObject intent =
                    DiningPollSendState.withQuestionCode(fresh, all)
                            .put("registeredAt", System.currentTimeMillis());
            save(context, intent);
            return publicState(intent);
        }
    }

    private static JSONObject registeredState(JSONObject previous, JSONObject fresh)
            throws Exception {
        DiningPollSendState.sameIntent(previous, fresh);
        return publicState(previous);
    }

    static synchronized JSONObject status(Context context, JSONObject args) throws Exception {
        return publicState(require(context, DiningPollSendState.uuid(args, "intentId")));
    }

    static synchronized void checkSubmission(Context context, String intentId, String thread)
            throws Exception {
        JSONObject intent = require(context, intentId);
        String executionThread = intent.optString("executionThreadId");
        if (thread.equals(intent.getString("sourceThreadId"))
                || !executionThread.isEmpty() && !thread.equals(executionThread))
            throw failure("POLL_EXECUTION_THREAD_CHANGED");
        if (!DiningPollSendState.usesQuestionCode(intent))
            throw failure("POLL_MESSAGE_IDENTITY_UNAVAILABLE");
        if ("sent".equals(intent.optString("status"))) throw failure("POLL_ALREADY_SENT");
    }

    private static JSONObject publicState(JSONObject intent) throws Exception {
        JSONObject result = new JSONObject(intent.toString());
        for (String key :
                List.of(
                        "sourceReply",
                        "binding",
                        "foregroundRecipient",
                        "foregroundAllowed",
                        "foregroundFallback",
                        "lastErrorExecutionThreadId",
                        "foregroundResume")) result.remove(key);
        JSONObject receipt = result.optJSONObject("receipt");
        if (receipt != null) {
            receipt.remove("counts");
            receipt.remove("groupFingerprint");
        }
        return result;
    }

    static JSONObject forward(Context c, String action, JSONObject args) throws Exception {
        String id = DiningPollSendState.uuid(args, "intentId");
        synchronized (DiningPollSend.class) {
            JSONObject intent = require(c, id);
            if (intent.optString("status").equals("sent")) return publicState(intent);
        }
        if (Looper.myLooper() == Looper.getMainLooper()) throw failure("POLL_ASYNC_REQUIRED");
        JSONObject request =
                new JSONObject()
                        .put("op", "diningPollInput")
                        .put("action", action)
                        .put("intentId", id);
        if (args.has("executionThreadId"))
            request.put("executionThreadId", DiningPollSendState.uuid(args, "executionThreadId"));
        return NativeClient.exchange(request);
    }

    /** Called by the broker while it holds the exact screen input lease. No broker IPC here. */
    static synchronized JSONObject nativeCall(Context c, JSONObject args) throws Exception {
        String id = DiningPollSendState.uuid(args, "intentId"),
                thread = DiningPollSendState.uuid(args, "executionThreadId"),
                action = args.getString("action");
        if ("observe".equals(action)) return observe(c, args, require(c, id));
        if (!List.of("bind", "send", "inspect").contains(action))
            throw failure("UNKNOWN_POLL_SEND_ACTION");
        JSONObject intent = require(c, id);
        if ("sent".equals(intent.optString("status"))) return publicState(intent);
        if (!DiningPollSendState.usesQuestionCode(intent))
            throw failure("POLL_MESSAGE_IDENTITY_UNAVAILABLE");
        JSONObject screen = screen(args);
        if (intent.has("binding"))
            DiningPollSendState.sameExecutionScreen(intent.getJSONObject("binding"), screen);
        checkTask(c, screen);
        checkCancelled(screen);
        if (action.equals("bind")) {
            String status = intent.getString("status");
            if (status.equals("unknown")) return publicState(intent);
            if (!status.equals("bound") && !status.equals("registered"))
                throw failure("POLL_SEND_STATE_INVALID");
            DiningPollSendAutomation.read(
                    ui -> {
                        if (status.equals("registered")) {
                            JSONObject binding;
                            try {
                                binding = openEditor(c, ui, screen, intent);
                            } catch (Exception unavailable) {
                                logBindFailure(unavailable);
                                throw unavailable;
                            }
                            intent.put("binding", binding)
                                    .put("executionThreadId", thread)
                                    .put("status", "bound")
                                    .put("boundAt", System.currentTimeMillis());
                            save(c, intent);
                        }
                        prepareForm(c, ui, screen, intent);
                        return null;
                    });
            intent.put("prepared", true);
            intent.remove("lastError");
            save(c, intent);
            return publicState(intent).put("editorOpened", true);
        }
        if (!intent.has("binding")) throw failure("POLL_EDITOR_TARGET_UNVERIFIED");
        if (action.equals("inspect") || !DiningPollSendState.mayClick(intent))
            return reconcile(c, intent, screen);
        return DiningPollSendAutomation.read(
                ui -> {
                    checkTask(c, screen);
                    checkCancelled(screen);
                    Ready ready =
                            awaitReady(
                                    c,
                                    ui,
                                    screen,
                                    intent,
                                    SystemClock.elapsedRealtime() + 3000,
                                    "SEND_READY",
                                    true);
                    AccessibilityNodeInfo send = ready.send();
                    // Persist the non-replayable boundary before any input can reach WhatsApp.
                    JSONObject attempted =
                            DiningPollSendState.beforeClick(intent, System.currentTimeMillis());
                    save(c, attempted);
                    try {
                        checkTask(c, screen);
                        checkCancelled(screen);
                        AccessibilityNodeInfo
                                current =
                                        form(
                                                c,
                                                ui,
                                                screen,
                                                attempted,
                                                false,
                                                SystemClock.elapsedRealtime() + 1500,
                                                "SEND_PRECLICK"),
                                live = one(current, "poll_create_button", true);
                        if (!send.equals(live) || !send.refresh())
                            throw failure("POLL_SEND_CONTROL_CHANGED");
                        requireControl(
                                live,
                                "android.widget.ImageButton",
                                AccessibilityNodeInfo.ACTION_CLICK);
                        // No retry, including ACTION_CLICK false, a timeout or a lost response.
                        if (!live.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            throw failure("POLL_SEND_OUTCOME_UNKNOWN");
                        long deadline = SystemClock.elapsedRealtime() + 7000;
                        do {
                            checkCancelled(screen);
                            try {
                                return confirm(
                                        c, attempted, screen, readSent(ui, screen, attempted));
                            } catch (Exception unavailable) {
                                if (SystemClock.elapsedRealtime() >= deadline) break;
                                Thread.sleep(120);
                            }
                        } while (true);
                    } catch (Exception uncertain) {
                        attempted.put(
                                "lastError",
                                uncertain.getMessage() == null
                                        ? "POLL_SEND_OUTCOME_UNKNOWN"
                                        : uncertain.getMessage());
                        save(c, attempted);
                    }
                    if (!attempted.has("lastError")) {
                        attempted.put("lastError", "POLL_SEND_OUTCOME_UNKNOWN");
                        save(c, attempted);
                    }
                    return publicState(attempted);
                });
    }

    /** Native code locations only: no query, group, message, intent or node contents. */
    private static void logBindFailure(Exception failure) {
        StringBuilder trace =
                new StringBuilder("OPEN_EDITOR_FAILED ").append(failure.getClass().getName());
        for (StackTraceElement frame : failure.getStackTrace())
            if (frame.getClassName().startsWith("org.agentos.assistant.DiningPoll"))
                trace.append("\n at ").append(frame);
        android.util.Log.e("DiningPollBind", trace.toString());
    }

    private static JSONObject reconcile(Context c, JSONObject intent, JSONObject screen)
            throws Exception {
        if (!"unknown".equals(intent.optString("status"))) return publicState(intent);
        try {
            return DiningPollSendAutomation.read(
                    ui -> confirm(c, intent, screen, readSent(ui, screen, intent)));
        } catch (Exception unavailable) {
            intent.put(
                    "lastError",
                    unavailable.getMessage() == null
                            ? "POLL_SEND_OUTCOME_UNKNOWN"
                            : unavailable.getMessage());
            save(c, intent);
            return publicState(intent);
        }
    }

    private static JSONObject confirm(
            Context c, JSONObject intent, JSONObject screen, JSONObject receipt) throws Exception {
        checkTask(c, screen);
        JSONObject sent =
                DiningPollSendState.confirmed(
                        intent, receipt.getString("anchor"), System.currentTimeMillis());
        sent.put("messageTimestamp", receipt.getString("timestamp")).put("receipt", receipt);
        save(c, sent);
        DiningPollBooking.submittedPoll(c, sent.getString("intentId"));
        return publicState(sent);
    }

    private static JSONObject observe(Context context, JSONObject target, JSONObject intent)
            throws Exception {
        if (!DiningPollSendState.codedReceiptConfirmed(intent))
            throw failure("POLL_SEND_UNCONFIRMED");
        JSONObject binding = intent.getJSONObject("binding");
        for (String field :
                List.of("executionThreadId", "turnId", "screenKey", "generation", "userId")) {
            if (!binding.get(field).equals(target.get(field)))
                throw failure("POLL_EXECUTION_SCREEN_CHANGED");
        }
        boolean foreground = Boolean.TRUE.equals(target.opt("readOnlyForeground"));
        boolean rebound = Boolean.TRUE.equals(target.opt("readOnlyRebound"));
        int display = target.getInt("displayId");
        if (!PACKAGE.equals(target.opt("packageName"))
                || target.optBoolean("legacy", true)
                || target.getInt("taskId") < 0
                || target.getInt("userId") < 0
                || display < 0
                || foreground != (display == 0)
                || foreground
                        && target.optInt("originalDisplayId", -1) != binding.getInt("displayId")
                || rebound
                        && (!foreground
                                || target.optInt("originalTaskId", -1) != binding.getInt("taskId"))
                || !rebound && target.getInt("taskId") != binding.getInt("taskId")
                || !foreground && display != binding.getInt("displayId"))
            throw failure("POLL_EXECUTION_SCREEN_CHANGED");
        return DiningPollSendAutomation.read(
                ui -> {
                    checkObservationTask(context, target, foreground, rebound);
                    JSONObject first = readSent(ui, target, intent, true);
                    if (foreground) {
                        JSONObject second = readSent(ui, target, intent, true);
                        if (!first.getString("anchor").equals(second.getString("anchor"))
                                || !first.getJSONArray("counts")
                                        .toString()
                                        .equals(second.getJSONArray("counts").toString()))
                            throw failure("POLL_TALLY_CHANGED");
                        first = second;
                    }
                    checkObservationTask(context, target, foreground, rebound);
                    if (!intent.getString("anchor").equals(first.getString("anchor")))
                        throw failure("POLL_MESSAGE_CHANGED");
                    return first.put("intentId", intent.getString("intentId"))
                            .put("observationTaskId", target.getInt("taskId"))
                            .put("observationDisplayId", display)
                            .put("observationUserId", target.getInt("userId"));
                });
    }

    private static void checkObservationTask(
            Context context, JSONObject target, boolean foreground, boolean rebound)
            throws Exception {
        checkCancelled(target);
        if (!foreground) {
            checkTask(context, target);
            return;
        }
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            throw failure("POLL_PHONE_LOCKED");
        int userId = android.os.Process.myUid() / 100_000;
        boolean userForeground = context.getSystemService(UserManager.class).isUserForeground();
        if (!userForeground || userId != target.getInt("userId"))
            throw failure("POLL_EXECUTION_USER_CHANGED");
        List<ActivityManager.RunningTaskInfo> tasks =
                context.getSystemService(ActivityManager.class).getRunningTasks(100);
        if (rebound) {
            JSONArray inventory = new JSONArray();
            for (ActivityManager.RunningTaskInfo task : tasks) {
                inventory.put(
                        new JSONObject()
                                .put("taskId", task.taskId)
                                .put("userId", taskUser(task))
                                .put("displayId", DiningPollUi.display(task))
                                .put("visible", task.isVisible())
                                .put(
                                        "basePackage",
                                        task.baseActivity == null
                                                ? ""
                                                : task.baseActivity.getPackageName())
                                .put(
                                        "topPackage",
                                        task.topActivity == null
                                                ? ""
                                                : task.topActivity.getPackageName()));
            }
            DiningPollSendState.reboundTask(target, inventory, userId, userForeground);
            return;
        }
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (DiningPollUi.display(task) != 0 || task.topActivity == null || !task.isVisible())
                continue;
            if (task.taskId == target.getInt("taskId")
                    && taskUser(task) == userId
                    && PACKAGE.equals(task.topActivity.getPackageName())) return;
            break;
        }
        throw failure("POLL_EXECUTION_TASK_CHANGED");
    }

    private static int taskUser(ActivityManager.RunningTaskInfo task) throws Exception {
        return task.getClass().getField("userId").getInt(task);
    }

    static synchronized JSONObject sentIntent(Context context, String id) throws Exception {
        JSONObject intent = require(context, id);
        if (!DiningPollSendState.codedReceiptConfirmed(intent)
                || intent.optJSONObject("binding") == null) throw failure("POLL_SEND_UNCONFIRMED");
        return intent;
    }

    static synchronized List<JSONObject> recentSentIntents(Context context, long since)
            throws Exception {
        JSONObject ledger = ledger(context);
        List<JSONObject> intents = new ArrayList<>();
        for (var keys = ledger.keys(); keys.hasNext(); ) {
            JSONObject intent = ledger.getJSONObject(keys.next());
            if (intent.optLong("sentAt") >= since
                    && intent.optJSONObject("binding") != null
                    && DiningPollSendState.codedReceiptConfirmed(intent)) intents.add(intent);
        }
        return intents;
    }

    private static JSONObject screen(JSONObject args) throws Exception {
        JSONObject screen = new JSONObject();
        for (String field :
                List.of(
                        "executionThreadId",
                        "turnId",
                        "screenKey",
                        "generation",
                        "displayId",
                        "taskId",
                        "userId",
                        "cancelPath")) screen.put(field, args.get(field));
        File directory = DiningPollSendState.cleanupTarget(args).getParentFile();
        if (!directory.isDirectory() || !directory.canRead() || !directory.canWrite())
            throw failure("POLL_OPERATION_DIRECTORY_UNAVAILABLE");
        if (!PACKAGE.equals(args.optString("packageName"))
                || args.optBoolean("legacy", true)
                || screen.getInt("displayId") <= 0
                || screen.getInt("taskId") < 0
                || screen.getInt("userId") < 0
                || screen.getString("screenKey").isBlank()
                || screen.getString("generation").isBlank())
            throw failure("POLL_EXECUTION_SCREEN_INVALID");
        return screen;
    }

    private static void checkTask(Context context, JSONObject screen) throws Exception {
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked())
            throw failure("POLL_PHONE_LOCKED");
        if (android.os.Process.myUid() / 100000 != screen.getInt("userId")
                || !context.getSystemService(UserManager.class).isUserForeground())
            throw failure("POLL_EXECUTION_USER_CHANGED");
        List<ActivityManager.RunningTaskInfo> tasks =
                context.getSystemService(ActivityManager.class).getRunningTasks(100);
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (DiningPollUi.display(task) != screen.getInt("displayId")
                    || task.topActivity == null) continue;
            if (task.taskId == screen.getInt("taskId")
                    && PACKAGE.equals(task.topActivity.getPackageName())) return;
            break;
        }
        throw failure("POLL_EXECUTION_TASK_CHANGED");
    }

    private static JSONObject openEditor(
            Context c, UiAutomation ui, JSONObject screen, JSONObject intent) throws Exception {
        checkCancelled(screen);
        AccessibilityNodeInfo chat =
                awaitRoot(c, ui, screen, SystemClock.elapsedRealtime() + 3000, "BIND_CHAT");
        String group = intent.getString("groupName");
        AccessibilityNodeInfo composer;
        // A failed, unsent bind may leave this same authorized query open. Never clear
        // another query or an existing message draft just to restart navigation.
        if (one(chat, "search_input", false) == null
                && one(chat, "search_bar_inner_layout", false) == null) {
            group(chat, intent);
            emptyComposer(chat, one(chat, "entry", true));
        }
        chat = uniqueGroup(c, ui, screen, intent);
        composer = one(chat, "entry", true);
        emptyComposer(chat, composer);
        chat =
                awaitRoot(
                        c, ui, screen, SystemClock.elapsedRealtime() + 1500, "BIND_GROUP_READBACK");
        group(chat, intent);
        emptyComposer(chat, one(chat, "entry", true));
        AccessibilityNodeInfo attachment = one(chat, "input_attach_button", true);
        click(attachment, "android.widget.ImageButton");
        long deadline = SystemClock.elapsedRealtime() + 3000;
        AccessibilityNodeInfo picker;
        while (true) {
            checkCancelled(screen);
            picker = awaitRoot(c, ui, screen, deadline, "ATTACHMENT_PICKER");
            group(picker, intent);
            AccessibilityNodeInfo poll = one(picker, "pickfiletype_poll_holder", false);
            if (poll != null) {
                click(poll, "android.widget.Button");
                break;
            }
            if (SystemClock.elapsedRealtime() >= deadline)
                throw failure("POLL_PICKER_UNAVAILABLE__STAGE_ATTACHMENT_PICKER");
            Thread.sleep(80);
        }
        deadline = SystemClock.elapsedRealtime() + 3000;
        AccessibilityNodeInfo form;
        while (true) {
            checkCancelled(screen);
            form = awaitRoot(c, ui, screen, deadline, "POLL_CREATOR");
            if (one(form, "poll_creator_container", false) != null) break;
            if (SystemClock.elapsedRealtime() >= deadline)
                throw failure("POLL_FORM_UNAVAILABLE__STAGE_POLL_CREATOR");
            Thread.sleep(80);
        }
        checkTask(c, screen);
        JSONObject binding =
                new JSONObject(screen.toString())
                        .put("groupName", group)
                        .put("editorWindowId", form.getWindowId())
                        .put("editorRootId", form.hashCode());
        return binding;
    }

    /** Search all Chats, including off-screen positions, before selecting one exact name. */
    private static AccessibilityNodeInfo uniqueGroup(
            Context c, UiAutomation ui, JSONObject screen, JSONObject intent) throws Exception {
        String target = intent.getString("groupName");
        long deadline = SystemClock.elapsedRealtime() + 12000;
        AccessibilityNodeInfo root = awaitRoot(c, ui, screen, deadline, "UNIQUE_GROUP_CHAT");
        AccessibilityNodeInfo input = one(root, "search_input", false);
        if (input != null) {
            if (!"registered".equals(intent.optString("status"))
                    || !target.equals(searchQuery(input))
                    || one(root, "search_fragment", false) == null)
                throw failure("POLL_SEARCH_UNVERIFIED");
            // Back out of this exact unsent search, then reopen the normal global
            // entry. Reusing its rows could retain an Unread/Photos/Polls filter.
            checkCancelled(screen);
            click(one(root, "wds_toolbar_nav_button", true), "android.widget.ImageButton");
        } else if (one(root, "search_bar_inner_layout", false) == null) {
            group(root, intent);
            click(one(root, "whatsapp_toolbar_home", true), "android.widget.Button");
        }
        {
            root = awaitNode(c, ui, screen, "search_bar_inner_layout", deadline);
            if (one(root, "search_input", false) != null) throw failure("POLL_SEARCH_UNVERIFIED");
            click(
                    one(root, "search_bar_inner_layout", true),
                    "androidx.appcompat.widget.LinearLayoutCompat");
            root = awaitNode(c, ui, screen, "search_input", deadline);
            input = one(root, "search_input", true);
            if (!"android.widget.EditText".contentEquals(input.getClassName())
                    || !input.isEditable()
                    || input.isPassword()
                    || !input.isEnabled()
                    || !input.refresh()) throw failure("POLL_SEARCH_UNVERIFIED");
            boolean blankView =
                    nodes(root, "search_unread_filter", true).stream()
                                    .anyMatch(
                                            n ->
                                                    "android.widget.Button"
                                                                    .contentEquals(n.getClassName())
                                                            && "Unread"
                                                                    .equals(
                                                                            DiningPollUi.value(
                                                                                    n.getText())))
                            && nodes(root, "search_media_filter_image", true).stream()
                                    .anyMatch(
                                            n ->
                                                    "android.widget.Button"
                                                                    .contentEquals(n.getClassName())
                                                            && "Photos"
                                                                    .equals(
                                                                            DiningPollUi.value(
                                                                                    n.getText())))
                            && nodes(root, "header_title", true).stream()
                                    .anyMatch(
                                            n ->
                                                    "android.widget.TextView"
                                                                    .contentEquals(n.getClassName())
                                                            && "Recent searches"
                                                                    .equals(
                                                                            DiningPollUi.value(
                                                                                    n.getText())));
            CharSequence searchText = input.getText();
            DiningPollSendTarget.emptySearch(
                    searchText == null ? "" : searchText.toString(),
                    input.isShowingHintText(),
                    nodes(root, "search_clear_btn", true).size(),
                    blankView);
            checkCancelled(screen);
            Bundle query = new Bundle();
            query.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target);
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, query))
                throw failure("POLL_SEARCH_REJECTED");
        }
        AccessibilityNodeInfo selected = null;
        String proof = "";
        int stable = 0;
        boolean verified = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            checkTask(c, screen);
            checkCancelled(screen);
            root = awaitRoot(c, ui, screen, deadline, "SEARCH_RESULTS");
            if (target.equals(searchQuery(one(root, "search_input", true)))) {
                selected = completeSearchRow(root, target);
                if (selected == null) {
                    selected = scanSearchRows(c, ui, screen, target, deadline);
                    verified = true;
                    break;
                }
                String now =
                        root.getWindowId() + ":" + selected.hashCode() + ":" + bounds(selected);
                if (now.equals(proof)) stable++;
                else {
                    proof = now;
                    stable = 0;
                }
                if (stable >= 2) {
                    verified = true;
                    break;
                }
            }
            Thread.sleep(100);
        }
        checkTask(c, screen);
        checkCancelled(screen);
        if (!verified || selected == null || !selected.refresh())
            throw DiningPollGroupSearch.incomplete();
        requireControl(
                selected, selected.getClassName().toString(), AccessibilityNodeInfo.ACTION_CLICK);
        if (!selected.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            throw failure("POLL_GROUP_TARGET_CHANGED");
        root = awaitNode(c, ui, screen, "conversation_contact_name", deadline + 3000);
        group(root, intent);
        // A contact with the same display name is not a group. WhatsApp's native group-info
        // activity supplies this type proof; no subtitle/name heuristic is used.
        click(one(root, "conversation_contact", true), "android.widget.LinearLayout");
        long infoDeadline = SystemClock.elapsedRealtime() + 3000;
        boolean isGroup = false;
        while (SystemClock.elapsedRealtime() < infoDeadline) {
            checkTask(c, screen);
            checkCancelled(screen);
            String activity = topClass(c, screen);
            try {
                DiningPollSendTarget.requireGroupActivity(activity);
                isGroup = true;
                break;
            } catch (IOException notGroup) {
                /* Wait only for the actual info activity transition. */
            }
            if (activity.endsWith(".ContactInfo") || activity.endsWith(".ContactInfoActivity"))
                throw failure("POLL_TARGET_IS_NOT_GROUP");
            Thread.sleep(80);
        }
        if (!isGroup) throw failure("POLL_GROUP_TYPE_UNVERIFIED");
        groupInfoBack(c, ui, screen, target);
        root =
                awaitNode(
                        c,
                        ui,
                        screen,
                        "conversation_contact_name",
                        SystemClock.elapsedRealtime() + 3000,
                        "GROUP_INFO_TO_CHAT");
        group(root, intent);
        return root;
    }

    private static String searchQuery(AccessibilityNodeInfo input) {
        String value = DiningPollUi.value(input.getText());
        return value.startsWith("\u200b") ? value.substring(1) : value;
    }

    /** A one-frame complete prefix retains the short-list path and needs no adapter metadata. */
    private static AccessibilityNodeInfo completeSearchRow(
            AccessibilityNodeInfo root, String target) throws Exception {
        AccessibilityNodeInfo list = one(root, "result_list", false);
        if (list == null) return null;
        List<AccessibilityNodeInfo> nativeRows = new ArrayList<>();
        List<DiningPollSendTarget.Row> facts = new ArrayList<>();
        for (AccessibilityNodeInfo name : nodes(list, "conversations_row_contact_name", true)) {
            AccessibilityNodeInfo row = searchResultRow(name, list);
            if (row == null) continue;
            if (nativeRows.contains(row)) throw failure("POLL_GROUP_TARGET_UNVERIFIED");
            nativeRows.add(row);
            facts.add(
                    new DiningPollSendTarget.Row(
                            DiningPollUi.value(name.getText()),
                            DiningPollUi.value(name.getPackageName()),
                            DiningPollUi.value(name.getClassName()),
                            under(row, list),
                            row.isClickable(),
                            row.isEnabled(),
                            hasAction(row, AccessibilityNodeInfo.ACTION_CLICK)));
        }
        // Non-scrollability is not proof of a populated Chats section: query text
        // can update before its results. Every short path needs the native boundary.
        if (facts.isEmpty()
                || !DiningPollSendTarget.completeChats(searchSections(list), facts.size()))
            return null;
        if (facts.stream().filter(row -> target.equals(row.name())).count() > 1)
            throw failure("POLL_GROUP_AMBIGUOUS");
        int match =
                DiningPollSendTarget.uniqueResult(
                        searchQuery(one(root, "search_input", true)),
                        target,
                        DiningPollUi.value(list.getClassName()),
                        false,
                        facts);
        return nativeRows.get(match);
    }

    private record SearchPage(
            DiningPollGroupSearch.Page facts,
            Map<Integer, AccessibilityNodeInfo> rows,
            AccessibilityNodeInfo list,
            String stamp) {}

    private static boolean hasAction(AccessibilityNodeInfo node, int action) {
        return node.getActionList().stream().anyMatch(item -> item.getId() == action);
    }

    private static SearchPage searchPage(AccessibilityNodeInfo root, String target)
            throws Exception {
        AccessibilityNodeInfo input = one(root, "search_input", true),
                list = one(root, "result_list", false);
        if (list == null) throw new DiningPollGroupSearch.Pending();
        if (!list.refresh()
                || !list.isVisibleToUser()
                || !"androidx.recyclerview.widget.RecyclerView".contentEquals(list.getClassName()))
            throw DiningPollGroupSearch.incomplete();
        AccessibilityNodeInfo.CollectionInfo collection = list.getCollectionInfo();
        if (collection == null || collection.getRowCount() < 1)
            throw new DiningPollGroupSearch.Pending();
        if (collection.getColumnCount() < 1) throw DiningPollGroupSearch.incomplete();
        Rect viewport = bounds(list);
        List<DiningPollGroupSearch.Item> items = new ArrayList<>();
        Map<Integer, AccessibilityNodeInfo> rows = new HashMap<>();
        StringBuilder stamp = new StringBuilder(viewport.toString());
        for (int i = 0; i < list.getChildCount() && i < 80; i++) {
            AccessibilityNodeInfo child = list.getChild(i);
            if (child == null
                    || !child.refresh()
                    || child.getWindowId() != list.getWindowId()
                    || !PACKAGE.contentEquals(child.getPackageName()))
                throw DiningPollGroupSearch.incomplete();
            if (!child.isVisibleToUser()) continue;
            AccessibilityNodeInfo.CollectionItemInfo position = child.getCollectionItemInfo();
            if (position == null
                    || position.getRowSpan() != 1
                    || position.getColumnIndex() != 0
                    || position.getColumnSpan() != collection.getColumnCount())
                throw DiningPollGroupSearch.incomplete();
            int row = position.getRowIndex();
            Rect box = bounds(child);
            stamp.append('|').append(row).append(':').append(box);
            List<AccessibilityNodeInfo> titles = nodes(child, "title", true);
            List<AccessibilityNodeInfo> names =
                    nodes(child, "conversations_row_contact_name", true);
            DiningPollGroupSearch.Kind kind;
            String name = "";
            if (titles.size() == 1 && "CHATS".equals(DiningPollUi.value(titles.get(0).getText())))
                kind = DiningPollGroupSearch.Kind.CHATS;
            else if (titles.size() == 1
                    && "MESSAGES".equals(DiningPollUi.value(titles.get(0).getText())))
                kind = DiningPollGroupSearch.Kind.MESSAGES;
            else if (id(child, "contact_row_container")) {
                // A 4px recycled edge row has no visible label. Its adapter position
                // must be filled by the following page, not treated as another chat.
                if (names.isEmpty()) continue;
                if (names.size() != 1
                        || !child.isClickable()
                        || !child.isEnabled()
                        || !hasAction(child, AccessibilityNodeInfo.ACTION_CLICK))
                    throw DiningPollGroupSearch.incomplete();
                AccessibilityNodeInfo label = names.get(0);
                if (!"android.widget.TextView".contentEquals(label.getClassName())
                        || !child.equals(
                                DiningPollGroupSearch.pageAncestry(
                                        () -> searchResultRow(label, list))))
                    throw DiningPollGroupSearch.incomplete();
                kind = DiningPollGroupSearch.Kind.CHAT;
                name = DiningPollUi.value(label.getText());
                rows.put(row, child);
            } else if ("android.widget.HorizontalScrollView".contentEquals(child.getClassName())
                    && nodes(child, "search_poll_filter", true).size() == 1)
                kind = DiningPollGroupSearch.Kind.FILTER;
            else throw DiningPollGroupSearch.incomplete();
            StringBuilder fingerprint = new StringBuilder();
            searchFingerprint(child, fingerprint, new HashSet<>(), 0);
            boolean full = viewport.contains(box) && !box.isEmpty();
            if (kind == DiningPollGroupSearch.Kind.CHAT) {
                Rect label = bounds(names.get(0));
                full &=
                        viewport.contains(label)
                                && box.contains(label)
                                && box.height() >= label.height() * 2;
            }
            items.add(
                    new DiningPollGroupSearch.Item(row, kind, name, fingerprint.toString(), full));
            if (kind == DiningPollGroupSearch.Kind.MESSAGES) break;
        }
        if (items.stream()
                .noneMatch(
                        item ->
                                item.kind() == DiningPollGroupSearch.Kind.CHAT
                                        || item.kind() == DiningPollGroupSearch.Kind.MESSAGES))
            throw new DiningPollGroupSearch.Pending();
        boolean back = hasAction(list, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
                forward = hasAction(list, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        var facts =
                new DiningPollGroupSearch.Page(
                        searchQuery(input),
                        list.getWindowId(),
                        collection.getRowCount(),
                        back,
                        forward,
                        List.copyOf(items));
        stamp.append('|').append(facts);
        return new SearchPage(facts, Map.copyOf(rows), list, stamp.toString());
    }

    private static void searchFingerprint(
            AccessibilityNodeInfo node,
            StringBuilder out,
            Set<AccessibilityNodeInfo> seen,
            int depth)
            throws Exception {
        if (node == null
                || depth > 20
                || seen.size() > 120
                || !seen.add(node)
                || !node.refresh()
                || !PACKAGE.contentEquals(node.getPackageName()))
            throw DiningPollGroupSearch.incomplete();
        // Bounds/visibility change while scrolling; the complete native row semantics do not.
        for (CharSequence value :
                List.of(
                        DiningPollUi.value(node.getViewIdResourceName()),
                        DiningPollUi.value(node.getClassName()),
                        DiningPollUi.value(node.getText()),
                        DiningPollUi.value(node.getContentDescription())))
            out.append(value.length()).append(':').append(value).append(';');
        out.append(node.getChildCount()).append('[');
        for (int i = 0; i < node.getChildCount(); i++)
            searchFingerprint(node.getChild(i), out, seen, depth + 1);
        out.append(']');
    }

    private static SearchPage settledSearchPage(
            Context c,
            UiAutomation ui,
            JSONObject screen,
            String target,
            long deadline,
            SearchPage prior,
            boolean moved)
            throws Exception {
        DiningPollGroupSearch.Identity identity =
                new DiningPollGroupSearch.Identity(
                        target, prior == null ? -1 : prior.facts().window());
        return DiningPollGroupSearch.settle(
                () -> {
                    checkTask(c, screen);
                    checkCancelled(screen);
                    AccessibilityNodeInfo
                            root = awaitRoot(c, ui, screen, deadline, "GROUP_SEARCH_PAGE"),
                            input = one(root, "search_input", true);
                    if (!input.refresh()) throw DiningPollGroupSearch.incomplete();
                    identity.check(searchQuery(input), root.getWindowId());
                    return root;
                },
                root -> searchPage(root, target),
                page -> {
                    checkTask(c, screen);
                    checkCancelled(screen);
                    identity.check(page.facts().query(), page.facts().window());
                    // Re-read current identity after constructing the subtree, outside the retry
                    // catch.
                    AccessibilityNodeInfo
                            live = awaitRoot(c, ui, screen, deadline, "GROUP_SEARCH_IDENTITY"),
                            input = one(live, "search_input", true);
                    if (!input.refresh()) throw DiningPollGroupSearch.incomplete();
                    identity.check(searchQuery(input), live.getWindowId());
                },
                SearchPage::stamp,
                SystemClock::elapsedRealtime,
                Thread::sleep,
                deadline,
                prior == null ? null : prior.stamp(),
                moved);
    }

    private static SearchPage scrollSearchPage(
            Context c,
            UiAutomation ui,
            JSONObject screen,
            String target,
            long deadline,
            SearchPage page,
            boolean forward)
            throws Exception {
        int action =
                forward
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        checkTask(c, screen);
        checkCancelled(screen);
        if (!page.list().refresh()
                || !hasAction(page.list(), action)
                || !page.list().performAction(action)) throw DiningPollGroupSearch.incomplete();
        return settledSearchPage(c, ui, screen, target, deadline, page, true);
    }

    private static AccessibilityNodeInfo scanSearchRows(
            Context c, UiAutomation ui, JSONObject screen, String target, long deadline)
            throws Exception {
        SearchPage page = settledSearchPage(c, ui, screen, target, deadline, null, false);
        for (int i = 0; page.facts().backward(); i++) {
            if (i >= 12) throw DiningPollGroupSearch.incomplete();
            page = scrollSearchPage(c, ui, screen, target, deadline, page, false);
        }
        DiningPollGroupSearch.Census census = new DiningPollGroupSearch.Census(target);
        while (true) {
            DiningPollGroupSearch.Move move = census.accept(page.facts());
            if (move == DiningPollGroupSearch.Move.VERIFIED) break;
            page =
                    scrollSearchPage(
                            c,
                            ui,
                            screen,
                            target,
                            deadline,
                            page,
                            move == DiningPollGroupSearch.Move.FORWARD);
        }
        for (int i = 0; i < 12; i++) {
            DiningPollGroupSearch.Item selected = census.selection(page.facts());
            if (selected != null) {
                SearchPage fresh = settledSearchPage(c, ui, screen, target, deadline, null, false);
                DiningPollGroupSearch.Item checked = census.selection(fresh.facts());
                if (checked == null || checked.position() != selected.position())
                    throw DiningPollGroupSearch.incomplete();
                AccessibilityNodeInfo row = fresh.rows().get(checked.position());
                if (row == null || !row.refresh()) throw DiningPollGroupSearch.incomplete();
                return row;
            }
            int first = page.facts().items().get(0).position();
            page =
                    scrollSearchPage(
                            c, ui, screen, target, deadline, page, census.targetPosition() > first);
        }
        throw DiningPollGroupSearch.incomplete();
    }

    /** Capture only the complete Chats prefix, including its following Messages boundary. */
    private static DiningPollSendTarget.SearchSections searchSections(AccessibilityNodeInfo list)
            throws Exception {
        boolean refreshed = list.refresh(), backward = false;
        for (AccessibilityNodeInfo.AccessibilityAction action : list.getActionList())
            if (action.getId() == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) backward = true;
        Rect viewport = bounds(list);
        List<DiningPollSendTarget.SearchSectionItem> items = new ArrayList<>();
        for (int i = 0; i < list.getChildCount() && i < 80; i++) {
            AccessibilityNodeInfo child = list.getChild(i);
            if (child == null) throw failure("POLL_GROUP_TARGET_UNVERIFIED");
            boolean fresh = child.refresh();
            Rect box = bounds(child);
            List<String> titles = new ArrayList<>();
            for (AccessibilityNodeInfo title : nodes(child, "title", false)) {
                boolean valid =
                        title.refresh()
                                && title.isVisibleToUser()
                                && "android.widget.TextView".contentEquals(title.getClassName())
                                && viewport.contains(bounds(title));
                titles.add(valid ? DiningPollUi.value(title.getText()) : "<unverified>");
            }
            int names = nodes(child, "conversations_row_contact_name", false).size();
            boolean filter = false;
            List<AccessibilityNodeInfo> filters = nodes(child, "search_poll_filter", false);
            if (filters.size() == 1 && child.getChildCount() == 1) {
                AccessibilityNodeInfo container = child.getChild(0), button = filters.get(0);
                filter =
                        container != null
                                && container.refresh()
                                && "android.view.ViewGroup".contentEquals(container.getClassName())
                                && container.getChildCount() == 1
                                && button.equals(container.getChild(0))
                                && button.refresh()
                                && button.isVisibleToUser()
                                && button.isEnabled()
                                && !button.isSelected()
                                && !button.isChecked()
                                && "android.widget.Button".contentEquals(button.getClassName())
                                && "Polls".equals(DiningPollUi.value(button.getText()))
                                && viewport.contains(bounds(button));
            }
            items.add(
                    new DiningPollSendTarget.SearchSectionItem(
                            DiningPollUi.value(child.getViewIdResourceName()),
                            DiningPollUi.value(child.getClassName()),
                            DiningPollUi.value(child.getPackageName()),
                            child.getWindowId(),
                            fresh,
                            child.isVisibleToUser(),
                            !box.isEmpty() && viewport.contains(box),
                            box.top,
                            box.bottom,
                            List.copyOf(titles),
                            names,
                            filter));
            if (titles.equals(List.of("MESSAGES"))) break;
        }
        return new DiningPollSendTarget.SearchSections(
                list.getWindowId(),
                refreshed,
                list.isVisibleToUser(),
                backward,
                List.copyOf(items));
    }

    /** Prove the bounded ancestry to this exact result list before excluding a message hit. */
    private static AccessibilityNodeInfo searchResultRow(
            AccessibilityNodeInfo name, AccessibilityNodeInfo list) throws Exception {
        List<DiningPollSendTarget.SearchNode> path = new ArrayList<>();
        Set<AccessibilityNodeInfo> seen = new HashSet<>();
        AccessibilityNodeInfo node = name, row = null;
        boolean reached = false;
        for (int depth = 0; node != null && depth < 24; depth++) {
            if (!seen.add(node)) throw failure("POLL_GROUP_TARGET_UNVERIFIED");
            boolean refreshed = node.refresh();
            path.add(
                    new DiningPollSendTarget.SearchNode(
                            DiningPollUi.value(node.getViewIdResourceName()),
                            DiningPollUi.value(node.getPackageName()),
                            node.getWindowId(),
                            node.isVisibleToUser(),
                            refreshed));
            if (id(node, "contact_row_container")) row = node;
            if (node.equals(list)) {
                reached = true;
                break;
            }
            node = node.getParent();
        }
        return DiningPollSendTarget.messageSearchResult(
                        DiningPollUi.value(name.getClassName()), list.getWindowId(), reached, path)
                ? null
                : row;
    }

    /** Wait only before dispatch. A rejected/unknown Back dispatch is never retried. */
    private static void groupInfoBack(Context c, UiAutomation ui, JSONObject screen, String target)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        AccessibilityNodeInfo previousRoot = null,
                previousBack = null,
                readyRoot = null,
                readyBack = null;
        Rect previousBounds = null;
        int matching = 0;
        Exception last = failure("POLL_GROUP_INFO_BACK_NOT_READY");
        while (SystemClock.elapsedRealtime() < deadline) {
            checkTask(c, screen);
            checkCancelled(screen);
            try {
                AccessibilityNodeInfo root = groupInfoRoot(c, ui, screen, target, deadline),
                        back = one(root, "wds_toolbar_nav_button", false);
                if (back == null) back = one(root, "back", true);
                requireControl(
                        back, "android.widget.ImageButton", AccessibilityNodeInfo.ACTION_CLICK);
                Rect currentBounds = bounds(back);
                if (root.equals(previousRoot)
                        && back.equals(previousBack)
                        && currentBounds.equals(previousBounds)) matching++;
                else matching = 0;
                previousRoot = root;
                previousBack = back;
                previousBounds = currentBounds;
                if (matching >= 2) {
                    readyRoot = root;
                    readyBack = back;
                    break;
                }
            } catch (IOException unsettled) {
                last = unsettled;
                matching = 0;
                previousRoot = null;
                previousBack = null;
                previousBounds = null;
            }
            Thread.sleep(100);
        }
        if (readyBack == null) throw last;
        // Reacquire the actual root and control once more after the settling samples.
        // No click has occurred yet, so a failed final check safely stops here.
        checkTask(c, screen);
        checkCancelled(screen);
        AccessibilityNodeInfo root = groupInfoRoot(c, ui, screen, target, deadline),
                back = one(root, "wds_toolbar_nav_button", false);
        if (back == null) back = one(root, "back", true);
        if (!root.equals(readyRoot)
                || !back.equals(readyBack)
                || !bounds(back).equals(previousBounds))
            throw failure("POLL_GROUP_INFO_BACK_CHANGED");
        requireControl(back, "android.widget.ImageButton", AccessibilityNodeInfo.ACTION_CLICK);
        checkCancelled(screen);
        if (!back.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            throw failure("POLL_GROUP_INFO_BACK_DISPATCH_UNCONFIRMED");
    }

    private static AccessibilityNodeInfo groupInfoRoot(
            Context c, UiAutomation ui, JSONObject screen, String target, long deadline)
            throws Exception {
        DiningPollSendTarget.requireGroupActivity(topClass(c, screen));
        AccessibilityNodeInfo root = awaitRoot(c, ui, screen, deadline, "GROUP_INFO_BACK");
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        collect(root, labels, 0);
        if (labels.stream()
                .noneMatch(n -> !n.isEditable() && target.equals(DiningPollUi.value(n.getText()))))
            throw failure("POLL_GROUP_TARGET_CHANGED");
        return root;
    }

    private static AccessibilityNodeInfo awaitNode(
            Context c, UiAutomation ui, JSONObject screen, String name, long deadline)
            throws Exception {
        return awaitNode(c, ui, screen, name, deadline, name.toUpperCase(Locale.ROOT));
    }

    private static AccessibilityNodeInfo awaitNode(
            Context c, UiAutomation ui, JSONObject screen, String name, long deadline, String stage)
            throws Exception {
        while (true) {
            checkTask(c, screen);
            checkCancelled(screen);
            AccessibilityNodeInfo root = awaitRoot(c, ui, screen, deadline, stage);
            if (one(root, name, false) != null) return root;
            if (SystemClock.elapsedRealtime() >= deadline)
                throw failure("POLL_NAVIGATION_UNVERIFIED__STAGE_" + stage);
            Thread.sleep(80);
        }
    }

    private static AccessibilityNodeInfo awaitRoot(
            Context c, UiAutomation ui, JSONObject screen, long deadline, String stage)
            throws Exception {
        return DiningPollSendWait.read(
                () -> DiningPollUi.root(ui, screen.getInt("displayId"), PACKAGE),
                () -> {
                    checkTask(c, screen);
                    checkCancelled(screen);
                },
                SystemClock::elapsedRealtime,
                Thread::sleep,
                deadline,
                stage);
    }

    private static String topClass(Context c, JSONObject screen) throws Exception {
        for (ActivityManager.RunningTaskInfo task :
                c.getSystemService(ActivityManager.class).getRunningTasks(100))
            if (task.taskId == screen.getInt("taskId") && task.topActivity != null)
                return task.topActivity.getClassName();
        throw failure("POLL_EXECUTION_TASK_CHANGED");
    }

    private static void collect(
            AccessibilityNodeInfo node, List<AccessibilityNodeInfo> all, int depth)
            throws Exception {
        if (depth > 40 || all.size() > 1800) throw failure("POLL_GROUP_UI_LIMIT");
        if (node == null || !node.isVisibleToUser()) return;
        all.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), all, depth + 1);
    }

    private static void prepareForm(
            Context c, UiAutomation ui, JSONObject screen, JSONObject intent) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12000;
        AccessibilityNodeInfo root =
                form(c, ui, screen, intent, true, deadline, "PREPARE_QUESTION");
        AccessibilityNodeInfo question = oneAny(root, "poll_question_input");
        write(c, screen, question, DiningPollSendState.wireQuestion(intent));
        JSONArray expected = intent.getJSONArray("options");
        for (int index = 0; index < expected.length(); index++) {
            while (true) {
                checkTask(c, screen);
                checkCancelled(screen);
                root = form(c, ui, screen, intent, true, deadline, "PREPARE_OPTIONS");
                List<AccessibilityNodeInfo> fields =
                        new ArrayList<>(nodes(root, "poll_option_input", false));
                fields.sort(Comparator.comparingInt(n -> bounds(n).top));
                if (fields.size() > index && fields.get(index).isVisibleToUser()) {
                    write(c, screen, fields.get(index), expected.getString(index));
                    break;
                }
                scrollForm(root, true);
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw failure("POLL_OPTION_UNAVAILABLE");
                Thread.sleep(100);
            }
        }
        while (true) {
            checkTask(c, screen);
            checkCancelled(screen);
            root = form(c, ui, screen, intent, true, deadline, "PREPARE_SINGLE_CHOICE");
            AccessibilityNodeInfo modeRow = oneAny(root, "single_option_control_switch");
            List<AccessibilityNodeInfo> modes = nodes(modeRow, "row_addon_end_switch_button", true);
            if (modes.size() == 1) {
                AccessibilityNodeInfo mode = modes.get(0);
                if (!mode.isCheckable()) throw failure("POLL_MODE_UNVERIFIED");
                if (mode.isChecked()) click(mode, "android.widget.Switch");
                break;
            }
            scrollForm(root, true);
            if (SystemClock.elapsedRealtime() >= deadline) throw failure("POLL_MODE_UNAVAILABLE");
            Thread.sleep(100);
        }
        awaitReady(
                c,
                ui,
                screen,
                intent,
                SystemClock.elapsedRealtime() + 3000,
                "PREPARE_READBACK",
                false);
    }

    private static void write(
            Context c, JSONObject screen, AccessibilityNodeInfo node, String expected)
            throws Exception {
        checkTask(c, screen);
        checkCancelled(screen);
        String existing = editorValue(node);
        if (expected.equals(existing)) return;
        if (!existing.isEmpty()) throw failure("POLL_EXISTING_DRAFT_PRESERVED");
        if (!node.refresh()
                || !node.isVisibleToUser()
                || !node.isEnabled()
                || !node.isEditable()
                || node.isPassword()
                || !"android.widget.EditText".contentEquals(node.getClassName())
                || !editorValue(node).isEmpty()) throw failure("POLL_EDITOR_CHANGED");
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            throw failure("POLL_TEXT_REJECTED");
        long deadline = SystemClock.elapsedRealtime() + 1500;
        while (true) {
            checkCancelled(screen);
            if (node.refresh() && expected.equals(editorValue(node))) return;
            if (SystemClock.elapsedRealtime() >= deadline)
                throw failure("POLL_READBACK_UNVERIFIED");
            Thread.sleep(80);
        }
    }

    private static void scrollForm(AccessibilityNodeInfo root, boolean forward) throws Exception {
        AccessibilityNodeInfo container = oneAny(root, "poll_creator_container");
        int action =
                forward
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        if (container.refresh() && container.isScrollable()) container.performAction(action);
    }

    private static String group(AccessibilityNodeInfo root, JSONObject intent) throws Exception {
        AccessibilityNodeInfo header = one(root, "conversation_contact", true),
                title = one(root, "conversation_contact_name", true),
                subtitle = one(root, "conversation_contact_status", true);
        if (!under(title, header)
                || !under(subtitle, header)
                || !intent.getString("groupName").equals(DiningPollUi.value(title.getText()))
                || DiningPollUi.value(subtitle.getText()).isEmpty())
            throw failure("POLL_GROUP_UNVERIFIED");
        return DiningPollUi.value(title.getText());
    }

    private static void emptyComposer(AccessibilityNodeInfo root, AccessibilityNodeInfo composer)
            throws Exception {
        List<AccessibilityNodeInfo> voices = nodes(root, "voice_note_btn", true);
        AccessibilityNodeInfo voice = voices.size() == 1 ? voices.get(0) : null;
        if (!composer.refresh() || voice != null && !voice.refresh())
            throw failure("POLL_COMPOSER_UNVERIFIED");
        CharSequence raw = composer.getText();
        DiningPollSendTarget.emptyComposer(
                raw == null ? "" : raw.toString(),
                composer.isShowingHintText(),
                nodes(root, "send", true).size(),
                voices.size(),
                voice == null ? "" : String.valueOf(voice.getClassName()),
                voice != null && voice.isEnabled());
    }

    private static AccessibilityNodeInfo form(
            Context c,
            UiAutomation ui,
            JSONObject screen,
            JSONObject intent,
            boolean partial,
            long deadline,
            String stage)
            throws Exception {
        AccessibilityNodeInfo root = awaitRoot(c, ui, screen, deadline, stage);
        JSONObject binding = intent.getJSONObject("binding");
        if (root.getWindowId() != binding.getInt("editorWindowId")
                || root.hashCode() != binding.getInt("editorRootId"))
            throw failure("POLL_EDITOR_TARGET_CHANGED");
        AccessibilityNodeInfo container = oneAny(root, "poll_creator_container"),
                question = oneAny(root, "poll_question_input");
        if (!under(question, container)) throw failure("POLL_FORM_UNVERIFIED");
        if (partial) return root;
        FrozenForm actual = frozenForm(root);
        DiningPollSendTarget.requireForm(
                actual.question(),
                actual.options(),
                actual.multiple(),
                DiningPollSendState.wireQuestion(intent),
                expectedOptions(intent));
        return root;
    }

    private record FrozenForm(String question, List<String> options, boolean multiple) {}

    private record Ready(AccessibilityNodeInfo root, AccessibilityNodeInfo send) {}

    private static List<String> expectedOptions(JSONObject intent) throws Exception {
        JSONArray expected = intent.getJSONArray("options");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < expected.length(); i++) values.add(expected.getString(i));
        return values;
    }

    private static FrozenForm frozenForm(AccessibilityNodeInfo root) throws Exception {
        AccessibilityNodeInfo container = oneAny(root, "poll_creator_container"),
                question = oneAny(root, "poll_question_input");
        if (!under(question, container)) throw failure("POLL_FORM_UNVERIFIED");
        AccessibilityNodeInfo list = oneAny(root, "poll_creator_options_recycler_view");
        List<AccessibilityNodeInfo> fields =
                new ArrayList<>(nodes(root, "poll_option_input", false));
        fields.sort(Comparator.comparingInt(n -> bounds(n).top));
        List<String> actualValues = new ArrayList<>();
        for (AccessibilityNodeInfo field : fields) {
            if (!under(field, list)
                    || !"android.widget.EditText".contentEquals(field.getClassName()))
                throw failure("POLL_OPTIONS_CHANGED");
            actualValues.add(editorValue(field));
        }
        AccessibilityNodeInfo modeRow = oneAny(root, "single_option_control_switch"),
                mode = oneAny(modeRow, "row_addon_end_switch_button");
        if (!under(mode, modeRow)
                || !"android.widget.Switch".contentEquals(mode.getClassName())
                || !mode.isCheckable()) throw failure("POLL_MODE_UNVERIFIED");
        return new FrozenForm(editorValue(question), List.copyOf(actualValues), mode.isChecked());
    }

    /** Two fresh equal readbacks are required before preparation or send admission. */
    private static Ready awaitReady(
            Context c,
            UiAutomation ui,
            JSONObject screen,
            JSONObject intent,
            long deadline,
            String stage,
            boolean requireSend)
            throws Exception {
        FrozenForm previous = null;
        AccessibilityNodeInfo previousRoot = null, previousSend = null;
        Rect previousBounds = null;
        Exception last = failure("POLL_FORM_NOT_READY__STAGE_" + stage);
        List<String> expected = expectedOptions(intent);
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                AccessibilityNodeInfo root = form(c, ui, screen, intent, true, deadline, stage);
                FrozenForm actual = frozenForm(root);
                if (DiningPollSendTarget.settledForm(
                        actual.question(),
                        actual.options(),
                        actual.multiple(),
                        DiningPollSendState.wireQuestion(intent),
                        expected)) {
                    AccessibilityNodeInfo send = null;
                    Rect box = null;
                    if (requireSend) {
                        send = one(root, "poll_create_button", true);
                        requireControl(
                                send,
                                "android.widget.ImageButton",
                                AccessibilityNodeInfo.ACTION_CLICK);
                        box = bounds(send);
                    }
                    boolean stable =
                            actual.equals(previous)
                                    && root.equals(previousRoot)
                                    && (!requireSend
                                            || send.equals(previousSend)
                                                    && box.equals(previousBounds));
                    if (stable) return new Ready(root, send);
                    previous = actual;
                    previousRoot = root;
                    previousSend = send;
                    previousBounds = box;
                } else {
                    previous = null;
                    previousRoot = null;
                    previousSend = null;
                    previousBounds = null;
                }
            } catch (IOException pending) {
                String code = pending.getMessage();
                if (code == null
                        || !code.startsWith("POLL_CONTROL_UNVERIFIED_")
                                && !code.startsWith("POLL_CONTROL_CHANGED__")) throw pending;
                last = pending;
                previous = null;
                previousRoot = null;
                previousSend = null;
                previousBounds = null;
            }
            Thread.sleep(100);
        }
        throw last;
    }

    /**
     * Accept a delivery indicator only on the unique outgoing single-choice poll with the frozen
     * identity.
     */
    private static JSONObject readSent(UiAutomation ui, JSONObject screen, JSONObject intent)
            throws Exception {
        return readSent(ui, screen, intent, false);
    }

    private static JSONObject readSent(
            UiAutomation ui, JSONObject screen, JSONObject intent, boolean includeCounts)
            throws Exception {
        AccessibilityNodeInfo root = DiningPollUi.root(ui, screen.getInt("displayId"), PACKAGE);
        group(root, intent);
        String expectedQuestion = DiningPollSendState.wireQuestion(intent);
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        for (AccessibilityNodeInfo poll : nodes(root, "polls_main_layout", true)) {
            AccessibilityNodeInfo question = one(poll, "poll_name", false);
            if (question != null && expectedQuestion.equals(DiningPollUi.value(question.getText())))
                matches.add(poll);
        }
        if (matches.size() != 1)
            throw failure(
                    matches.isEmpty()
                            ? "POLL_SEND_RECEIPT_UNATTRIBUTED"
                            : "POLL_SENT_MESSAGE_AMBIGUOUS");
        AccessibilityNodeInfo poll = matches.get(0);
        JSONArray options = new JSONArray();
        for (AccessibilityNodeInfo option : nodes(poll, "poll_option_main_layout", true))
            options.put(DiningPollUi.value(one(option, "poll_option_name", true).getText()));
        AccessibilityNodeInfo question = one(poll, "poll_name", true);
        if (!question.refresh()) throw failure("POLL_SEND_RECEIPT_UNATTRIBUTED");
        JSONObject identity =
                DiningPollSendEvidence.codedIdentity(
                        intent, DiningPollUi.value(question.getText()), options);
        AccessibilityNodeInfo mode = one(poll, "poll_type_text", true);
        if (!List.of("Select one", "选择一个", "单选").contains(DiningPollUi.value(mode.getText())))
            throw failure("POLL_MODE_UNVERIFIED");
        AccessibilityNodeInfo row = poll;
        for (int i = 0; i < 8 && row != null && !id(row, "conversation_row_text_poll"); i++)
            row = row.getParent();
        if (row == null || !id(row, "conversation_row_text_poll"))
            throw failure("POLL_SENT_MESSAGE_UNVERIFIED");
        AccessibilityNodeInfo status = one(row, "status", true);
        String delivery = DiningPollUi.value(status.getContentDescription());
        if (!List.of("Sent", "Delivered", "Read", "已发送", "已送达", "已读").contains(delivery))
            throw failure("POLL_SEND_DELIVERY_UNCONFIRMED");
        String timestamp = "";
        for (String name : List.of("date", "timestamp", "message_timestamp")) {
            AccessibilityNodeInfo node = one(row, name, false);
            if (node == null) continue;
            timestamp = DiningPollUi.value(node.getText());
            if (!timestamp.isEmpty()) break;
        }
        if (timestamp.isEmpty()) throw failure("POLL_TIMESTAMP_UNAVAILABLE");
        JSONObject receipt =
                new JSONObject()
                        .put("anchor", DiningPollSendEvidence.codedAnchor(intent, identity))
                        .put("timestamp", timestamp)
                        .put("delivery", delivery)
                        .put("groupName", intent.getString("groupName"))
                        .put("capturedAt", System.currentTimeMillis())
                        .put("messageIdentity", identity);
        if (includeCounts) {
            JSONArray counts = new JSONArray();
            for (AccessibilityNodeInfo option : nodes(poll, "poll_option_main_layout", true)) {
                String count =
                        DiningPollUi.value(one(option, "poll_option_vote_count", true).getText());
                if (!count.matches("[0-9]{1,5}")) throw failure("POLL_TALLY_INCOMPLETE");
                counts.put(Integer.parseInt(count));
            }
            AccessibilityNodeInfo subtitle = one(root, "conversation_contact_status", true);
            receipt.put(
                    "groupFingerprint",
                    DiningPollBookingFlow.key(
                            "group-ui",
                            intent.getString("groupName"),
                            DiningPollUi.value(subtitle.getText())));
            receipt.put("counts", counts);
        }
        return receipt;
    }

    static synchronized void recordFailure(Context context, JSONObject args, String code) {
        try {
            JSONObject intent = require(context, DiningPollSendState.uuid(args, "intentId"));
            if (!"sent".equals(intent.optString("status")))
                save(context, intent.put("lastError", code));
        } catch (Exception ignored) {
        }
    }

    private static List<AccessibilityNodeInfo> nodes(
            AccessibilityNodeInfo root, String name, boolean visible) {
        return root.findAccessibilityNodeInfosByViewId(ID + name).stream()
                .filter(
                        n ->
                                n.getWindowId() == root.getWindowId()
                                        && PACKAGE.contentEquals(n.getPackageName())
                                        && (!visible || n.isVisibleToUser()))
                .toList();
    }

    private static AccessibilityNodeInfo one(
            AccessibilityNodeInfo root, String name, boolean required) throws Exception {
        List<AccessibilityNodeInfo> found = nodes(root, name, true);
        if (found.size() > 1 || required && found.isEmpty())
            throw failure("POLL_CONTROL_UNVERIFIED_" + name.toUpperCase(Locale.ROOT));
        return found.isEmpty() ? null : found.get(0);
    }

    private static AccessibilityNodeInfo oneAny(AccessibilityNodeInfo root, String name)
            throws Exception {
        List<AccessibilityNodeInfo> found = nodes(root, name, false);
        if (found.size() != 1)
            throw failure("POLL_CONTROL_UNVERIFIED_" + name.toUpperCase(Locale.ROOT));
        return found.get(0);
    }

    private static void checkCancelled(JSONObject screen) throws Exception {
        if (Thread.currentThread().isInterrupted()
                || screen.has("cancelPath") && new File(screen.getString("cancelPath")).exists())
            throw failure("POLL_INPUT_CANCELLED");
    }

    static void acknowledgeCleanup(JSONObject args) throws Exception {
        if (!DiningPollSendAutomation.cleanupConfirmed())
            throw failure("POLL_AUTOMATION_CLEANUP_UNCONFIRMED");
        String token = DiningPollSendState.uuid(args, "operationToken");
        File cleanup = DiningPollSendState.cleanupTarget(args);
        try (FileOutputStream output = new FileOutputStream(cleanup)) {
            output.write(
                    new JSONObject()
                            .put("operationToken", token)
                            .put("cleanupConfirmed", true)
                            .toString()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static boolean id(AccessibilityNodeInfo node, String name) {
        return (ID + name).equals(node.getViewIdResourceName());
    }

    private static String editorValue(AccessibilityNodeInfo node) {
        CharSequence raw = node.getText();
        return node.isShowingHintText() || raw == null ? "" : raw.toString();
    }

    private static boolean under(AccessibilityNodeInfo node, AccessibilityNodeInfo parent) {
        for (int i = 0; i < 24 && node != null; i++, node = node.getParent())
            if (node.equals(parent)) return true;
        return false;
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return rect;
    }

    private static void requireControl(AccessibilityNodeInfo node, String type, int action)
            throws Exception {
        if (node == null) throw failure("POLL_CONTROL_CHANGED__MISSING");
        boolean refreshed = node.refresh();
        String actualClass = String.valueOf(node.getClassName());
        try {
            DiningPollSendTarget.control(
                    refreshed,
                    node.isVisibleToUser(),
                    node.isEnabled(),
                    actualClass,
                    type,
                    node.isClickable(),
                    node.getActionList().stream().anyMatch(a -> a.getId() == action),
                    action);
        } catch (IOException changed) {
            throw failure(
                    changed.getMessage()
                            + "__VIEW_"
                            + String.valueOf(node.getViewIdResourceName())
                            + "__CLASS_"
                            + actualClass
                            + "__REFRESH_"
                            + refreshed);
        }
    }

    private static void click(AccessibilityNodeInfo node, String type) throws Exception {
        requireControl(node, type, AccessibilityNodeInfo.ACTION_CLICK);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            throw failure("POLL_NAVIGATION_REJECTED");
    }
}
