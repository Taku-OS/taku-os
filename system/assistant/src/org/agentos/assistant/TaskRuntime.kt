package org.agentos.assistant

import java.io.File

internal object TaskRuntime {
    @JvmField val ROOT = File("/data/user/0/org.agentos.assistant/files/runtime")
    @JvmField val TASKS = File(ROOT, "tasks")
}
