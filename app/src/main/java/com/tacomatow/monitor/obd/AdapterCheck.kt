package com.tacomatow.monitor.obd

/**
 * Turns a handful of probes into a plain-English verdict about whether an
 * adapter can read extended Toyota data at all.
 *
 * The distinction that matters: an adapter that *cannot* carry the request
 * looks identical to an ECU that *refuses* it — both show up as NO DATA
 * unless you look for a negative response (7F). A 7F 22 31 ("request out of
 * range") is actually GOOD news: the adapter worked, the truck simply
 * doesn't publish that identifier.
 */
enum class AdapterVerdict {
    NOT_CONNECTED,
    BASELINE_FAILED,
    EXTENDED_OK,
    ECU_REFUSED,
    SESSION_REFUSED,
    ADAPTER_LIMITED,
}

data class AdapterReport(
    val verdict: AdapterVerdict,
    val headline: String,
    val detail: String,
    val steps: List<String>,
    val identity: String?,
    val protocol: String?,
    val probes: List<Pair<String, String?>>,
    /** Classified silicon, so "can it be reflashed?" has an answer. */
    val firmware: FirmwareInfo? = null,
) {
    companion object {
        fun notConnected() = AdapterReport(
            verdict = AdapterVerdict.NOT_CONNECTED,
            headline = "Connect an adapter first",
            detail = "The check needs a live connection to the ELM327.",
            steps = listOf("Connect on the dashboard, ignition ON, then run the check."),
            identity = null,
            protocol = null,
            probes = emptyList(),
        )
    }
}

object AdapterCheck {

    private fun norm(raw: String?): String = raw.orEmpty().replace(" ", "").uppercase()

    fun evaluate(
        identity: String?,
        protocol: String?,
        baselineRaw: String?,
        sessionRaw: String?,
        transRaw: String?,
        bodyRaw: String?,
    ): AdapterReport {
        val baseline = norm(baselineRaw)
        val session = norm(sessionRaw)
        val trans = norm(transRaw)
        val body = norm(bodyRaw)

        val baselineWorks = baseline.contains("4100")
        val sessionOk = session.startsWith("5003") || (session.startsWith("50") && !session.contains("7F"))
        // A negative response proves the adapter delivered the request.
        val transNegative = trans.contains("7F22") || trans.contains("7F21")
        val bodyNegative = body.contains("7F21") || body.contains("7F22")
        val transAnswered = trans.contains("6216") || trans.contains("621A")
        val bodyAnswered = body.contains("6114") || body.contains("61")

        val probes = listOf(
            "Adapter (ATI)" to identity,
            "Protocol (ATDPN)" to protocol,
            "Mode 01 baseline (0100)" to baselineRaw,
            "Extended session (10 03)" to sessionRaw,
            "Transmission (22 1627 @701)" to transRaw,
            "Body ECU (21 14 @750)" to bodyRaw,
        )

        // 1. Nothing at all: connection or key problem, not a PID problem.
        if (!baselineWorks) {
            return AdapterReport(
                verdict = AdapterVerdict.BASELINE_FAILED,
                headline = "Not even basic data is coming through",
                detail = "A standard emissions request failed. This is a connection problem before it is a PID problem.",
                steps = listOf(
                    "Confirm the ignition is ON and the adapter's LED is lit.",
                    "Force-close Torque, Car Scanner or any other OBD app — these dongles accept one connection.",
                    "Reconnect, then run the check again.",
                ),
                identity = identity, protocol = protocol, probes = probes,
            )
        }

        // 2. Extended reads work: the good outcome.
        if (transAnswered || bodyAnswered) {
            val which = buildList {
                if (transAnswered) add("transmission temperature")
                if (bodyAnswered) add("body ECU customization blocks")
            }.joinToString(" and ")
            return AdapterReport(
                verdict = AdapterVerdict.EXTENDED_OK,
                headline = "This adapter reads extended Toyota data",
                detail = "It answered $which. The dashboard will use it automatically.",
                steps = listOf(
                    "Nothing to buy. Gauges populate on the next sweep.",
                    "If only customization worked, your truck may not publish the trans DID — that is a vehicle limit, not the adapter.",
                ),
                identity = identity, protocol = protocol, probes = probes,
            )
        }

        // 3. The ECU answered, but refused the identifier.
        if (transNegative || bodyNegative) {
            return AdapterReport(
                verdict = AdapterVerdict.ECU_REFUSED,
                headline = "Adapter is fine — the truck won't answer that identifier",
                detail = "The ECU sent a negative response (7F), which proves the request arrived. " +
                    "It simply does not publish this particular identifier for this calibration.",
                steps = listOf(
                    "Don't buy hardware — this is not an adapter limitation.",
                    "Open Diagnostics and try other identifiers, or compare against OBD Fusion's Toyota pack on the same truck.",
                ),
                identity = identity, protocol = protocol, probes = probes,
            )
        }

        // 4. Extended session refused.
        if (!sessionOk) {
            return AdapterReport(
                verdict = AdapterVerdict.SESSION_REFUSED,
                headline = "Adapter won't enter the extended diagnostic session",
                detail = "Manufacturer reads are commonly blocked outside that session, and this adapter " +
                    "rejected the request to enter it (or could not carry it).",
                steps = listOf(
                    "Retry with the engine running rather than key-on only.",
                    "Check 'Show adapter log' for `session: 10 03` — 'refused' confirms this verdict.",
                    "An STN-chip adapter (Kiwi 3, OBDLink MX+/CX) is the reliable fix if it stays refused.",
                ),
                identity = identity, protocol = protocol, probes = probes,
            )
        }

        // 5. Session fine, requests unanswered: the classic clone limitation.
        return AdapterReport(
            verdict = AdapterVerdict.ADAPTER_LIMITED,
            headline = "Adapter accepts the session but drops manufacturer traffic",
            detail = "The session opened and basic data flows, yet requests to the transmission and body " +
                "ECUs returned nothing at all. That is the signature of clone firmware that only " +
                "reassembles replies from the engine pair.",
            steps = listOf(
                "Confirm in the adapter log that every probe says NO DATA (not a negative response).",
                "A cheap Veepeak BLE (~$15, returnable) is worth one try before spending more.",
                "Known-good on 3rd-gen Tacomas: Kiwi 3 or OBDLink MX+ (~$100).",
                "Your current dongle still handles coolant, RPM, speed and trip logging.",
            ),
            identity = identity, protocol = protocol, probes = probes,
        )
    }
}
