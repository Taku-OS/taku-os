package com.android.systemui.taku.island

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import java.io.File
import org.json.JSONObject

/** Attaches only the component under test so Android dispatches real visibility/lifecycle events. */
class CapsuleTestActivity : Activity() {
    lateinit var content: android.widget.FrameLayout
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        content = android.widget.FrameLayout(this)
        setContentView(content)
    }
}

/** Renders the production View on Android. No SystemUI replacement, mock login or live notification. */
class CapsuleRenderTests : Instrumentation() {
    private var checks = 0
    private var host: CapsuleTestActivity? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            host = startActivitySync(android.content.Intent(targetContext, CapsuleTestActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as CapsuleTestActivity
            waitForIdleSync()
            var failure: Throwable? = null
            runOnMainSync { try { cases(); statusCases(); cutoutCases(); geometryCases(); summaryCases(); ownershipCases(); progressCases(); templateCases() } catch (e: Throwable) { failure = e } }
            failure?.let { throw it }
            result.putString("stream", "PASS: $checks capsule render checks. Component Canvas only; real status-bar placement still needs SystemUI installation.\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: $e\n${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { host?.finish() }
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
    /** The lock status: disc and camera only, a white padlock whose shackle lifts when unlocked. */
    private fun statusCases() {
        fun status(state: String) = IslandActivity("taku:status:lock", targetContext.packageName, 0, 0,
            JSONObject().put("version", 2).put("status", state).put("compact", JSONObject())
                .put("expanded", JSONObject().put("template", "basic").put("title", state)),
            emptyMap(), emptyMap(), null, null, null, source = IslandActivity.SOURCE_STATUS)
        val view = IslandCapsuleView(targetContext, {}, {}, {}, {})
        val density = targetContext.resources.displayMetrics.density
        fun px(value: Float) = (value * density).toInt()
        val height = px(32f)
        val dir = File(targetContext.getExternalFilesDir(null), "frames").apply { mkdirs() }
        fun render(state: String): Bitmap {
            val width = view.layoutWidth(status(state), px(220f), false)
            // 16 dp disc radius, 8 dp gap and the 24 dp design camera: no pill for a status.
            assertThat(width == kotlin.math.ceil(64f * density).toInt(), "a status is the disc and the camera only")
            view.layout(0, 0, width, height)
            view.visibility = View.VISIBLE
            view.bind(status(state), null)
            assertThat(view.state().getString("disc") == "lock", "the disc draws the padlock")
            assertThat(view.contentDescription == state, "a status announces only itself")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(dir, "lock-$state.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap
        }
        val locked = render(IslandActivity.STATUS_LOCKED)
        val unlocked = render(IslandActivity.STATUS_UNLOCKED)
        for (bitmap in listOf(locked, unlocked)) {
            assertThat(bitmap.getPixel(px(16f), height / 2 + px(4f)) == Color.WHITE, "the padlock body is white")
            assertThat(bitmap.getPixel(px(52f), height / 2) == Color.BLACK, "camera must remain solid black")
        }
        // The open shackle is lifted 1.5 dp higher than the closed one.
        assertThat(locked.getPixel(px(16f), height / 2 - px(8f)) == Color.BLACK, "the closed shackle stays low")
        assertThat(unlocked.getPixel(px(16f), height / 2 - px(8f)) == Color.WHITE, "the open shackle is lifted")
        locked.recycle()
        unlocked.recycle()
        // On the lock screen the capsule only displays: touches belong to the lock screen below.
        view.interactive = false
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, px(16f).toFloat(), height / 2f, 0)
        assertThat(!view.onTouchEvent(down), "a display-only capsule takes no touches")
        down.recycle()
        view.visibility = View.GONE
    }
    private fun cutoutCases() {
        // Pixel 8 overlay on the 1080 px AVD: the exclusion is NOT the visible 72.5 px hole.
        val exclusion = Rect(479, 0, 601, 132)
        val path = Path().apply { addCircle(539.75f, 65.75f, 36.25f, Path.Direction.CW) }
        val original = Path(path)
        fun bounds(p: Path?) = IslandCameraGeometry.bounds(p, exclusion, 168f)
        val camera = bounds(path)!!
        assertThat(camera.width() == 72.5f && camera.height() == 72.5f, "paint the hole, not the 122 px exclusion")
        assertThat(camera.centerX() == 539.75f && camera.centerY() == 65.75f, "preserve the actual subpixel centre")
        assertThat(IslandCameraGeometry.bounds(original, Rect(503, 29, 576, 102), 168f) == camera,
            "a tight exclusion without top padding still contains a camera")
        path.addCircle(540f, 2380f, 20f, Path.Direction.CW)
        assertThat(bounds(path) == camera, "a bottom cutout cannot enlarge the top camera")
        assertThat(bounds(original) == camera, "shared cutout path remains unchanged")
        assertThat(bounds(null) == null && bounds(Path()) == null, "missing paths fall back to the design circle")
        val notch = Path().apply { addRect(490f, 0f, 590f, 100f, Path.Direction.CW) }
        assertThat(bounds(notch) == null, "an edge-connected notch is not a camera")
        val oval = Path().apply { addOval(490f, 30f, 590f, 80f, Path.Direction.CW) }
        assertThat(bounds(oval) == null, "a wide cutout is not a circular camera")
        assertThat(IslandCameraGeometry.bounds(original, Rect(), 168f) == null, "no top exclusion means no camera")
        val view = IslandCapsuleView(targetContext, {}, {}, {}, {})
        val d = targetContext.resources.displayMetrics.density
        view.configure((52 * d).toInt(), camera.width() / 2f)
        val width = view.layoutWidth(activity("A123"), (260 * d).toInt(), false)
        view.bind(activity("A123"), null)
        val height = (32 * d).toInt()
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val x = view.cameraOffset()
        assertThat(Color.alpha(bitmap.getPixel(x, 0)) == 0, "camera is smaller than the 32 dp side discs")
        assertThat(bitmap.getPixel(x, height / 2) == Color.BLACK, "real camera centre stays black")
        assertThat(kotlin.math.abs(view.state().getDouble("cameraRadiusDp") - 36.25 / d) < .001,
            "Agent readback reports the drawn radius")
        val preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(preview).apply { drawColor(0xffb3c6dd.toInt()); drawBitmap(bitmap, 0f, 0f, null) }
        File(targetContext.getExternalFilesDir(null), "frames/pixel8-cutout.png").outputStream().use {
            preview.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle(); preview.recycle()
    }
    /** Real cutout sizes, a short bar and the waiting circle on the left (Figma 1:4742). */
    private fun geometryCases() {
        val d = targetContext.resources.displayMetrics.density
        fun px(value: Float) = (value * d).toInt()
        fun render(view: IslandCapsuleView, width: Int, height: Int): Bitmap {
            view.layout(0, 0, width, height)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        // A synthetic 46 dp physical hole: keep supporting genuinely large cameras.
        val big = IslandCapsuleView(targetContext, {}, {}, {}, {})
        big.configure(px(52f), px(23f).toFloat())
        val bigWidth = big.layoutWidth(activity("A123"), px(260f), false)
        big.bind(activity("A123"), null)
        val bigBitmap = render(big, bigWidth, px(48f))
        val camera = big.cameraOffset()
        assertThat(kotlin.math.abs(camera - px(63f)) <= 1, "camera centre follows the real radius (40 + 23 dp)")
        assertThat(bigBitmap.getPixel(camera, bigBitmap.height / 2) == Color.BLACK, "large camera stays solid")
        assertThat((px(2f) until bigWidth - px(2f)).all { Color.alpha(bigBitmap.getPixel(it, bigBitmap.height / 2)) == 255 },
            "necks join a large camera without gaps")
        File(targetContext.getExternalFilesDir(null), "frames/large-cutout.png").outputStream().use { bigBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bigBitmap.recycle()
        // A 24 dp status bar scales the whole capsule instead of clipping its top and bottom.
        val short = IslandCapsuleView(targetContext, {}, {}, {}, {})
        short.configure(px(24f), 0f)
        val shortWidth = short.layoutWidth(activity("A123"), px(260f), false)
        short.bind(activity("A123"), null)
        val shortBitmap = render(short, shortWidth, px(24f))
        assertThat(Color.alpha(shortBitmap.getPixel(px(12f), 0)) == 255 && Color.alpha(shortBitmap.getPixel(px(12f), px(24f) - 1)) == 255,
            "scaled disc reaches the bar's edges")
        assertThat(Color.alpha(shortBitmap.getPixel(0, 0)) == 0, "scaled disc stays round")
        shortBitmap.recycle()
        // Group B that does not fit shows as much as fits and ends with an ellipsis.
        val narrow = IslandCapsuleView(targetContext, {}, {}, {}, {})
        narrow.configure(px(48f), 0f)
        val narrowWidth = narrow.layoutWidth(activity("Delivering to the north entrance"), px(140f), false)
        val shown = narrow.state().getString("text")
        assertThat(shown.endsWith("\u2026") && shown.length > 1 && narrowWidth <= px(140f), "long label is ellipsized, not dropped: $shown")
        assertThat(narrow.layoutWidth(activity("Ready"), px(220f), false) > 0 && narrow.state().getString("text") == "Ready", "short label stays whole")
        narrow.layoutWidth(activity("Delivering"), px(52f + 12f + 8f + 16f + 4f), false)
        assertThat(narrow.state().getString("text").isEmpty(), "no pill when not even one character and the ellipsis fit")
        // Two activities: the waiting circle is 32 dp on the left; the main capsule keeps its offset.
        val pair = IslandCapsuleView(targetContext, {}, {}, {}, {})
        pair.configure(px(48f), 0f)
        val pairWidth = pair.layoutWidth(activity("FL123"), px(300f), true)
        pair.bind(activity("FL123"), activity("A123").copy(key = "other"))
        val pairBitmap = render(pair, pairWidth, px(32f))
        assertThat(pair.state().getString("secondaryPosition") == "left", "waiting activity sits on the left")
        assertThat(pairBitmap.getPixel(px(16f), px(16f)) != 0 && Color.alpha(pairBitmap.getPixel(px(16f), px(1f))) == 255,
            "waiting circle is as tall as the main disc")
        assertThat(Color.alpha(pairBitmap.getPixel(px(34f), px(16f))) == 0, "a 4 dp gap separates it from the capsule")
        assertThat(pairBitmap.getPixel(px(36f + 52f), px(16f)) == Color.BLACK, "main camera keeps its 52 dp offset")
        File(targetContext.getExternalFilesDir(null), "frames/two-activities.png").outputStream().use { pairBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        pairBitmap.recycle()
    }
    /** The same production view survives app/card hiding, source changes and waiting-disc changes. */
    private fun summaryCases() {
        val d = targetContext.resources.displayMetrics.density
        fun px(value: Float) = (value * d).toInt()
        val primary = activity("A123")
        val live = primary.copy(key = "live", packageName = "delivery.app", source = IslandActivity.SOURCE_LIVE_UPDATE,
            payload = JSONObject(primary.payload.toString()).apply {
                getJSONObject("compact").remove("a") // The adapter may have no readable icon.
                getJSONObject("compact").getJSONObject("b").put("text", "12 min")
            })
        val waiting = primary.copy(key = "waiting")
        var taps = 0
        var next = 0
        val view = IslandCapsuleView(targetContext, { taps++ }, {}, { next++ }, {})
        host!!.content.addView(view, android.widget.FrameLayout.LayoutParams(px(300f), px(32f)))
        val phase = IslandCapsuleView::class.java.getDeclaredField("phase").apply { isAccessible = true }
        val animator = IslandCapsuleView::class.java.getDeclaredField("animation").apply { isAccessible = true }
        val frames = File(targetContext.getExternalFilesDir(null), "frames")
        fun render(name: String, item: IslandActivity, other: IslandActivity? = null): Bitmap {
            view.configure(px(52f), 36.25f)
            val width = view.layoutWidth(item, px(300f), other != null)
            view.bind(item, other)
            view.layout(0, 0, width, px(32f))
            view.visibility = View.VISIBLE
            phase.setFloat(view, 0f)
            return Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                view.draw(Canvas(bitmap))
                val preview = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                Canvas(preview).apply { drawColor(0xffb3c6dd.toInt()); drawBitmap(bitmap, 0f, 0f, null) }
                File(frames, "$name.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                preview.recycle()
            }
        }
        fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val single = render("summary-single", primary)
        val initial = pixels(single)
        for (entry in listOf("app-return", "card-collapse")) {
            view.visibility = View.GONE
            assertThat(animator.get(view) == null, "$entry stops hidden motion")
            val returned = render("summary-$entry", primary)
            assertThat(initial.contentEquals(pixels(returned)), "$entry restores identical capsule pixels")
            returned.recycle()
        }
        val pair = render("summary-two", primary, live)
        val lead = view.leadWidth()
        assertThat(pair.width == single.width + lead, "waiting circle adds only its own space")
        val pairedMain = IntArray(single.width * single.height)
        pair.getPixels(pairedMain, 0, single.width, lead, 0, single.width, single.height)
        assertThat(initial.indices.all { Color.alpha(initial[it]) == Color.alpha(pairedMain[it]) },
            "waiting Live Update cannot stretch or move the main outline")
        // Skia can round a few subpixel glyph edges by one channel level after translation.
        assertThat(initial.indices.all { index ->
            listOf(0, 8, 16).all { shift -> kotlin.math.abs((initial[index] shr shift and 255) - (pairedMain[index] shr shift and 255)) <= 1 }
        }, "adding a waiting activity preserves the main glyph and label")
        assertThat(view.state().getJSONObject("geometry").getDouble("secondaryGapDp") in 3.8..4.1,
            "waiting circle has the design's 4 dp gap")
        fun tap(x: Float) {
            val now = android.os.SystemClock.uptimeMillis()
            for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to now, android.view.MotionEvent.ACTION_UP to now + 30)) {
                val event = android.view.MotionEvent.obtain(now, time, action, x, view.height / 2f, 0)
                view.onTouchEvent(event); event.recycle()
            }
        }
        tap(16 * view.unit)
        assertThat(next == 1 && taps == 0, "waiting-circle tap switches activity, not the main card")
        val livePair = render("summary-live-update-waiting", live, waiting)
        assertThat(animator.get(view) != null, "a waiting AI animation runs even while the primary Live Update is static")
        val before = pixels(livePair)
        phase.setFloat(view, .375f)
        val changed = Bitmap.createBitmap(livePair.width, livePair.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(changed))
        assertThat(!before.contentEquals(pixels(changed)), "waiting animation paints different frames")
        val primaryBefore = IntArray((livePair.width - lead) * livePair.height)
        val primaryAfter = IntArray(primaryBefore.size)
        livePair.getPixels(primaryBefore, 0, livePair.width - lead, lead, 0, livePair.width - lead, livePair.height)
        changed.getPixels(primaryAfter, 0, changed.width - lead, lead, 0, changed.width - lead, changed.height)
        assertThat(primaryBefore.contentEquals(primaryAfter), "waiting animation leaves the primary Live Update untouched")
        view.visibility = View.GONE
        assertThat(animator.get(view) == null, "card expansion stops both discs' animation")
        view.visibility = View.VISIBLE
        assertThat(animator.get(view) != null, "card collapse resumes a waiting animation")
        val liveSingle = render("summary-live-update", live)
        assertThat(animator.get(view) == null && !view.state().getBoolean("secondary"),
            "ending the waiting activity clears its motion and state")
        val restored = render("summary-restored", primary)
        assertThat(initial.contentEquals(pixels(restored)), "switching sources restores the original geometry")
        val shortLabel = live.copy(payload = JSONObject(live.payload.toString()).apply {
            getJSONObject("compact").getJSONObject("b").put("text", "1")
        })
        val short = render("summary-one-character", shortLabel)
        assertThat(view.state().getJSONObject("geometry").getDouble("pillWidthDp") >= 32,
            "one-character Live Updates retain a round 32 dp end cap")
        assertThat(view.state().getJSONObject("geometry").getDouble("discDiameterDp") == 32.0,
            "all summary paths keep 32 dp side discs")
        listOf(single, pair, livePair, changed, liveSingle, restored, short).forEach(Bitmap::recycle)
        view.visibility = View.GONE
        host!!.content.removeView(view)
    }

    private fun ownershipCases() {
        val primary = activity("A123")
        val live = activity("12 min").copy(key = "live", source = IslandActivity.SOURCE_LIVE_UPDATE)
        val ownership = IslandPresentationOwnership
        ownership.show(primary, live)
        assertThat(ownership.state.value.notificationKeys == setOf(primary.key, live.key),
            "both drawn summaries replace their identical upstream chips")
        assertThat("unrelated" !in ownership.state.value.notificationKeys, "unrepresented chips remain upstream")
        ownership.show(live, primary)
        assertThat(ownership.state.value.notificationKeys == setOf(primary.key, live.key), "swapping summaries does not duplicate chips")
        ownership.show(primary)
        assertThat(ownership.state.value.notificationKeys == setOf(primary.key), "an expanded card owns only its own notification")
        val recording = primary.copy(key = "taku:system:recording", payload = JSONObject(primary.payload.toString()).put("systemSource", "recording"))
        ownership.show(live, recording)
        assertThat(ownership.state.value.sources == setOf("recording") && ownership.state.value.notificationKeys == setOf(live.key),
            "a waiting system source owns its matching system chip")
        ownership.show(null)
        assertThat(ownership.state.value.notificationKeys.isEmpty() && ownership.state.value.sources.isEmpty(),
            "hidden summaries release all upstream chips")
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
        // Template style: a white card switches text, chips and the glyph to black content tokens.
        val whitePayload = JSONObject(payload.toString())
        whitePayload.getJSONObject("expanded").put("backgroundColor", "#FFFFFF").put("accentColor", "#0A5BD3")
        val white = item.copy(payload = whitePayload)
        card.update(white)
        assertThat(card.state().getString("backgroundColor") == "#FFFFFF", "background colour applied")
        assertThat(IslandStyle.palette(white) == IslandStyle.LIGHT && IslandStyle.cardFill(white) == 0xebffffff.toInt(), "white card at 92 % with dark text")
        val firstChip = row.getChildAt(1) as android.widget.TextView
        assertThat(firstChip.currentTextColor == IslandStyle.LIGHT.primary, "secondary chips use dark text on a white card")
        assertThat(IslandStyle.palette(item) == IslandStyle.DARK && IslandStyle.cardFill(item) == IslandStyle.CARD, "default card stays Taku black")
    }

}
