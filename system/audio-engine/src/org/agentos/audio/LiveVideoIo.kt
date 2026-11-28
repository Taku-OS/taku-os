// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.Executor

/** The audio host changes its foreground-service types as camera or projection capture starts and stops. */
internal interface LiveVideoHost {
    fun videoForeground(camera: Boolean, projection: Boolean)
}

/**
 * Screen or camera frames for one Live Talk lease, hardware encoded for the provider. All capture and
 * codec work runs serially on one video thread; switching sources fully releases the previous one first.
 *
 * Screen capture mirrors the default display into a non-secure virtual display, so SurfaceFlinger
 * leaves `FLAG_SECURE` windows (passwords, payments, DRM) black. The platform-signed system build holds
 * `CAPTURE_VIDEO_OUTPUT` and mirrors without a prompt; any other install falls back to the standard
 * MediaProjection consent. Frames are capped at [MAX_FPS] while the picture changes, and a static picture
 * repeats roughly every [REPEAT_US] so the provider keeps a current view.
 */
internal class LiveVideoIo(private val context: Context, private val listener: Listener) {
    interface Listener {
        /** One encoded frame (H.264 Annex B with SPS/PPS on key frames, or a raw VP8 frame). Video thread. */
        fun frame(source: String, monotonicUs: Long, keyFrame: Boolean, data: ByteArray)
        /** Capture ended without being asked (projection revoked, camera lost, codec error). Video thread. */
        fun stopped(source: String, reason: String)
    }

    private val thread = HandlerThread("audio-live-video").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var config: ByteArray? = null
    private var mime = MediaFormat.MIMETYPE_VIDEO_AVC
    private var generation = 0L
    @Volatile var source = "none"
        private set

    /** Starts [source] (`screen` or `camera`) encoded as [codecName] (`h264` or `vp8`), replacing any current source. */
    fun start(source: String, codecName: String) {
        require(source == "screen" || source == "camera")
        handler.post {
            stopNow()
            val current = ++generation
            this.source = source
            try {
                mime = if(codecName == "vp8") MediaFormat.MIMETYPE_VIDEO_VP8 else MediaFormat.MIMETYPE_VIDEO_AVC
                if(source == "screen") startScreen(current) else startCamera(current)
            } catch(error: Exception) {
                fail(current, (error as? AudioFailure)?.code ?: "CAPTURE_FAILED", error)
            }
        }
    }

    fun stop() { handler.post { stopNow() } }

    fun requestKeyframe() {
        handler.post { runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }
    }

    fun release() {
        handler.post { stopNow() }
        thread.quitSafely()
    }

    private fun fail(current: Long, reason: String, error: Exception? = null) {
        if(current != generation) return
        Log.w("AudioLive", "video source=$source failure=$reason detail=${error?.javaClass?.simpleName ?: ""}")
        val stoppedSource = source
        stopNow()
        listener.stopped(stoppedSource, reason)
    }

    private fun startScreen(current: Long) {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val (width, height) = scaled(bounds.width(), bounds.height())
        val dpi = context.resources.displayMetrics.densityDpi
        val surface = encoder(current, width, height)
        if(context.checkSelfPermission(CAPTURE_VIDEO_OUTPUT) == PackageManager.PERMISSION_GRANTED) {
            // System build: mirror the default display directly, no consent prompt.
            display = context.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay("taku-live-screen", width, height, dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
                ?: throw AudioFailure("CAPTURE_FAILED", "Screen mirroring is unavailable")
            Log.i("AudioLive", "video screen path=system size=${width}x$height")
            return
        }
        // Development install: ask once with the standard MediaProjection consent.
        LiveProjection.request(context) { grant ->
            handler.post {
                if(current != generation) return@post
                if(grant == null) { fail(current, "PERMISSION_DENIED"); return@post }
                try {
                    (context as? LiveVideoHost)?.videoForeground(camera = false, projection = true)
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    val granted = manager.getMediaProjection(grant.first, grant.second)
                        ?: throw AudioFailure("PERMISSION_DENIED", "Screen sharing was not allowed")
                    projection = granted
                    granted.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() { handler.post { fail(current, "PROJECTION_STOPPED") } }
                    }, handler)
                    display = granted.createVirtualDisplay("taku-live-screen", width, height, dpi, 0, surface, null, handler)
                        ?: throw AudioFailure("CAPTURE_FAILED", "Screen sharing is unavailable")
                    Log.i("AudioLive", "video screen path=projection size=${width}x$height")
                } catch(error: Exception) {
                    fail(current, (error as? AudioFailure)?.code ?: "CAPTURE_FAILED", error)
                }
            }
        }
    }

    private fun startCamera(current: Long) {
        if(context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw AudioFailure("PERMISSION_DENIED", "Allow Taku Audio to use the camera")
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: throw AudioFailure("CAPTURE_FAILED", "No camera is available")
        val sizes = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
        val size = sizes.filter { maxOf(it.width, it.height) <= MAX_LONG_SIDE }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height } ?: throw AudioFailure("CAPTURE_FAILED", "No camera size is available")
        // The encoder surface must match a size the camera supports exactly.
        val surface = encoder(current, size.width, size.height)
        (context as? LiveVideoHost)?.videoForeground(camera = true, projection = false)
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if(current != generation) { device.close(); return }
                camera = device
                val output = OutputConfiguration(surface)
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(output), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if(current != generation) { configured.close(); return }
                            session = configured
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(surface) }.build()
                            runCatching { configured.setRepeatingRequest(request, null, handler) }
                                .onFailure { fail(current, "CAPTURE_FAILED", it as? Exception) }
                            Log.i("AudioLive", "video camera=$id size=${size.width}x${size.height}")
                        }
                        override fun onConfigureFailed(failed: CameraCaptureSession) { fail(current, "CAPTURE_FAILED") }
                    }))
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); fail(current, "CAMERA_UNAVAILABLE") }
            override fun onError(device: CameraDevice, error: Int) { device.close(); fail(current, "CAMERA_UNAVAILABLE") }
        })
    }

    /** A surface-input hardware encoder whose output is delivered on the video thread. */
    private fun encoder(current: Long, width: Int, height: Int): Surface {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, MAX_FPS.toInt())
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, MAX_FPS)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_US)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_INTERVAL_S)
            if(mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                // Baseline has no B-frames, as the provider requires.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }
        val created = MediaCodec.createEncoderByType(mime)
        codec = created
        config = null
        created.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if(current != generation) return
                val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull()
                val bytes = if(buffer != null && info.size > 0) ByteArray(info.size).also {
                    buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(it)
                } else null
                runCatching { codec.releaseOutputBuffer(index, false) }
                if(bytes == null) return
                if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { config = bytes; return }
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val data = if(key && mime == MediaFormat.MIMETYPE_VIDEO_AVC) config?.plus(bytes) ?: bytes else bytes
                listener.frame(source, info.presentationTimeUs, key, data)
            }
            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) { fail(current, "CAPTURE_FAILED", error) }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = created.createInputSurface()
        input = surface
        created.start()
        return surface
    }

    private fun stopNow() {
        generation++
        val wasCamera = camera != null
        val wasProjection = projection != null
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { display?.release() }; display = null
        runCatching { projection?.stop() }; projection = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { input?.release() }; input = null
        config = null
        source = "none"
        if(wasCamera || wasProjection) (context as? LiveVideoHost)?.videoForeground(camera = false, projection = false)
    }

    companion object {
        const val CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val MAX_LONG_SIDE = 720
        const val MAX_FPS = 2f
        const val REPEAT_US = 3_000_000L
        const val BIT_RATE = 400_000
        const val KEY_INTERVAL_S = 10

        const val SNAPSHOT_LONG_SIDE = 1280

        /**
         * One still image of the screen the user is sharing, as a PNG on the read end of a pipe. It
         * mirrors the default display through its own non-secure virtual display, so `FLAG_SECURE`
         * windows stay black. Requires the system build's `CAPTURE_VIDEO_OUTPUT`. Blocks up to 1.5 s.
         */
        fun snapshot(context: Context): android.os.ParcelFileDescriptor {
            if(context.checkSelfPermission(CAPTURE_VIDEO_OUTPUT) != PackageManager.PERMISSION_GRANTED)
                throw AudioFailure("SCREENSHOT_UNAVAILABLE", "Screen snapshots need the Taku OS system build")
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val (width, height) = scaled(bounds.width(), bounds.height(), SNAPSHOT_LONG_SIDE)
            val thread = HandlerThread("audio-live-snapshot").apply { start() }
            val reader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val captured = java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap?>()
            val ready = java.util.concurrent.CountDownLatch(1)
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    if(captured.get() != null) return@use
                    val plane = image.planes[0]
                    val padded = plane.rowStride / plane.pixelStride
                    val full = android.graphics.Bitmap.createBitmap(padded, height, android.graphics.Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(plane.buffer)
                    captured.set(if(padded == width) full else android.graphics.Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() })
                    ready.countDown()
                }
            }, Handler(thread.looper))
            val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay("taku-live-snapshot", width, height,
                context.resources.displayMetrics.densityDpi, reader.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
            val bitmap = try {
                if(!ready.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) throw AudioFailure("SCREENSHOT_UNAVAILABLE", "The screen could not be captured")
                captured.get()!!
            } finally {
                runCatching { display?.release() }; runCatching { reader.close() }; thread.quitSafely()
            }
            val (read, write) = android.os.ParcelFileDescriptor.createPipe()
            Thread({
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(write).use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                bitmap.recycle()
            }, "audio-live-snapshot-png").start()
            return read
        }

        /** Scales to at most [longSide] on the long side, keeping the aspect ratio, in multiples of 16. */
        fun scaled(width: Int, height: Int, longSide: Int = MAX_LONG_SIDE): Pair<Int, Int> {
            require(width > 0 && height > 0)
            val factor = minOf(1.0, longSide.toDouble() / maxOf(width, height))
            fun align(value: Int) = maxOf(16, (value * factor).toInt() / 16 * 16)
            return align(width) to align(height)
        }
    }
}

/** Hands one MediaProjection consent result from [LiveProjectionActivity] to the waiting capture. */
internal object LiveProjection {
    private var pending: ((Pair<Int, Intent>?) -> Unit)? = null

    @Synchronized fun request(context: Context, result: (Pair<Int, Intent>?) -> Unit) {
        pending?.invoke(null)
        pending = result
        context.startActivity(Intent(context, LiveProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Synchronized fun deliver(code: Int, data: Intent?) {
        val result = pending ?: return
        pending = null
        result(if(code == Activity.RESULT_OK && data != null) code to data else null)
    }
}

/** Transparent host for the system's screen-sharing consent; finishes as soon as the user answers. */
class LiveProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState == null)
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        LiveProjection.deliver(resultCode, data)
        finish()
    }

    override fun onDestroy() {
        if(isFinishing) LiveProjection.deliver(RESULT_CANCELED, null)
        super.onDestroy()
    }
}
