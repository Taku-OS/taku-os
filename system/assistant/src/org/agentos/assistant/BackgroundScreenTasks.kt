package org.agentos.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.agentos.surfaces.SurfaceSpec
import org.agentos.surfaces.client.SurfaceClient
import org.json.JSONArray
import org.json.JSONObject

/** Screen-task owner, held by the bound system assistant; never starts an Activity. */
internal class BackgroundScreenTasks private constructor(context: Context) : SurfaceClient.Listener {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val questions = ConcurrentHashMap<String, Question>()
    private val surfaces = SurfaceClient(context, this)
    private val saved = File(context.filesDir, "background-screen-tasks.json")
    private var connected = false

    private class Job(val id: String, val title: String, val createdAt: Long) {
        @Volatile var thread = ""
        @Volatile var task = ""
        @Volatile var state = "starting"
        @Volatile var result = ""
        var revision = 0L
        var starting = false
        var polling = false
        var hidden = false
        var manual = false
        @Volatile var cancelRequested = false
        var capture: JevScreenDecision? = null

        fun surfaceId(): String = "screen-$id"
    }

    private class Question(val job: Job, val key: String, val presentation: BackgroundQuestion) {
        var revision = 0L
        var answering = false
        var hidden = false

        fun surfaceId(): String = "ask-$key"
    }

    @Synchronized
    @Throws(Exception::class)
    private fun persist() {
        val rows = JSONArray()
        for (job in jobs.values) {
            rows.put(JSONObject().put("id", job.id).put("title", job.title).put("createdAt", job.createdAt)
                .put("thread", job.thread).put("task", job.task).put("state", job.state)
                .put("result", job.result).put("manual", job.manual))
        }
        TaskRequests.write(saved, rows.toString())
    }

    private fun pruneFinished() {
        for (job in ArrayList(jobs.values)) {
            if (job.state != "starting" && job.state != "running") {
                dismiss(job.surfaceId())
                jobs.remove(job.id)
            }
        }
    }

    override fun onConnected() {
        connected = true
        for (job in jobs.values) progress(job)
        main.removeCallbacks(poll)
        main.post(poll)
    }

    override fun onEvent(type: String, id: String, revision: Long, value: String?) {
        if (type == "disconnected") {
            connected = false
            return
        }
        if (type == "error") {
            Toast.makeText(context, "The bottom component is temporarily unavailable. No automatic answer or confirmation was given", Toast.LENGTH_LONG).show()
            for (job in jobs.values) {
                if (job.surfaceId() == id && job.task.isEmpty()) {
                    job.state = "failed"
                    job.capture = null
                }
            }
        }
        if (type == "published") {
            for (job in jobs.values) {
                if (job.surfaceId() == id && job.state == "starting" && !job.starting) start(job)
            }
        }
        if (type == "dismissed" || type == "expired") {
            for (question in questions.values) {
                if (question.surfaceId() == id) {
                    question.hidden = true
                    question.job.hidden = false
                    progress(question.job)
                }
            }
            for (job in jobs.values) {
                if (job.surfaceId() == id) job.hidden = true
            }
        }
    }

    override fun onAction(event: SurfaceClient.ActionEvent) {
        for (question in questions.values) {
            if (question.surfaceId() != event.id() || question.revision != event.revision()) continue
            if (question.answering) return
            try {
                val response = question.presentation.response(event.actionId(), event.revision(), question.revision)
                    ?: return
                question.answering = true
                NativeClient.async(JSONObject().put("op", "respond").put("key", question.key)
                    .put("response", response), object : NativeClient.Callback {
                    override fun ready(value: JSONObject) {
                        dismiss(question.surfaceId())
                        questions.remove(question.key)
                        progress(question.job)
                    }

                    override fun failed(message: String) {
                        question.answering = false
                        showQuestion(question)
                    }
                })
            } catch (_: Exception) {
                question.answering = false
            }
            return
        }
        for (job in jobs.values) {
            if (job.surfaceId() != event.id() || job.revision != event.revision()) continue
            when (event.actionId()) {
                "stop" -> cancel(job)
                "resume" -> {
                    for (question in questions.values) {
                        if (question.job === job) {
                            question.hidden = false
                            showQuestion(question)
                        }
                    }
                }
                "done" -> {
                    dismiss(job.surfaceId())
                    jobs.remove(job.id)
                    try { persist() } catch (_: Exception) { }
                }
            }
            return
        }
    }

    private fun progress(job: Job) {
        if (!connected || job.hidden) return
        try {
            val running = job.state == "starting" || job.state == "running"
            val waiting = questions.values.any { it.job === job }
            val title = when {
                running -> if (waiting) "Taku needs your choice" else "Taku is working in the background"
                job.state == "completed" -> "Results are ready"
                job.state == "cancelled" -> "Task cancelled"
                else -> "This attempt did not complete"
            }
            val actions = ArrayList<SurfaceSpec.Action>()
            if (waiting) actions.add(SurfaceSpec.Action("resume", "Answer question", true))
            actions.add(SurfaceSpec.Action(if (running) "stop" else "done", if (running) "Stop task" else "Got it", !waiting))
            val summary = if (running) job.title else limit(job.result, 500)
            surfaces.publish(SurfaceSpec(job.surfaceId(), ++job.revision, "hint", "bottom", title, summary,
                emptyList(), actions, 1800000, "compact", "", null))
        } catch (_: Exception) {
            Toast.makeText(context, "The bottom component is temporarily unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun start(job: Job) {
        job.starting = true
        NativeClient.io.execute {
            try {
                val capture = job.capture
                if (job.cancelRequested) throw IllegalStateException("This background task was cancelled")
                if (capture == null || capture.expired()) throw IllegalStateException("Suggestion expired. Open the assistant again")
                ImageScreenSuggestion.requireCurrent(capture)
                if (!capture.receipt.optBoolean("userTriggered") &&
                    (!context.getSharedPreferences("agent-settings", 0).getBoolean("jevEnabled", false) ||
                        capture.receipt.optString("gateModel") != SceneRoutes.get(context, "jev"))) {
                    throw IllegalStateException("Jev settings changed")
                }
                val thread = NativeClient.rpcSync("thread/start", JSONObject().put("developerInstructions", INSTRUCTIONS))
                job.thread = thread.getJSONObject("thread").getString("id")
                if (job.cancelRequested) throw IllegalStateException("This background task was cancelled")
                val options = JSONObject().put("includeScreenshot", true).put("responseStyle", "concise")
                ComposerOptions.prepareSubmission().apply(context.getSharedPreferences("agent-settings", 0), job.thread, options)
                TaskRequests.submit(capture.prompt(), capture.foreground, capture.screenshot, job.thread, options) { id ->
                    if (job.cancelRequested) throw IllegalStateException("This background task was cancelled")
                    job.task = id
                    job.state = "running"
                    persist()
                }
                job.capture = null
                main.post {
                    progress(job)
                    main.removeCallbacks(poll)
                    main.post(poll)
                }
            } catch (error: Exception) {
                job.state = if (job.cancelRequested) "cancelled" else "failed"
                job.capture = null
                job.result = error.message ?: "Background task did not start. Trigger it again"
                try { persist() } catch (_: Exception) { }
                main.post { progress(job) }
            }
        }
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            for (job in jobs.values) {
                if (job.state != "running" || job.polling) continue
                job.polling = true
                NativeClient.io.execute {
                    try {
                        val task = File(TaskRuntime.TASKS, job.task)
                        val state = JSONObject(Files.readAllBytes(File(task, "state.json").toPath()).toString(Charsets.UTF_8))
                        val nativeState = NativeClient.state(job.thread)
                        var result = ""
                        when (state.optString("state")) {
                            "completed", "failed", "cancelled", "needs_user" -> {
                                val view = File(TaskRuntime.ROOT, "agent-view/${job.thread}.json")
                                if (view.isFile && view.length() < 20000000) {
                                    result = AgentConversation.lastReply(JSONObject(Files.readAllBytes(view.toPath()).toString(Charsets.UTF_8)))
                                }
                            }
                        }
                        val reply = result
                        main.post { update(job, state, nativeState, reply) }
                    } catch (_: Exception) {
                        main.post { job.polling = false }
                    }
                }
            }
            if (jobs.values.any { it.state == "running" }) main.postDelayed(this, 1500)
        }
    }

    private fun update(job: Job, state: JSONObject, nativeState: JSONObject, reply: String) {
        job.polling = false
        val pending = nativeState.optJSONArray("requests")
        val present = ArrayList<String>()
        if (pending != null) {
            for (i in 0 until pending.length()) {
                try {
                    val request = pending.getJSONObject(i)
                    val params = request.getJSONObject("params")
                    val method = request.optString("method")
                    if (!BackgroundQuestion.supports(method) || job.thread != params.optString("threadId")) continue
                    val key = request.getString("key")
                    present.add(key)
                    if (!questions.containsKey(key)) {
                        val locales = context.resources.configuration.locales
                        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
                        val question = Question(job, key, BackgroundQuestion.fromPending(method, params, locale))
                        questions[key] = question
                        job.hidden = false
                        progress(job)
                        showQuestion(question)
                    }
                } catch (_: Exception) {
                    // Invalid questions are never accepted on the user's behalf.
                }
            }
        }
        var questionsRemoved = false
        for (question in ArrayList(questions.values)) {
            if (question.job === job && !present.contains(question.key)) {
                dismiss(question.surfaceId())
                questions.remove(question.key)
                questionsRemoved = true
            }
        }
        val status = state.optString("state")
        when (status) {
            "completed", "failed", "cancelled", "needs_user" -> {
                job.state = status
                job.result = BackgroundTaskResult.message(status, state.optString("message"), reply)
                job.hidden = false
                try { persist() } catch (_: Exception) { }
                progress(job)
            }
            else -> {
                if (System.currentTimeMillis() - job.createdAt > 1800000) {
                    cancel(job)
                } else if (questionsRemoved && (job.state == "running" || job.state == "starting") &&
                    questions.values.none { it.job === job && !it.hidden }) {
                    // Refresh after an external answer without covering a replacement question.
                    progress(job)
                }
            }
        }
    }

    private fun showQuestion(question: Question) {
        if (!connected || question.hidden) return
        try {
            surfaces.publish(question.presentation.surface(question.surfaceId(), ++question.revision))
        } catch (_: Exception) {
            Toast.makeText(context, "The question was not displayed. The task is still waiting", Toast.LENGTH_LONG).show()
        }
    }

    private fun cancel(job: Job) {
        job.cancelRequested = true
        NativeClient.io.execute {
            try {
                if (job.task.isNotEmpty()) TaskRequests.write(File(File(TaskRuntime.TASKS, job.task), "cancel"), "1")
                main.post {
                    if (job.task.isEmpty()) job.state = "cancelled"
                    job.result = "Background task stop requested"
                    progress(job)
                }
            } catch (_: Exception) {
                main.post {
                    Toast.makeText(context, "Stopping is not yet confirmed. Retry later", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dismiss(id: String) {
        if (connected) {
            try { surfaces.dismiss(id) } catch (_: Exception) { }
        }
    }

    companion object {
        private var current: BackgroundScreenTasks? = null
        private val taskIdPattern = Regex("[0-9]+")
        private const val INSTRUCTIONS = "You are Taku's background phone Agent. The user accepted a screen suggestion. Work in the independent execution display; keep the user's foreground app untouched except for an explicitly requested final draft prepared through the dedicated tool. The task has a 15-minute execution budget and the user can stop it. " +
            "Use authorized memory_search/memory_get and the supplied original image to reuse known facts. Never OCR or transcribe the screenshot, and never invent missing facts. " +
            "For ordinary visible verification challenges such as sliders or picture puzzles, use the phone gesture tools to complete the normal on-screen interaction yourself without first asking the user. Observe each fresh challenge and verify the actual result. For a reveal-while-dragging challenge, use touch DOWN/MOVE and observe_background while held. MOVE to the final observed target, observe again if needed, then UP at exactly the last MOVE coordinates. UP only releases; it is not a drag-to command. Retain the same gesture and act within its 15-second idle deadline. Never bypass verification APIs, forge verification tokens, modify apps, or alter verification responses. After at most two failed attempts on the same barrier, stop retrying and explain the actual blocker through the bottom surface. " +
            "Minimize interruptions. When a necessary parameter or approval is missing, call taku_phone.ask_user with one concise question and 1-3 meaningful options; wait for the actual choice before proceeding. " +
            "Do not put clarification questions into a final chat reply or ask the user to open Taku. The current ask_user surface supports choices, not free-text entry; never pretend arbitrary text was entered. " +
            "Before committing an action, show the actual details with taku_phone.confirm_action and wait for the real response to this pending call. For a reservation, show the verified restaurant and branch, complete date and time, party size, and allergy note as separate fields; use notice style for the allergy. Do not infer a missing date or claim that the restaurant can accommodate an allergy without evidence. " +
            "Only decision=confirm from this exact confirmation authorizes submission of this one free reservation with precisely those details. Rendering, dismissing, expiring, a prior prototype preview, modify, cancel, and silence are not approval. If any detail changes, show a new confirmation and wait again. On modify, clarify the changed details; on cancel, do not submit. A reservation confirmation never authorizes payment: any charge requires a separate explicit confirmation of the payee and exact amount/currency. Never ask for passwords or verification codes. " +
            "After submission, inspect the real receipt and distinguish a confirmed reservation, a request awaiting merchant confirmation, and a failure. A submitted request is not a confirmed booking. Report only the verified result; do not promise merchant acceptance or allergy accommodation. " +
            "Never send messages to WeChat or other contacts, press Send, or use Enter to send. If the user explicitly requested a reply draft, use taku_phone.prepare_foreground_draft(packageName,conversationTitle,text) only to fill the input of the current original conversation after the actual result is known. The user will send it manually. Preserve any existing draft, do not switch to another conversation, and do not include unverified success claims. All other work stays in the independent execution display. " +
            "Report progress in concise Chinese. The final reply is displayed in a small bottom system panel: use at most 120 Chinese characters of plain text, without Markdown tables or headings. Lead with the actual outcome or unresolved blocker, then the most useful verified result and next step. Do not claim a booking or an app action succeeded without actual evidence."

        private fun get(context: Context): BackgroundScreenTasks =
            current ?: BackgroundScreenTasks(context).also { current = it }

        @JvmStatic
        fun ownsTask(context: Context, id: String): Boolean {
            if (current?.jobs?.values?.any { it.task == id } == true) return true
            try {
                val file = File(context.filesDir, "background-screen-tasks.json")
                if (!file.isFile || file.length() > 65536) return false
                val rows = JSONArray(Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8))
                for (i in 0 until minOf(rows.length(), 4)) {
                    if (id == rows.getJSONObject(i).optString("task")) return true
                }
            } catch (_: Exception) { }
            return false
        }

        @JvmStatic
        @Throws(Exception::class)
        fun status(context: Context, id: String): JSONObject? {
            restore(context)
            val job = get(context).jobs[id] ?: return null
            return JSONObject().put("id", job.id).put("threadId", job.thread)
                .put("taskId", job.task).put("state", job.state).put("result", job.result).put("manual", job.manual)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun enqueue(context: Context, id: String, decision: JevScreenDecision): JSONObject {
            val existing = status(context, id)
            if (existing != null) return existing
            if (decision.expired() || !decision.actionable || !decision.receipt.optBoolean("generated")) {
                throw IllegalStateException("CAPTURE_EXPIRED")
            }
            val owner = get(context)
            owner.pruneFinished()
            if (owner.jobs.size >= 4) throw IllegalStateException("BACKGROUND_TASK_LIMIT")
            val job = Job(id, decision.suggestion.substringBefore('\n'), System.currentTimeMillis())
            job.capture = decision
            job.manual = decision.receipt.optBoolean("userTriggered")
            owner.jobs[job.id] = job
            try {
                owner.persist()
                owner.surfaces.connect()
                if (owner.connected) owner.progress(job)
            } catch (failure: Exception) {
                owner.jobs.remove(job.id)
                owner.persist()
                throw failure
            }
            return requireNotNull(status(context, id))
        }

        @JvmStatic
        fun restore(context: Context) {
            if (current != null) return
            val owner = get(context)
            try {
                if (!owner.saved.isFile) return
                val rows = JSONArray(Files.readAllBytes(owner.saved.toPath()).toString(Charsets.UTF_8))
                for (i in 0 until minOf(rows.length(), 4)) {
                    val row = rows.getJSONObject(i)
                    if (!AgentGatewayProtocol.isUuid(row.optString("id"))) continue
                    val job = Job(row.getString("id"), row.getString("title"), row.getLong("createdAt"))
                    job.thread = row.optString("thread")
                    job.task = row.optString("task")
                    job.state = row.optString("state")
                    job.result = row.optString("result")
                    job.manual = row.optBoolean("manual")
                    if (job.state == "running" &&
                        (!AgentGatewayProtocol.isUuid(job.thread) || !taskIdPattern.matches(job.task))) continue
                    if (job.state == "starting") {
                        job.state = "failed"
                        job.result = "The assistant stopped before this task was submitted. Capture the screen again."
                    }
                    when (job.state) {
                        "running", "completed", "failed", "cancelled", "needs_user" -> owner.jobs[job.id] = job
                    }
                }
                if (owner.jobs.isNotEmpty()) owner.surfaces.connect()
            } catch (_: Exception) {
                // Never replay an uncommitted capture after process death.
            }
        }

        private fun limit(value: String, size: Int): String =
            if (value.length <= size) value else value.substring(0, size - 1) + "…"
    }
}
