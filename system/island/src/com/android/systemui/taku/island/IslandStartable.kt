/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import org.json.JSONObject

/** Starts the system-wide data plane and presenter without creating demo notifications. */
class IslandStartable @Inject constructor(
    private val registry: CommandRegistry,
    private val presenter: IslandPresenter,
    private val engine: IslandEngine,
    private val producer: IslandProducerBinding,
    private val systemSources: IslandSystemSources,
    @Background private val worker: Executor,
) : CoreStartable {
    override fun start() {
        engine.start()
        presenter.start()
        producer.start()
        systemSources.start()
        registry.registerCommand("island") {
            object : Command {
                override fun execute(pw: PrintWriter, args: List<String>) {
                    if (args.isEmpty() || args.size > 3) help(pw)
                    else pw.println(IslandRuntime.call(args[0], args.getOrNull(1), args.getOrNull(2)))
                }
                override fun help(pw: PrintWriter) {
                    pw.println("island capabilities | canShowIsland PACKAGE | state | list | get KEY | select KEY | choose | expand KEY | collapse | dismiss KEY | open KEY | action KEY JSON | settings | setEnabled 0|1 | setAllowed CSV")
                }
            }
        }
        registry.registerCommand("island-validate", {
            object : Command {
                override fun execute(pw: PrintWriter, args: List<String>) {
                    if (args.size != 2) {
                        help(pw)
                        return
                    }
                    // This read-only validation path uses the same parser intended for extraction.
                    // It cannot grant a producer system-source identity or execute an action.
                    val result = IslandPayload.parse(args[1], args[0], false)
                    pw.println(JSONObject().put("valid", result.isValid)
                        .put("error", result.error ?: JSONObject.NULL))
                }
                override fun help(pw: PrintWriter) {
                    pw.println("island-validate POSTING_PACKAGE JSON (read-only; no posting permission implied)")
                }
            }
        }, worker)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        // dumpsys may run off-main; read the immutable snapshot, never traverse Views here.
        pw.println(engine.snapshot)
    }
}
