package com.tacomatow.monitor.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tacomatow.monitor.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the ELM327 poll alive with the screen off while towing, with the two
 * things a background poll otherwise lacks: a glanceable temperature readout
 * on the lockscreen/shade (Maps is in front, not this app) and a heads-up
 * alarm when the transmission crosses WARN/CRIT.
 *
 * The poll loop itself lives in [ObdRepository]; this service only holds a
 * foreground slot and mirrors [ObdHolder.repository] state into two
 * notifications: an ongoing LOW readout (updated at most every 2 s, instantly
 * on a level change) and a HIGH alarm posted once per rising edge. The alarm
 * latches until both temps fall back below WARN minus hysteresis, so a gauge
 * hovering on the line buzzes once instead of every sweep. "Silence 10 min"
 * snoozes re-fires without clearing the latch.
 *
 * MainActivity starts this on connect and stops it on disconnect; the service
 * is START_STICKY so a killed process comes back as "waiting for adapter"
 * until the app re-opens and auto-reconnect rebuilds the session.
 */
class ObdForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null

    @Volatile private var latched: TowAlarm.Level = TowAlarm.Level.NORMAL
    @Volatile private var silencedUntil = 0L
    @Volatile private var lastOngoingAt = 0L
    @Volatile private var lastTitle = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tow monitoring", NotificationManager.IMPORTANCE_LOW),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarm = NotificationChannel(
                ALARM_CHANNEL, "Trans temp alarms", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Fires once when transmission temp crosses WARN/CRIT."
                enableVibration(true)
            }
            mgr.createNotificationChannel(alarm)
        }
        promote(
            Notification.Builder(this, CHANNEL)
                .setContentTitle("Tacoma Tow Monitor")
                .setContentText("Reading trans temps from ELM327…")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(openApp())
                .setOngoing(true)
                .build(),
        )
        collectJob = scope.launch {
            ObdHolder.repository.state.collect { s ->
                val panF = s.readings["trans_pan"]?.valueF
                val convF = s.readings["trans_conv"]?.valueF
                val level = TowAlarm.levelFor(panF, convF)
                val previous = latched
                val now = System.currentTimeMillis()

                val title = when {
                    s.demoMode -> "Demo · ${TowAlarm.summaryLine(panF, convF)}"
                    !s.connected -> "Tacoma Tow Monitor — waiting for adapter"
                    else -> TowAlarm.summaryLine(panF, convF)
                }
                if (level != previous || now - lastOngoingAt >= TowAlarm.MIN_UPDATE_MS ||
                    title != lastTitle
                ) {
                    lastOngoingAt = now
                    lastTitle = title
                    try {
                        mgr.notify(ONGOING_ID, buildOngoing(s, title, level))
                    } catch (_: Exception) {
                    }
                }

                if (TowAlarm.shouldFire(level, previous) && now >= silencedUntil &&
                    (s.connected || s.demoMode)
                ) {
                    try {
                        mgr.notify(ALARM_ID, buildAlarm(panF, convF, level))
                    } catch (_: Exception) {
                        // No notification permission on 33+: the ongoing slot
                        // still shows, the heads-up just never arrives.
                    }
                }
                if (level == TowAlarm.Level.NORMAL && previous != TowAlarm.Level.NORMAL &&
                    TowAlarm.cleared(panF, convF)
                ) {
                    try {
                        mgr.cancel(ALARM_ID)
                    } catch (_: Exception) {
                    }
                }
                latched = level
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SILENCE -> {
                silencedUntil = System.currentTimeMillis() + TowAlarm.SILENCE_MS
                try {
                    getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
                } catch (_: Exception) {
                }
            }
            ACTION_STOP -> {
                try {
                    getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
                } catch (_: Exception) {
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        collectJob = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun promote(note: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ONGOING_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(ONGOING_ID, note)
        }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(action: String, code: Int, label: String, icon: Int): Notification.Action {
        val intent = Intent(this, ObdForegroundService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            this, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(icon, label, pending).build()
    }

    private fun buildOngoing(
        s: ObdUiState,
        title: String,
        level: TowAlarm.Level,
    ): Notification {
        val coolantF = s.readings["coolant"]?.valueF
        val volts = s.voltage
        val detail = buildString {
            append(TowAlarm.statusWord(level))
            append(" · ")
            append(TowAlarm.summaryLine(s.readings["trans_pan"]?.valueF, s.readings["trans_conv"]?.valueF))
            coolantF?.let { append(" · Coolant %.0f°F".format(it)) }
            volts?.let { append(" · %.1f V".format(it)) }
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(TowAlarm.statusWord(level))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .addAction(
                serviceAction(
                    ACTION_STOP, 1, "Stop",
                    android.R.drawable.ic_menu_close_clear_cancel,
                ),
            )
            .build()
    }

    private fun buildAlarm(
        panF: Float?,
        convF: Float?,
        level: TowAlarm.Level,
    ): Notification {
        val hot = maxOf(panF ?: Float.MIN_VALUE, convF ?: Float.MIN_VALUE)
        val title = when (level) {
            TowAlarm.Level.CRIT -> "Trans temp CRIT — %.0f°F".format(hot)
            TowAlarm.Level.WARN -> "Trans temp WARN — %.0f°F".format(hot)
            TowAlarm.Level.NORMAL -> "Trans temps normal"
        }
        val body = "${TowAlarm.summaryLine(panF, convF)}. ${TowAlarm.advice(level)}"
        return Notification.Builder(this, ALARM_CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openApp())
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_ALARM)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .addAction(
                serviceAction(
                    ACTION_SILENCE, 2, "Silence 10 min",
                    android.R.drawable.ic_lock_idle_alarm,
                ),
            )
            .build()
    }

    companion object {
        const val CHANNEL = "obd_tow"
        const val ALARM_CHANNEL = "tow_alarm"
        const val ONGOING_ID = 1
        const val ALARM_ID = 2
        const val ACTION_STOP = "com.tacomatow.monitor.obd.STOP"
        const val ACTION_SILENCE = "com.tacomatow.monitor.obd.SILENCE_10MIN"
    }
}
