package org.agentos.assistant

import java.io.IOException
import java.util.HashSet
import java.util.Locale
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/** The public UI capability list. No arbitrary RPC, file path, command, or model credentials. */
internal class AgentGatewayProtocol private constructor() {
    fun interface NativeRead {
        @Throws(Exception::class)
        fun read(request: JSONObject): JSONObject
    }
    companion object {
        const val AUTHORITY = "org.agentos.assistant.agent-gateway"
        const val HOME_WORKSPACES_URI = "content://$AUTHORITY/workspaces/home"
        const val PERMISSION = "org.agentos.permission.AGENT_CLIENT"
        const val CLIENT_PACKAGE = "org.agentos.agent"
        const val MAX_REQUEST = 48_000
        @JvmField val ACTIONS: Set<String> = java.util.Set.of("listThreads", "readSession", "createThread",
            "resumeThread", "sendMessage", "steer", "interrupt", "answer", "getWorkspaces",
            "openWorkspace", "openSettings", "cancelQueued", "openNativeConversation", "subscribeSession", "unsubscribeSession",
            "decide", "generateUi", "mainReply", "panels", "cardView", "readMatter", "subscribeMatter", "unsubscribeMatter", "dispatchMatter", "analyzeCameraFrame", "audioCapabilities", "audioCommand",
            "readSystemAi", "setSystemAi", "bindSystemAi", "bindSystemAiModel", "reorderSystemAi", "restoreSystemAi", "deleteSystemAiGroup", "openAssistantConnection", "openMagpieManagement", "pluginManager",
            "readAppPermissions", "setAppPermissionMode", "setAppPermissionScope", "resetAppPermission")
        @JvmField val QUESTIONS: Set<String> = java.util.Set.of("phone/askUser", "phone/confirmAction",
            "phone/action/requestApproval", "item/tool/requestUserInput")
        private val SECRET_KEYS: Set<String> = java.util.Set.of("accesstoken", "refreshtoken", "idtoken",
            "apikey", "authorization", "password", "authurl", "encryptedcontent")

        @JvmStatic
        @Throws(Exception::class)
        fun matterIdentity(matterId: String): Array<String> {
            val parts = (matterId as java.lang.String).split("/", -1)
            if (parts.size != 2) throw IOException("INVALID_MATTER_ID")
            requireId(JSONObject().put("threadId", parts[0]), "threadId")
            requireKey(parts[1])
            return parts
        }

        @JvmStatic
        @Throws(Exception::class)
        fun request(action: String, json: String?): JSONObject {
            if (!ACTIONS.contains(action)) throw IOException("UNSUPPORTED_ACTION")
            val jsonText = json ?: "{}"
            if (jsonText.length > limit(action)) throw IOException("REQUEST_TOO_LARGE")
            val input = JSONObject(jsonText)
            val fields: Set<String> = when (action) {
                "readSession" -> java.util.Set.of("threadId", "refresh", "statusOnly")
                "mainReply" -> java.util.Set.of("action", "threadId", "turnId", "replyId")
                "subscribeSession" -> java.util.Set.of("threadId", "brokerEpoch", "afterSeq")
                "unsubscribeSession" -> java.util.Set.of("subscriptionId")
                "readMatter" -> java.util.Set.of("matterId")
                "subscribeMatter" -> java.util.Set.of("matterIds", "cursor")
                "unsubscribeMatter" -> java.util.Set.of("subscriptionId")
                "dispatchMatter" -> java.util.Set.of("matterId", "requestId", "actionRef", "expectedRevision", "values")
                "analyzeCameraFrame" -> java.util.Set.of("image", "language", "instruction")
                "audioCapabilities" -> java.util.Set.of()
                "audioCommand" -> java.util.Set.of("matterId", "requestId", "operation", "sessionId", "purpose", "language", "profileId", "targetLanguage", "targetPackage", "recordAudio")
                "getWorkspaces" -> if (input.has("scope")) java.util.Set.of("scope") else java.util.Set.of("threadId")
                "resumeThread", "openNativeConversation" -> java.util.Set.of("threadId")
                "sendMessage" -> java.util.Set.of("threadId", "text", "requestId")
                "steer" -> java.util.Set.of("threadId", "text", "expectedTurnId", "requestId")
                "interrupt" -> java.util.Set.of("threadId", "turnId")
                "cancelQueued" -> java.util.Set.of("threadId", "taskId")
                "answer" -> java.util.Set.of("threadId", "key", "response")
                "openWorkspace" -> java.util.Set.of("threadId", "screenKey", "generation", "source")
                "openSettings" -> java.util.Set.of("page")
                "pluginManager" -> java.util.Set.of("operation", "argument", "name", "arguments")
                "decide" -> java.util.Set.of("state", "questions")
                "generateUi" -> java.util.Set.of("requestId", "instructions", "prompt", "catalogVersion")
                "panels", "cardView" -> java.util.Set.of("operation", "args")
                "setSystemAi" -> java.util.Set.of("enabled")
                "bindSystemAi" -> java.util.Set.of("scene", "group")
                "bindSystemAiModel" -> java.util.Set.of("scene", "model")
                "reorderSystemAi" -> java.util.Set.of("group", "members")
                "restoreSystemAi" -> java.util.Set.of("undo")
                "deleteSystemAiGroup" -> java.util.Set.of("group")
                "readAppPermissions" -> java.util.Set.of("query")
                "setAppPermissionMode" -> java.util.Set.of("mode", "allowRootInYolo")
                "setAppPermissionScope" -> java.util.Set.of("packageName", "scope", "allowed")
                "resetAppPermission" -> java.util.Set.of("packageName")
                else -> java.util.Set.of()
            }
            val keys = input.keys()
            while (keys.hasNext()) if (!fields.contains(keys.next())) throw IOException("UNSUPPORTED_PARAMETER")
            if (fields.contains("threadId")) requireId(input, "threadId")
            if (fields.contains("scope") && input.get("scope") != "home") throw IOException("INVALID_WORKSPACE_SCOPE")
            if (fields.contains("text")) text(input, "text", 16000)
            if (fields.contains("requestId")) requireId(input, "requestId")
            if (fields.contains("subscriptionId")) requireId(input, "subscriptionId")
            if (fields.contains("taskId") && !Pattern.matches("[0-9]{1,20}", text(input, "taskId", 20))) throw IOException("INVALID_TASK_ID")
            if (fields.contains("expectedTurnId")) text(input, "expectedTurnId", 160)
            if (fields.contains("turnId")) text(input, "turnId", 160)
            if (action == "answer") {
                requireId(input, "key")
                input.getJSONObject("response")
            }
            if (action == "openWorkspace") {
                if (!Pattern.matches("task-[a-f0-9]{20}", text(input, "screenKey", 64))) throw IOException("INVALID_SCREEN_KEY")
                requireId(input, "generation")
                if (input.has("source") && input.get("source") != "home") throw IOException("INVALID_WORKSPACE_SOURCE")
            }
            if (action == "decide") decision(input)
            if (action == "generateUi") {
                text(input, "instructions", 60_000)
                text(input, "prompt", 16_000)
                if (!Pattern.matches("[a-z0-9.-]{1,32}", text(input, "catalogVersion", 32))) throw IOException("INVALID_catalogVersion")
            }
            if (action == "analyzeCameraFrame") {
                val image = text(input, "image", 300_000)
                if (!Pattern.matches("data:image/(jpeg|png);base64,[A-Za-z0-9+/=]+", image)) throw IOException("INVALID_CAMERA_FRAME")
                if (!java.util.Set.of("zh", "en").contains(text(input, "language", 2))) throw IOException("INVALID_LANGUAGE")
                if (input.has("instruction")) text(input, "instruction", 1000)
            }
            if (action == "panels" || action == "cardView") {
                text(input, "operation", 32)
                input.getJSONObject("args")
            }
            if (action == "mainReply") {
                if (input.optString("action") != "read") throw IOException("INVALID_ARGUMENT")
                if (input.has("replyId")) text(input, "replyId", 160)
            }
            if (action == "audioCommand") {
                val operation = text(input, "operation", 16)
                if (!java.util.Set.of("start", "pause", "resume", "stop", "status", "summarize").contains(operation)) throw IOException("UNSUPPORTED_AUDIO_OPERATION")
                if (operation == "start") {
                    if (!java.util.Set.of("DICTATION", "MEDIA_TRANSLATE").contains(text(input, "purpose", 32))) throw IOException("UNSUPPORTED_AUDIO_PURPOSE")
                    if (input.has("sessionId")) throw IOException("UNSUPPORTED_PARAMETER")
                    if (input.getString("purpose") == "MEDIA_TRANSLATE") text(input, "targetLanguage", 200)
                    for (field in arrayOf("language", "profileId", "targetLanguage", "targetPackage")) if (input.has(field)) text(input, field, 200)
                    if (input.has("recordAudio") && input.get("recordAudio") !is Boolean) throw IOException("INVALID_RECORD_AUDIO")
                } else {
                    requireId(input, "sessionId")
                    if (operation == "summarize" && input.has("language")) text(input, "language", 80)
                    for (field in arrayOf("purpose", "profileId", "targetLanguage", "targetPackage", "recordAudio")) if (input.has(field)) throw IOException("UNSUPPORTED_PARAMETER")
                    if (operation != "summarize" && input.has("language")) throw IOException("UNSUPPORTED_PARAMETER")
                }
            }
            if (fields.contains("matterId")) matterIdentity(text(input, "matterId", 101))
            if (action == "dispatchMatter") {
                text(input, "actionRef", 200)
                nonnegativeInteger(input, "expectedRevision")
                input.getJSONObject("values")
            }
            if (action == "subscribeMatter") {
                val ids = input.getJSONArray("matterIds")
                if (ids.length() < 1 || ids.length() > 32) throw IOException("INVALID_MATTER_IDS")
                val unique = HashSet<String>()
                for (i in 0 until ids.length()) {
                    if (ids.get(i) !is String || !unique.add(ids.getString(i))) throw IOException("INVALID_MATTER_IDS")
                    matterIdentity(ids.getString(i))
                }
                if (input.has("cursor")) {
                    val cursor = input.getJSONObject("cursor")
                    if (cursor.length() != 2) throw IOException("INVALID_CURSOR")
                    requireId(cursor, "runtimeEpoch")
                    nonnegativeInteger(cursor, "afterSeq")
                }
            }
            if (action == "openSettings" && !java.util.Set.of("models", "system", "workspaces").contains(input.optString("page", "models")))
                throw IOException("INVALID_SETTINGS_PAGE")
            if (action == "pluginManager") {
                val operation = text(input, "operation", 64)
                if (!java.util.Set.of("ping", "list", "show", "install", "remove", "enable", "disable", "reload",
                        "tools", "tool", "checks", "run-check", "call-tool", "evidence", "local").contains(operation))
                    throw IOException("UNSUPPORTED_PLUGIN_OPERATION")
                if (input.has("argument") && text(input, "argument", 4096).isEmpty()) throw IOException("INVALID_argument")
                if (input.has("name") && text(input, "name", 256).isEmpty()) throw IOException("INVALID_name")
                if (input.has("arguments") && input.get("arguments") !is JSONObject) throw IOException("INVALID_arguments")
            }
            if (input.has("refresh") && input.get("refresh") !is Boolean) throw IOException("INVALID_REFRESH")
            if (input.has("statusOnly") && (input.get("statusOnly") !is Boolean || input.optBoolean("statusOnly") && input.optBoolean("refresh")))
                throw IOException("INVALID_STATUS_ONLY")
            if (action == "subscribeSession") {
                if (input.has("brokerEpoch") != input.has("afterSeq")) throw IOException("INVALID_CURSOR")
                if (input.has("brokerEpoch")) {
                    requireId(input, "brokerEpoch")
                    val seq = input.get("afterSeq")
                    if (seq !is Number || seq.toDouble() != seq.toLong().toDouble() || seq.toLong() < 0 || seq.toLong() > 9_007_199_254_740_991L)
                        throw IOException("INVALID_CURSOR")
                }
            }
            return input
        }

        @Throws(Exception::class)
        private fun nonnegativeInteger(input: JSONObject, key: String) {
            val value = input.get(key)
            if (value !is Number || value.toDouble() != value.toLong().toDouble() || value.toLong() < 0 || value.toLong() > 9_007_199_254_740_991L)
                throw IOException("INVALID_" + key.uppercase(Locale.ROOT))
        }

        /** Strict event surface with no arbitrary app-server notifications/auth/server requests. */
        @JvmStatic
        @Throws(Exception::class)
        fun sessionEvent(source: JSONObject, thread: String): JSONObject {
            if ("taku.session-events/1" != source.optString("schema") || thread != source.optString("threadId")) throw IOException("INVALID_STREAM_EVENT")
            requireId(source, "brokerEpoch")
            val sequence = source.get("seq")
            if (sequence !is Number || sequence.toLong() < 0 || sequence.toDouble() != sequence.toLong().toDouble() || sequence.toLong() > 9_007_199_254_740_991L)
                throw IOException("INVALID_STREAM_EVENT")
            val method = source.getString("method")
            val fields: Set<String> = when (method) {
                "session/snapshot" -> java.util.Set.of("thread")
                "session/reply" -> java.util.Set.of("turnId", "reply", "items")
                "item/agentMessage/delta" -> java.util.Set.of("turnId", "itemId", "delta")
                "item/started", "item/completed" -> java.util.Set.of("turnId", "item")
                "turn/started", "turn/completed" -> java.util.Set.of("turn")
                "thread/name/updated" -> java.util.Set.of("threadName", "name")
                "thread/status/changed" -> java.util.Set.of("status")
                "session/resync" -> java.util.Set.of("reason")
                "session/ready", "session/heartbeat" -> java.util.Set.of()
                else -> throw IOException("UNSUPPORTED_STREAM_EVENT")
            }
            val params = source.getJSONObject("params")
            val visible = JSONObject()
            if (method == "session/snapshot" && thread != params.getJSONObject("thread").optString("id")) throw IOException("THREAD_ID_MISMATCH")
            if (method == "session/reply") {
                if (params.length() != 3) throw IOException("INVALID_STREAM_EVENT")
                val turn = text(params, "turnId", 160)
                mainReply(params.getJSONObject("reply"), thread, turn)
                params.getJSONArray("items")
            }
            for (key in fields) {
                if (!params.has(key)) continue
                val value = when {
                    method == "session/reply" && key == "reply" -> params.getJSONObject(key)
                    method == "session/snapshot" && key == "thread" -> threadView(params.getJSONObject(key))
                    key == "turn" && (method == "turn/started" || method == "turn/completed") -> {
                        val turn = params.getJSONObject(key)
                        val projected = clean(turn, 0) as JSONObject
                        preserveReplies(turn, projected, thread)
                        projected
                    }
                    else -> clean(params.get(key), 0)
                }
                visible.put(key, value)
            }
            return JSONObject().put("schema", "taku.session-events/1").put("threadId", thread)
                .put("brokerEpoch", source.getString("brokerEpoch")).put("seq", source.getLong("seq"))
                .put("method", method).put("params", visible)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun threadView(thread: JSONObject): JSONObject {
            val projected = clean(thread, 0) as JSONObject
            val turns = thread.optJSONArray("turns") ?: return projected
            val visible = projected.getJSONArray("turns")
            for (i in 0 until turns.length()) preserveReplies(turns.getJSONObject(i), visible.getJSONObject(i), thread.getString("id"))
            return projected
        }

        private fun preserveReplies(turn: JSONObject, projected: JSONObject, thread: String) {
            val replies = turn.optJSONObject("mainReplies") ?: return
            val ids = replies.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                val reply = replies.getJSONObject(id)
                if (reply.optString("id") != id) throw IOException("INVALID_MAIN_REPLY")
                mainReply(reply, thread, turn.getString("id"))
            }
            projected.put("mainReplies", replies)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun mainReply(reply: JSONObject, thread: String, turn: String) {
            val provenance = reply.getJSONObject("provenance")
            val revision = reply.get("revision")
            if (reply.optString("schema") != "taku.main-reply/1" || provenance.optString("kind") != "primary-assistant-reply" ||
                provenance.optString("threadId") != thread || provenance.optString("turnId") != turn ||
                revision !is Number || revision.toDouble() != revision.toLong().toDouble() || revision.toLong() < 1 ||
                revision.toLong() > 9_007_199_254_740_991L) throw IOException("INVALID_MAIN_REPLY")
            text(reply, "id", 160)
            requireKey(text(reply, "key", 64))
            if (reply.get("readableText") !is String || !Pattern.matches("[a-f0-9]{64}", provenance.optString("contractHash")))
                throw IOException("INVALID_MAIN_REPLY")
            val phase = reply.getJSONObject("lifecycle").optString("phase")
            if (!java.util.Set.of("streaming", "sealed", "completed", "failed", "interrupted").contains(phase) ||
                (phase == "sealed" || phase == "completed") && reply.optJSONObject("card") == null) throw IOException("INVALID_MAIN_REPLY")
        }

        @JvmStatic
        fun replyRead(input: JSONObject, native: NativeRead): JSONObject {
            val control: JSONObject
            try {
                if (input.toString().length > 1024) throw IOException("INVALID_ARGUMENT")
                val keys = input.keys()
                while (keys.hasNext()) if (!java.util.Set.of("threadId", "turnId", "replyId").contains(keys.next())) throw IOException("INVALID_ARGUMENT")
                requireId(input, "threadId")
                val turn = text(input, "turnId", 160)
                control = JSONObject().put("op", "mainReply").put("action", "read")
                    .put("threadId", input.getString("threadId")).put("turnId", turn)
                if (input.has("replyId")) control.put("replyId", text(input, "replyId", 160))
            } catch (_: Exception) { return replyReadError("INVALID_ARGUMENT") }
            return try { native.read(control) } catch (failure: Exception) {
                val code = failure.message
                replyReadError(if (code != null && java.util.Set.of("THREAD_UNAVAILABLE", "TURN_UNAVAILABLE", "REPLY_UNAVAILABLE", "INVALID_ARGUMENT").contains(code)) code else "REPLY_UNAVAILABLE")
            }
        }

        @JvmStatic
        fun replyReadError(code: String): JSONObject = JSONObject().put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", code))

        // A catalog prompt and a saved layout exceed ordinary chat requests; everything else keeps the 48k fence.
        @JvmStatic
        fun limit(action: String): Int = when (action) {
            "analyzeCameraFrame" -> 360_000
            "generateUi" -> 96_000
            else -> MAX_REQUEST
        }

        /** Jev choice questions only with opaque keys and short text, never page content or credentials. */
        @JvmStatic
        @Throws(Exception::class)
        fun decision(input: JSONObject) {
            input.getJSONObject("state")
            val questions = input.getJSONObject("questions")
            if (questions.length() < 1 || questions.length() > 64) throw IOException("INVALID_questions")
            val keys = questions.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val question = questions.optJSONObject(key)
                if (key.length > 64 || question == null || "choice" != question.optString("type")) throw IOException("INVALID_questions")
                text(question, "instructions", 4000)
                val criteria = question.optJSONObject("criteria")
                if (criteria == null || criteria.length() < 1 || criteria.length() > 64) throw IOException("INVALID_questions")
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun text(objectValue: JSONObject, key: String, limit: Int): String {
            val raw = objectValue.get(key)
            if (raw !is String || (raw as java.lang.String).isBlank() || raw.length > limit) throw IOException("INVALID_$key")
            return raw
        }

        @JvmStatic
        @Throws(IOException::class)
        fun requireKey(key: String?) {
            if (key == null || !Pattern.matches("[a-z0-9-]{1,64}", key)) throw IOException("INVALID_key")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun requireId(objectValue: JSONObject, key: String) {
            if (!Pattern.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", text(objectValue, key, 36)))
                throw IOException("INVALID_$key")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun nativeView(source: JSONObject, thread: String): JSONObject {
            val result = JSONObject()
            for (key in arrayOf("activeTurnId", "ready", "plan", "goal", "error", "diff", "usage", "outputs", "executionScreen", "completedTurn", "cancellationCleanup"))
                if (source.has(key)) result.put(key, clean(source.get(key), 0))
            val requests = JSONArray()
            val pending = source.optJSONArray("requests")
            var unsupported = 0
            if (pending != null) for (i in 0 until pending.length()) {
                val question = pending.optJSONObject(i)
                val params = question?.optJSONObject("params") ?: continue
                // NativeBridge returns global runtime requests to every session. Signal the need
                // for native controls without exposing authentication payloads in the React UI.
                if (params.optString("threadId").isEmpty()) { unsupported++; continue }
                if (thread != params.optString("threadId")) continue
                if (supportedQuestion(question)) requests.put(clean(question, 0)) else unsupported++
            }
            return result.put("requests", requests).put("unsupportedRequestCount", unsupported)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun pending(state: JSONObject, thread: String, key: String): JSONObject {
            val pending = state.optJSONArray("requests")
            if (pending != null) for (i in 0 until pending.length()) {
                val question = pending.getJSONObject(i)
                val params = question.optJSONObject("params")
                if (key == question.optString("key") && params != null && thread == params.optString("threadId") && supportedQuestion(question)) return question
            }
            throw IOException("REQUEST_EXPIRED")
        }

        private fun supportedQuestion(question: JSONObject): Boolean {
            val method = question.optString("method")
            if (!QUESTIONS.contains(method)) return false
            if ("item/tool/requestUserInput" != method) return true
            val params = question.optJSONObject("params")
            val questions = params?.optJSONArray("questions")
            if (questions != null) for (i in 0 until questions.length()) {
                val item = questions.optJSONObject(i)
                if (item != null && item.optBoolean("isSecret")) return false
            }
            return true
        }

        @JvmStatic
        @Throws(Exception::class)
        fun clean(value: Any?, depth: Int): Any? {
            if (depth > 40) throw IOException("RESPONSE_TOO_DEEP")
            if (value is JSONObject) {
                val result = JSONObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!SECRET_KEYS.contains(key.lowercase(Locale.ROOT).replace("_", "").replace("-", ""))) result.put(key, clean(value.get(key), depth + 1))
                }
                return result
            }
            if (value is JSONArray) {
                val result = JSONArray()
                for (i in 0 until value.length()) result.put(clean(value.get(i), depth + 1))
                return result
            }
            return value
        }
    }
}
