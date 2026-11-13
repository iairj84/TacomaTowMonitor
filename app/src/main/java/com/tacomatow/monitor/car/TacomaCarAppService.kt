package com.tacomatow.monitor.car

import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point. The host (car / DHU) binds here.
 *
 * HONEST LIMITS — read before expecting Torque-on-the-dash:
 * - The Car App Library has no vehicle-health / OBD / custom-gauge
 *   category. We declare IOT (connected-device readout), which is the
 *   closest supported fit. Google Play review will very likely REJECT a
 *   public listing under that category for this use.
 * - The host renders our templates; we get slow text rows (List/Pane),
 *   refreshed at ~5s. No dials, graphs, or 1 Hz gauges.
 * - "Unknown sources" sideloading does NOT apply to Car App Library apps.
 *   For YOUR truck use internal app sharing / internal test track, or the
 *   DHU while developing. Full gauges stay on the phone (mount it).
 * - If you want mirrors of the phone screen on the head unit, that is a
 *   separate screen-mirroring app path (Fermata / Screen2Auto style),
 *   outside this codebase and outside Play policy.
 */
class TacomaCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: android.content.Intent): Screen =
            TowStatusScreen(carContext)
    }
}
