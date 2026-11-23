package com.android.systemui.taku.island

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import java.io.File
import org.json.JSONObject

/** Renders the production View on Android. No SystemUI replacement, mock login or live notification. */
class CapsuleRenderTests : Instrumentation() {
    private var checks = 0
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            var failure: Throwable? = null
            runOnMainSync { try { cases(); progressCases(); templateCases() } catch (e: Throwable) { failure = e } }
            failure?.let { throw it }
            result.putString("stream", "PASS: $checks capsule render checks. Component Canvas only; real status-bar placement still needs SystemUI installation.\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: $e\n${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun assertThat(ok: Boolean, message: String) { check(ok) { message }; checks++ }
    /** Canonical v2 activity: Taku's AI loader in group A, a short status in group B. */
    private fun activity(label: String, capsule: Boolean = true): IslandActivity {
        val payload = JSONObject().put("version", 2)
            .put("expanded", JSONObject().put("template", "basic").put("title", "Signing in to ChatGPT"))
        if (capsule) payload.put("compact", JSONObject()
            .put("a", JSONObject().put("type", "animation").put("name", "ai-working"))
            .put("b", JSONObject().put("type", "text").put("text", label)))
        return IslandActivity("render-test", targetContext.packageName, 0, 0, payload, emptyMap(), emptyMap(), null, null, null)
    }
    private fun cases() {
        assertThat(activity("Signing...").compactVisible, "publisher-supplied capsule is shown")
        assertThat(!activity("", capsule = false).compactVisible, "no compact data means no capsule")
        assertThat(activity("Building...").compact?.getJSONObject("b")?.getString("text") == "Building...", "Building is publisher text")
        val view = IslandCapsuleView(targetContext, {}, {}, {}, {})
        val density = targetContext.resources.displayMetrics.density
        fun px(value: Float) = (value * density).toInt()
        val phase = IslandCapsuleView::class.java.getDeclaredField("phase").apply { isAccessible = true }
        val dir = File(targetContext.getExternalFilesDir(null), "frames").apply { mkdirs() }
        for (budgetDp in listOf(220f, 180f)) {
            val scale = 1f
            val width = view.layoutWidth(activity("Signing..."), px(budgetDp), false)
            val height = px(32f)
            view.layout(0, 0, width, height)
            view.visibility = View.VISIBLE
            view.bind(activity("Signing..."), null)
            var previous: IntArray? = null
            var first: IntArray? = null
            for ((frame, value) in listOf(0f, .125f, .375f, .875f).withIndex()) {
                phase.setFloat(view, value)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                // The camera centre is fixed at x=52 design pixels, not the View's midpoint.
                assertThat(bitmap.getPixel(px(52f * scale), height / 2) == Color.BLACK, "camera must remain solid black")
                // Every interior centre-line pixel spans one continuous silhouette: no open neck seams.
                assertThat((px(2f * scale) until width - px(2f * scale)).all { Color.alpha(bitmap.getPixel(it, height / 2)) == 255 }, "one opaque connected silhouette")
                // A 32 dp disc has transparent corners; it cannot regress to the old full-width left pill.
                assertThat(Color.alpha(bitmap.getPixel(px(32f * scale), 0)) == 0, "loading disc must stay circular")
                previous?.let { assertThat(!it.contentEquals(pixels), "nine-dot poses change across the loop") }
                File(dir, "scale-${scale}-frame-$frame.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(preview).apply { drawColor(0xffb3c6dd.toInt()); drawBitmap(bitmap, 0f, 0f, null) }
                File(dir, "preview-${scale}-frame-$frame.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                preview.recycle()
                if (frame == 0) first = pixels
                previous = pixels
                bitmap.recycle()
            }
            phase.setFloat(view, 1f)
            val wrapped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(wrapped))
            val wrappedPixels = IntArray(width * height)
            wrapped.getPixels(wrappedPixels, 0, width, 0, 0, width, height)
            assertThat(first!!.contentEquals(wrappedPixels), "1000 ms loop joins without a reset flash")
            wrapped.recycle()
            view.visibility = View.GONE // Cancels the production animator; no leaked frame callbacks.
        }
    }
    private fun progressCases() {
        val body = "Finish signing in on the ChatGPT page, then Taku continues setup."
        val expanded = JSONObject().put("template", "basic").put("title", "Signing in to ChatGPT").put("body", body)
            .put("actions", org.json.JSONArray().put("open")).put("primaryAction", "open")
        val item = activity("Signing...").copy(packageName = "org.agentos.agent",
            payload = JSONObject(activity("Signing...").payload.toString()).put("expanded", expanded),
            nativeActions = mapOf("open" to IslandNativeAction("Back to setup", {})))
        assertThat(item.nativeProgressCard, "onboarding uses native card")
        assertThat(!item.copy(packageName = "another.app").nativeProgressCard, "third-party notifications keep their template")
        assertThat(!item.copy(payload = JSONObject(item.payload.toString()).apply {
            getJSONObject("expanded").put("content", JSONObject())
        }).nativeProgressCard, "remote content is not replaced")
        val commands = mutableListOf<String>()
        val closes = mutableListOf<String>()
        val card = IslandProgressCard(targetContext, { op, key, arg -> commands.add("$op:$key:$arg") }, { closes.add(it) })
        val d = targetContext.resources.displayMetrics.density
        val width = (396 * d).toInt()
        val height = card.desiredHeight(item, width)
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, width, height)
        assertThat(height > 150 * d && height < 250 * d, "login height follows content instead of fixed 340 dp")
        assertThat(kotlin.math.abs(height / d - 180f) < 2f, "two-line card preserves Figma 180 dp height and line leading")
        assertThat(card.state().getString("text") == body, "real login text, no All set or sample name")
        val row = card.getChildAt(2) as android.widget.LinearLayout
        assertThat(row.childCount == 2, "two designed pill actions")
        val close = row.getChildAt(0) as android.widget.TextView
        val open = row.getChildAt(1) as android.widget.TextView
        assertThat(close.text == "Close" && open.text == "Back to setup", "actual operation labels")
        assertThat(kotlin.math.abs(open.width - close.width) <= 1, "equal action widths")
        assertThat(row.top - card.getChildAt(1).bottom in (15*d).toInt()..(17*d).toInt(), "16 dp content/action gap")
        close.performClick()
        assertThat(closes == listOf("button") && commands.isEmpty(), "Close only collapses, does not cancel sign-in")
        open.performClick()
        assertThat(commands.single() == "action:${item.key}:{\"id\":\"open\"}", "return uses real action identity")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xffb3c6dd.toInt())
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 40*d, 40*d,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xeb000000.toInt() })
        card.draw(canvas)
        val output = File(targetContext.getExternalFilesDir(null), "frames/expanded-signing.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val longItem = item.copy(payload = JSONObject(item.payload.toString()).apply {
            getJSONObject("expanded").put("body", body.repeat(12))
        })
        card.update(longItem)
        val capped = (250*d).toInt()
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, width, capped)
        assertThat(row.bottom <= capped - card.paddingBottom && row.height > 0, "capped long content keeps actions visible")
        assertThat((card.getChildAt(1) as android.widget.ScrollView).canScrollVertically(1), "long status remains scrollable")
        card.update(item.copy(payload = JSONObject(item.payload.toString()).apply { getJSONObject("expanded").put("actions", org.json.JSONArray()) }, nativeActions = emptyMap()))
        assertThat(row.childCount == 1, "removed actions do not survive an update")
    }

    /** Public v2 templates: highlight value, accent colour, one unlimited chip row, tapAction off the row. */
    private fun templateCases() {
        val intent = android.app.PendingIntent.getActivity(targetContext, 0,
            android.content.Intent(android.content.Intent.ACTION_MAIN).setPackage(targetContext.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val ids = (0 until 40).map { "a$it" }
        val actions = (ids + "open_order").associateWith {
            android.app.Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Action $it", intent).build()
        }
        val payload = JSONObject().put("version", 2).put("expanded", JSONObject()
            .put("template", "highlight").put("title", "Order ready")
            .put("highlight", JSONObject().put("text", "A123").put("label", "Pickup code"))
            .put("body", "Counter 3").put("actions", org.json.JSONArray(ids)).put("primaryAction", "a0")
            .put("tapAction", "open_order").put("accentColor", "#FF8A3D"))
        val item = IslandActivity("template-test", "org.example.delivery", 0, 0, payload, emptyMap(), actions, intent, null, null)
        val card = IslandTemplateCard(targetContext, { _, _, _ -> }, { })
        card.update(item)
        val state = card.state()
        assertThat(state.getString("template") == "highlight" && state.getString("highlight") == "A123", "highlight value drawn")
        assertThat(state.getString("accentColor") == "#FF8A3D", "accent colour applied")
        assertThat(state.getInt("actionCount") == 40, "every listed action is a chip; tapAction stays off the row")
        assertThat(state.getString("tapAction") == "open_order", "card tap runs the tap action")
        val width = (360 * targetContext.resources.displayMetrics.density).toInt()
        val height = card.heightFor(width)
        card.layout(0, 0, width, height)
        val scroll = (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<android.widget.HorizontalScrollView>().single()
        val row = scroll.getChildAt(0) as android.widget.LinearLayout
        val chipHeight = row.getChildAt(0).height
        assertThat(row.height == chipHeight, "chips stay in one row and never wrap")
        assertThat(scroll.canScrollHorizontally(1), "extra chips scroll horizontally")
        assertThat(height < (340 * targetContext.resources.displayMetrics.density).toInt(), "forty actions do not grow the card")
    }

}
