package org.agentos.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Visible microphone lifetime independent of any generated view. */
class AudioCaptureService : Service() {
    private var startup: CompletableFuture<AudioCaptureService>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && "stop" == intent.action) {
            AudioSessionBridge.get(this).stopFromNotification()
            return START_NOT_STICKY
        }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(
                "matter-audio", "Meeting recording", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1,
                Intent(this, AudioCaptureService::class.java).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "matter-audio")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Taku is recording a meeting")
                .setContentText("Microphone is active. Tap Stop to finish and save.")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            startForeground(107, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            synchronized(AudioCaptureService::class.java) {
                startup = ready
                startup?.complete(this)
            }
        } catch (failure: Exception) {
            synchronized(AudioCaptureService::class.java) {
                ready?.completeExceptionally(failure)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(AudioCaptureService::class.java) {
            if (ready === startup) ready = null
        }
        AudioSessionBridge.get(this).captureHostDestroyed(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private var ready: CompletableFuture<AudioCaptureService>? = null

        @JvmStatic
        @Throws(Exception::class)
        fun acquire(context: Context): AudioCaptureService {
            val pending: CompletableFuture<AudioCaptureService>
            synchronized(AudioCaptureService::class.java) {
                if (ready == null) ready = CompletableFuture()
                pending = ready!!
                context.startForegroundService(Intent(context, AudioCaptureService::class.java))
            }
            return pending.get(10, TimeUnit.SECONDS)
        }

        @JvmStatic
        fun release(context: Context) {
            synchronized(AudioCaptureService::class.java) { ready = null }
            context.stopService(Intent(context, AudioCaptureService::class.java))
        }
    }
}
