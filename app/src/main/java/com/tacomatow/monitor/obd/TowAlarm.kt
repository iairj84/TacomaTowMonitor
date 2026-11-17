package com.tacomatow.monitor.obd

/**
 * Pure tow-alert policy for the foreground service.
 *
 * The service itself is Android framework code and untestable here; the
 * decisions — what counts as WARN/CRIT, when an excursion is new enough to
 * make noise, when it is over — live here so they are pinned by tests.
 *
 * Thresholds default to [TacomaPids] tow values (pan WARN 220 / CRIT 240,
 * converter WARN 230 / CRIT 250). A gauge that has never answered carries a
 * null temperature and never contributes: missing data must not page the
 * driver, and it must not hold a stale alarm forever either.
 */
object TowAlarm {

    enum class Level { NORMAL, WARN, CRIT }

    /** Drop this far below WARN before the excursion counts as over. */
    const val CLEAR_HYSTERESIS_F = 5f

    /** A silenced alarm stays quiet this long before it may fire again. */
    const val SILENCE_MS = 10L * 60L * 1000L

    /** Fastest the ongoing notification refreshes (level changes skip this). */
    const val MIN_UPDATE_MS = 2_000L

    fun levelFor(
        panF: Float?,
        convF: Float?,
        panWarn: Float = TacomaPids.TRANS_PAN.warnF ?: 220f,
        panCrit: Float = TacomaPids.TRANS_PAN.critF ?: 240f,
        convWarn: Float = TacomaPids.TRANS_CONVERTER.warnF ?: 230f,
        convCrit: Float = TacomaPids.TRANS_CONVERTER.critF ?: 250f,
    ): Level {
        val pan = severity(panF, panWarn, panCrit)
        val conv = severity(convF, convWarn, convCrit)
        return if (pan.ordinal > conv.ordinal) pan else conv
    }

    private fun severity(v: Float?, warn: Float, crit: Float): Level = when {
        v == null -> Level.NORMAL
        v >= crit -> Level.CRIT
        v >= warn -> Level.WARN
        else -> Level.NORMAL
    }

    /**
     * True only on a rising edge: NORMAL -> WARN, WARN -> CRIT, NORMAL -> CRIT.
     * A still-hot gauge on the next sweep is not a new excursion.
     */
    fun shouldFire(current: Level, latched: Level): Boolean =
        current.ordinal > latched.ordinal

    /**
     * True when nothing known-hot remains. Nulls never block clearing: if the
     * dongle dies mid-alarm the bus watchdog owns that failure, and holding a
     * CRIT banner forever over a dead link is the wrong answer.
     */
    fun cleared(
        panF: Float?,
        convF: Float?,
        panWarn: Float = TacomaPids.TRANS_PAN.warnF ?: 220f,
        convWarn: Float = TacomaPids.TRANS_CONVERTER.warnF ?: 230f,
    ): Boolean {
        val panOk = panF == null || panF < panWarn - CLEAR_HYSTERESIS_F
        val convOk = convF == null || convF < convWarn - CLEAR_HYSTERESIS_F
        return panOk && convOk
    }

    /** One-line glance for the collapsed notification. Never blank. */
    fun summaryLine(panF: Float?, convF: Float?): String {
        if (panF == null && convF == null) return "Waiting for adapter…"
        fun fmt(v: Float?): String = v?.let { "%.0f°F".format(it) } ?: "—"
        return "Pan ${fmt(panF)} · Conv ${fmt(convF)}"
    }

    fun statusWord(level: Level): String = when (level) {
        Level.CRIT -> "STOP — COOL DOWN"
        Level.WARN -> "EASE OFF — trans hot"
        Level.NORMAL -> "Temps normal"
    }

    fun advice(level: Level): String = when (level) {
        Level.CRIT -> "Stop and cool with the engine running."
        Level.WARN -> "Ease off — trans running hot."
        Level.NORMAL -> "Tap to open gauges."
    }
}
