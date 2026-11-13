package com.tacomatow.monitor.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tacomatow.monitor.obd.ObdHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One Pane with up to 4 tow rows. Refreshes at 5s — deliberately slow,
 * because the AA host throttles template updates and driving-safe UI
 * must not flicker at 1 Hz.
 */
class TowStatusScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                refreshJob?.cancel()
                refreshJob = scope.launch {
                    while (isActive) {
                        invalidate()
                        delay(5_000)
                    }
                }
            }
            override fun onPause(owner: LifecycleOwner) {
                refreshJob?.cancel()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val s = ObdHolder.repository.state.value
        val pane = Pane.Builder()

        if (!s.connected) {
            pane.addRow(
                Row.Builder()
                    .setTitle("Not connected")
                    .addText("Open Tacoma Tow Monitor on the phone and connect the ELM327.")
                    .build()
            )
        } else {
            val order = listOf("trans_pan", "trans_conv", "coolant", "oil")
            for (id in order) {
                val g = s.readings[id] ?: continue
                val value = g.valueF?.let { "%.0f%s".format(it, g.unit) } ?: "—"
                pane.addRow(
                    Row.Builder()
                        .setTitle("${g.label}: $value")
                        .addText(statusLine(g.status))
                        .build()
                )
            }
            if (s.readings.isEmpty()) {
                pane.addRow(
                    Row.Builder().setTitle("Warming up…")
                        .addText("First sweep takes a few seconds (701 header flips).")
                        .build()
                )
            }
        }

        pane.addAction(
            Action.Builder()
                .setTitle("Refresh")
                .setOnClickListener { invalidate() }
                .build()
        )

        return PaneTemplate.Builder(pane.build())
            .setTitle("Tacoma Tow Temps")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun statusLine(status: String?): String = when (status) {
        "CRIT" -> "CRITICAL — back off / stop and cool"
        "WARN" -> "Hot — ease off, watch the climb"
        "NO DATA" -> "ECU reports no data (oil is often unsupported)"
        else -> "OK"
    }

    @Suppress("unused")
    private fun spanFor(status: String?): CarColor = when (status) {
        "CRIT" -> CarColor.RED
        "WARN" -> CarColor.YELLOW
        else -> CarColor.DEFAULT
    }
}
