package org.agentos.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;
import org.json.*;

/** Real status mapping and durable request client; no Android rendering or model claims. */
public final class WorkspaceUiLogicTest {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    private static String label(int resource) { return "resource:" + resource; }
    private static JSONObject screen() throws Exception {
        return new JSONObject().put("threadId", "00000000-0000-4000-8000-000000000001")
                .put("screenKey", "task-0123456789abcdef0123")
                .put("generation", "00000000-0000-4000-8000-000000000002")
                .put("controlRevision", 3).put("controlOwner", "user").put("resumeRequired", true);
    }
    private static void waitingAppLabels() throws Exception {
        JSONObject queued = screen().put("controlOwner", "agent").put("resumeRequired", false)
                .put("status", "waiting_for_app").put("activeTurnId", "running-but-queued")
                .put("waitReason", new JSONObject().put("text", "正在等待时钟"));
        check(WorkspaceUi.status(queued, WorkspaceUiLogicTest::label).equals("正在等待时钟"), "queued task displays its actual App wait reason, even with an active turn");
        queued.remove("waitReason");
        check(WorkspaceUi.status(queued, WorkspaceUiLogicTest::label).equals(label(R.string.workspace_status_waiting_app)), "missing wait reason still means waiting for an App");
        queued.put("waitReason", new JSONObject().put("text", "  "));
        check(WorkspaceUi.status(queued, WorkspaceUiLogicTest::label).equals(label(R.string.workspace_status_waiting_app)), "blank reason does not become Ready or waiting for a person");
        for (String owner : List.of("user", "paused")) {
            queued.put("controlOwner", owner);
            int expected = owner.equals("user") ? R.string.workspace_status_user : R.string.workspace_status_paused;
            check(WorkspaceUi.status(queued, WorkspaceUiLogicTest::label).equals(label(expected)), "takeover/pause keeps display priority over App wait");
        }
        queued.put("controlOwner", "agent").put("resumeRequired", true);
        check(WorkspaceUi.status(queued, WorkspaceUiLogicTest::label).equals(label(R.string.workspace_status_resume)), "required return keeps display priority");
        queued.put("status", "cleanup_failed");
        check(WorkspaceUi.status(queued, WorkspaceUiLogicTest::label).equals(label(R.string.workspace_cleanup_failed)), "cleanup failure keeps display priority");
        check(WorkspaceUi.status(new JSONObject().put("status", "waiting_for_user"), WorkspaceUiLogicTest::label).equals(label(R.string.workspace_status_waiting)), "waiting for a person is unchanged");
        check(WorkspaceUi.status(new JSONObject().put("status", "running"), WorkspaceUiLogicTest::label).equals(label(R.string.workspace_status_working)), "ordinary running state is unchanged");
        check(WorkspaceUi.status(new JSONObject().put("status", "ready"), WorkspaceUiLogicTest::label).equals(label(R.string.workspace_status_ready)), "ordinary ready state is unchanged");
    }
    private static final class Preferences extends Context {
        private final Map<String, String> values = new HashMap<>();
        private final SharedPreferences store = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getString")) return values.getOrDefault((String) args[0], (String) args[1]);
                    if (method.getName().equals("getAll")) return new HashMap<>(values);
                    if (method.getName().equals("edit")) return editor();
                    throw new AssertionError("Unexpected preference operation: " + method.getName());
                });
        private SharedPreferences.Editor editor() {
            Map<String, String> changed = new HashMap<>();
            Set<String> removed = new HashSet<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("putString")) { removed.remove((String) args[0]); changed.put((String) args[0], (String) args[1]); return proxy; }
                        if (method.getName().equals("remove")) { changed.remove((String) args[0]); removed.add((String) args[0]); return proxy; }
                        if (method.getName().equals("commit")) { removed.forEach(values::remove); values.putAll(changed); return true; }
                        throw new AssertionError("Unexpected preference edit: " + method.getName());
                    });
        }
        public SharedPreferences getSharedPreferences(String name, int mode) { return store; }
    }
    private static final class Broker implements WorkspaceExecutionRequest.Transport {
        final List<JSONObject> calls = new ArrayList<>();
        final JSONObject current;
        String id = "", status = "pending";
        NativeClient.Callback acknowledgement;
        Broker(JSONObject current) { this.current = current; }
        JSONObject receipt() throws Exception {
            return new JSONObject().put("requestId", id).put("status", status)
                    .put("requiresReconciliation", status.equals("unknown"));
        }
        public void send(JSONObject request, NativeClient.Callback callback) {
            try {
                calls.add(new JSONObject(request.toString()));
                String action = request.getString("action");
                if (action.equals("list")) callback.ready(new JSONObject().put("screens", new JSONArray().put(current)));
                else if (action.equals("executionStatus")) {
                    check(request.getString("requestId").equals(id) && WorkspaceUi.same(current, request), "status query retains exact screen and original request ID");
                    callback.ready(receipt());
                } else {
                    check(action.equals("returnAndResume"), "client sends the supported return action");
                    check(WorkspaceUi.same(current, request) && request.getLong("controlRevision") == current.getLong("controlRevision"), "return uses a freshly read exact screen/revision");
                    id = request.getString("requestId"); acknowledgement = callback;
                }
            } catch (Exception error) { throw new AssertionError(error); }
        }
        long returns() { return calls.stream().filter(c -> c.optString("action").equals("returnAndResume")).count(); }
    }
    private static void returnReceipts() throws Exception {
        for (String owner : List.of("user", "paused")) {
            JSONObject current = screen().put("controlOwner", owner); Preferences context = new Preferences(); Broker broker = new Broker(current);
            List<JSONObject> seen = new ArrayList<>();
            WorkspaceExecutionRequest first = new WorkspaceExecutionRequest(context, current, "returnAndResume", seen::add, broker);
            first.start(); first.submit(""); String original = broker.id;
            check(first.busy() && broker.returns() == 1, "return remains pending before the native acknowledgement");
            first.submit(""); first.submit("");
            check(broker.returns() == 1, "repeated clicks query the same request instead of returning twice");
            first.stop(); broker.status = "unknown";
            WorkspaceExecutionRequest reopened = new WorkspaceExecutionRequest(context, current, "returnAndResume", seen::add, broker);
            reopened.start(); reopened.submit("");
            check(reopened.busy() && broker.returns() == 1 && broker.id.equals(original), "recreated page preserves uncertain delivery and cannot resend");
            broker.status = "accepted"; broker.acknowledgement.ready(broker.receipt()); reopened.refresh();
            check(!reopened.busy() && seen.get(seen.size() - 1).optString("status").equals("accepted"), "late acknowledgement resolves the stored request without claiming task completion");
            check(broker.returns() == 1, "late acknowledgement creates no second return"); reopened.stop();
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("status")) waitingAppLabels();
        if (args.length == 0 || args[0].equals("return")) returnReceipts();
        System.out.println("WorkspaceUiLogicTest: " + checks + " checks passed (host logic only; no device rendering/click acceptance)");
    }
}
