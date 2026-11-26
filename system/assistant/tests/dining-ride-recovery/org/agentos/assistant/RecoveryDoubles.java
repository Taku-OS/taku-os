package org.agentos.assistant;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Observable native reads only: no submit, thread creation or task publication exists here. */
final class NativeClient {
    static final List<JSONObject> states = new ArrayList<>();
    static final List<String> calls = new ArrayList<>();
    static String expectedThread;
    static JSONObject readResult;
    static IOException readFailure;
    private static int stateIndex;

    static void reset(String thread, JSONObject... snapshots) {
        expectedThread = thread;
        states.clear();
        calls.clear();
        for (JSONObject snapshot : snapshots) states.add(new JSONObject(snapshot.toString()));
        stateIndex = 0;
        readResult = new JSONObject().put("thread", new JSONObject().put("id", thread));
        readFailure = null;
    }

    static JSONObject state(String thread) {
        if (!expectedThread.equals(thread))
            throw new AssertionError("Read another thread: " + thread);
        calls.add("state:" + thread);
        if (stateIndex >= states.size())
            throw new AssertionError("Unexpected additional native state read");
        return new JSONObject(states.get(stateIndex++).toString());
    }

    static JSONObject rpcSync(String method, JSONObject args) throws IOException {
        if (!"thread/read".equals(method)
                || args.length() != 2
                || !expectedThread.equals(args.optString("threadId"))
                || !Boolean.FALSE.equals(args.opt("includeTurns"))) {
            throw new AssertionError("Unexpected native operation: " + method + " " + args);
        }
        calls.add(method + ":" + expectedThread);
        if (readFailure != null) throw readFailure;
        return new JSONObject(readResult.toString());
    }
}

final class TaskRuntime {
    static File ROOT;
    static File TASKS;
}

final class PanelProtocol {
    static void exact(JSONObject value, Set<String> allowed) throws IOException {
        for (var keys = value.keys(); keys.hasNext(); ) {
            if (!allowed.contains(keys.next())) throw new IOException("INVALID_ARGUMENT");
        }
    }
}
