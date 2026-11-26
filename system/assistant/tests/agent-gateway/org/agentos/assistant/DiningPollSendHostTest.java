package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DiningPollSendHostTest {
    private static final String THREAD = "00000000-0000-4000-8000-000000000001";
    private static final String INTENT = "f132ee20-4133-42c9-8000-000000000002";
    private static final String HASH = "a".repeat(64);
    private static final String GROUP = "Dinner test group";

    public static void main(String[] args) throws Exception {
        completedReplyOwnsPollOptions();
        sendAttemptCanNeverBeReplayed();
        verifiedGroupSearchRejectsAnOffscreenDuplicate();
        pollSubmissionIsScopedAndCarriesNoScreenshot();
        pollOperationsRequireRootScope();
        operationLockQuiescesDuplicateInput();
        System.out.println("PASS DiningPollSendHostTest");
    }

    private static JSONObject registration() throws Exception {
        JSONObject source =
                new JSONObject()
                        .put("kind", "primary")
                        .put("replyId", "dining")
                        .put("turnId", "turn")
                        .put("revision", 2)
                        .put("contractHash", HASH);
        return DiningPollSendState.registration(
                new JSONObject()
                        .put("intentId", INTENT)
                        .put("sourceThreadId", THREAD)
                        .put("sourceReply", source)
                        .put("groupName", GROUP)
                        .put("question", "Where should we eat?")
                        .put("options", new JSONArray(List.of("Restaurant A", "Restaurant B"))));
    }

    private static JSONObject reply(String phase, List<String> choices) {
        JSONArray rows = new JSONArray();
        for (String choice : choices) rows.put(new JSONObject().put("label", choice));
        JSONObject block = new JSONObject().put("kind", "collection").put("items", rows);
        JSONObject card =
                new JSONObject()
                        .put(
                                "request",
                                new JSONObject()
                                        .put("key", "dining")
                                        .put("mode", "card")
                                        .put("blocks", new JSONArray().put(block)));
        JSONObject provenance =
                new JSONObject()
                        .put("kind", "primary-assistant-reply")
                        .put("threadId", THREAD)
                        .put("turnId", "turn")
                        .put("contractHash", HASH);
        JSONObject reply =
                new JSONObject()
                        .put("id", "dining")
                        .put("key", "dining")
                        .put("revision", 2)
                        .put("schema", "taku.main-reply/1")
                        .put("provenance", provenance)
                        .put("lifecycle", new JSONObject().put("phase", phase))
                        .put("card", card);
        return new JSONObject().put("ok", true).put("reply", reply);
    }

    private static void completedReplyOwnsPollOptions() throws Exception {
        JSONObject registration = registration();
        check(
                DiningPollSendSource.readRequest(registration).getString("threadId").equals(THREAD),
                "source thread is owned");
        DiningPollSendSource.verify(
                registration, reply("completed", List.of("Restaurant A", "Restaurant B")));
        rejects(
                "POLL_SOURCE_REPLY_CHANGED",
                () ->
                        DiningPollSendSource.verify(
                                registration,
                                reply("streaming", List.of("Restaurant A", "Restaurant B"))));
        rejects(
                "POLL_SOURCE_OPTIONS_CHANGED",
                () ->
                        DiningPollSendSource.verify(
                                registration,
                                reply("completed", List.of("Restaurant B", "Restaurant A"))));
        rejects(
                "POLL_SOURCE_REPLY_UNAVAILABLE",
                () -> DiningPollSendSource.verify(registration, new JSONObject().put("ok", false)));
    }

    private static void sendAttemptCanNeverBeReplayed() throws Exception {
        JSONObject bound =
                DiningPollSendState.withQuestionCode(registration(), new JSONObject())
                        .put("status", "bound");
        String question = DiningPollSendState.wireQuestion(bound);
        JSONObject attempted = DiningPollSendState.beforeClick(bound, 500L);
        check(
                !DiningPollSendState.mayClick(new JSONObject(attempted.toString())),
                "an uncertain persisted send cannot click again");
        rejects("POLL_SEND_NOT_REPLAYABLE", () -> DiningPollSendState.beforeClick(attempted, 501L));
        rejects(
                "POLL_SEND_RECEIPT_UNATTRIBUTED",
                () ->
                        DiningPollSendEvidence.codedIdentity(
                                attempted,
                                bound.getString("question"),
                                bound.getJSONArray("options")));
        JSONObject receipt =
                DiningPollSendEvidence.codedIdentity(
                        attempted, question, bound.getJSONArray("options"));
        String anchor = DiningPollSendEvidence.codedAnchor(attempted, receipt);
        JSONObject sent = DiningPollSendState.confirmed(attempted, anchor, 502L);
        check(
                sent.getBoolean("sent") && anchor.equals(sent.getString("anchor")),
                "only native evidence can seal an attempt");
        rejects(
                "POLL_MESSAGE_CHANGED",
                () -> DiningPollSendState.confirmed(sent, "another-message", 503L));
    }

    private static void verifiedGroupSearchRejectsAnOffscreenDuplicate() throws Exception {
        DiningPollGroupSearch.Census scan = new DiningPollGroupSearch.Census(GROUP);
        var title =
                new DiningPollGroupSearch.Item(
                        0, DiningPollGroupSearch.Kind.CHATS, "", "title", true);
        var first =
                new DiningPollGroupSearch.Item(
                        1, DiningPollGroupSearch.Kind.CHAT, GROUP, "first", true);
        var duplicate =
                new DiningPollGroupSearch.Item(
                        2, DiningPollGroupSearch.Kind.CHAT, GROUP, "second", true);
        check(
                scan.accept(
                                new DiningPollGroupSearch.Page(
                                        GROUP, 1, 3, false, true, List.of(title, first)))
                        == DiningPollGroupSearch.Move.FORWARD,
                "the first matching row does not prove uniqueness");
        rejects(
                "POLL_GROUP_AMBIGUOUS",
                () ->
                        scan.accept(
                                new DiningPollGroupSearch.Page(
                                        GROUP, 1, 3, true, false, List.of(first, duplicate))));
    }

    private static void pollSubmissionIsScopedAndCarriesNoScreenshot() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putBoolean("includeScreenshot", true).commit();
        String revision =
                ComposerState.write(prefs, THREAD, "", "Send the selected poll")
                        .getString("revision");
        JSONObject ordinary =
                new JSONObject()
                        .put("threadId", THREAD)
                        .put("requestId", INTENT)
                        .put("revision", revision)
                        .put("action", "sendMessage")
                        .put("attachmentIds", new JSONArray());
        JSONObject poll =
                new JSONObject(ordinary.toString())
                        .put("pollSendIntentId", INTENT)
                        .put("includeInvocationScreenshot", false);
        rejects(
                "INVALID_ARGUMENT",
                () ->
                        AgentGatewayProtocol.request(
                                "composer/submit",
                                new JSONObject(poll.toString())
                                        .put("includeInvocationScreenshot", true)
                                        .toString()));
        rejects(
                "INVALID_ARGUMENT",
                () ->
                        AgentGatewayProtocol.request(
                                "composer/submit",
                                new JSONObject(poll.toString())
                                        .put("attachmentIds", new JSONArray().put(INTENT))
                                        .toString()));
        JSONObject input = AgentGatewayProtocol.request("composer/submit", poll.toString());
        JSONObject options =
                ComposerState.INSTANCE
                        .reserveSubmission(prefs, input, (thread, output) -> kotlin.Unit.INSTANCE)
                        .getFirst()
                        .getJSONObject("options");
        check(
                !options.getBoolean("includeScreenshot")
                        && INTENT.equals(options.getString("pollSendIntentId")),
                "the persisted poll intent carries the authorization but no invocation screenshot");
        check(
                prefs.getBoolean("includeScreenshot", false),
                "the poll leaves the shared screenshot preference alone");
    }

    private static void pollOperationsRequireRootScope() {
        for (String method :
                List.of(
                        "send-register",
                        "send-status",
                        "send-bind",
                        "send",
                        "send-inspect",
                        "send-native-v1")) {
            String route = "dining/poll/" + method;
            check(
                    "PERMISSION_DENIED"
                            .equals(AgentOpenAccessPolicy.denial(route, "taku.control"::equals)),
                    "general control scope cannot reach the poll journal or submit input");
            check(
                    AgentOpenAccessPolicy.denial(route, "taku.root"::equals) == null,
                    "the provider applies its root scope gate");
        }
    }

    private static void operationLockQuiescesDuplicateInput() throws Exception {
        Path path = Files.createTempFile("dining-poll-operation", ".lock");
        try {
            try (var held = DiningPollOperationLock.acquire(path)) {
                rejects("POLL_OPERATION_BUSY", () -> DiningPollOperationLock.acquire(path));
            }
            try (var released = DiningPollOperationLock.acquire(path)) {
                check(
                        released != null,
                        "a completed callback releases the lock for broker recovery");
            }
        } finally {
            Files.delete(path);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void rejects(String code, Action action) throws Exception {
        try {
            action.run();
        } catch (IOException failure) {
            check(
                    code.equals(failure.getMessage()),
                    "Expected " + code + ", got " + failure.getMessage());
            return;
        }
        throw new AssertionError("Expected " + code);
    }
}
