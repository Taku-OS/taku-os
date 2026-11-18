// SPDX-License-Identifier: Apache-2.0
package org.agentos.assistant

import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import org.json.JSONObject

/** Check the production cache on the UI thread without replacing PackageManager. */
class AvailabilityProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    override fun onStart() {
        val report=JSONObject().put("passed",false)
        try {
            runOnMainSync {
                val expected=AudioEngine.available(targetContext)
                val before=Debug.getBinderSentTransactions()
                repeat(1000) { check(AudioEngine.available(targetContext)==expected) }
                val transactions=Debug.getBinderSentTransactions()-before
                check(transactions==0) { "Cached checks performed IPC: $transactions" }
                report.put("available",expected).put("cachedReads",1000).put("binderTransactions",transactions)
            }
            report.put("passed",true)
        } catch(error: Throwable) { report.put("error",error.toString()) }
        finish(if(report.optBoolean("passed")) -1 else 0,Bundle().apply { putString("report",report.toString()) })
    }
}
