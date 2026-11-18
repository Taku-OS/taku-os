// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings

/** Keeps the idle translation entry alive across apps; settings owns the close action. */
class TranslationOverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val active=MediaTranslationController.isActive()
        val enabled=OverlayPreference.enabled(this)
        val shown=enabled && Settings.canDrawOverlays(this) && OverlayHost.showEntry(this)
        if(!shown && !active) {
            OverlayHost.closeEntry()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if(!shown)OverlayHost.closeEntry()
        return START_STICKY
    }

    override fun onDestroy() {
        OverlayHost.closeEntry()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Translation overlay",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,1,Intent(this,MediaTranslationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Realtime translation overlay").setContentText("Tap to open settings").setContentIntent(open)
            .setOngoing(true).build()
    }

    companion object {
        private const val CHANNEL="translation-overlay"
        private const val NOTIFICATION_ID=101
    }
}
