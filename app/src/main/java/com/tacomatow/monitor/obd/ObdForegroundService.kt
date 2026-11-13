package com.tacomatow.monitor.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Keeps the ELM327 poll alive with the screen off while towing. */
class ObdForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tow monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Tacoma Tow Monitor")
            .setContentText("Reading trans temps from ELM327…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, note)
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "obd_tow"
    }
}
