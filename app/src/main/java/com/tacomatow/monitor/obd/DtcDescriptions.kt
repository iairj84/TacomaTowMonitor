package com.tacomatow.monitor.obd

/**
 * Plain-English meanings for generic trouble codes, written fresh for this app.
 *
 * The code NUMBERS are the SAE J1979 standard — P0301 is cylinder-1 misfire
 * on every car — but the WORDS here are our own, not copied from any
 * scan-tool vendor, manual or database. Manufacturer codes (P1xxx,
 * P30xx–P33xx, B/C/U 1xxx and up) mean different things per maker, so they
 * get no entry: the UI says so instead of guessing.
 *
 * Coverage is three layers: exact entries for the codes a Tacoma owner will
 * actually meet, generators for regular families (cylinders, oxygen
 * sensors), and SAE family text for everything else generic.
 */
object DtcDescriptions {

    /**
     * Exact meaning, or null for manufacturer-specific and malformed codes.
     * Null means "look this one up against a Toyota reference", never a guess.
     */
    fun describe(code: String): String? {
        val c = code.uppercase()
        if (c.length != 5 || c[0] !in "PCBU") return null
        if (c[1] == '1') return null // P1xxx, B1xxx, C1xxx, U1xxx: per-maker
        if (c[0] == 'P' && c[1] == '3' && c[2] in '0'..'3') return null // P30xx–P33xx
        EXACT[c]?.let { return it }
        generated(c)?.let { return it }
        FAMILY[c.take(3)]?.let { return it }
        FAMILY[c.take(2)]?.let { return it }
        return when (c[0]) {
            'P' -> "Generic powertrain code"
            'B' -> "Generic body code"
            'C' -> "Generic chassis code"
            else -> "Generic network code"
        }
    }

    private fun generated(c: String): String? {
        if (c[0] != 'P' || c[1] != '0') return null
        val v = c.substring(1).toIntOrNull() ?: return null
        // Oxygen sensors come in blocks of six: circuit, low, high, slow,
        // no activity, heater — bank 1 sensor 1, bank 1 sensor 2, then bank 2.
        if (v in 130..141 || v in 150..161) {
            val base = if (v < 150) v - 130 else v - 150
            val bank = if (v < 150) "bank 1" else "bank 2"
            val sensor = if (base < 6) "sensor 1" else "sensor 2"
            val what = when (base % 6) {
                0 -> "circuit fault"
                1 -> "signal stuck low"
                2 -> "signal stuck high"
                3 -> "slow response"
                4 -> "no activity"
                else -> "heater circuit fault"
            }
            return "Oxygen sensor $what, $bank $sensor"
        }
        // The last two digits are decimal cylinder numbers. Misfires and coils
        // share the same third digit: P0301–P0312 are misfires, P0351–P0358
        // are the coils for the same cylinders.
        val num = c.substring(3).toIntOrNull() ?: return null
        return when (c[2]) {
            '2' -> if (num in 1..8) "Fuel injector circuit, cylinder $num" else null
            '3' -> when {
                num in 1..12 -> "Cylinder $num misfire detected"
                num in 51..58 -> "Ignition coil circuit, cylinder ${num - 50}"
                else -> null
            }
            else -> null
        }
    }

    private val FAMILY = mapOf(
        "P00" to "Fuel and air metering, or an auxiliary emission control",
        "P01" to "Fuel and air metering",
        "P02" to "Fuel and air metering (fuel injectors)",
        "P03" to "Ignition system or engine misfire",
        "P04" to "Auxiliary emission control",
        "P05" to "Idle control, engine speed or auxiliary input",
        "P06" to "Engine computer or its output circuits",
        "P07" to "Automatic transmission",
        "P08" to "Automatic transmission",
        "P09" to "Powertrain component",
        "P0A" to "Hybrid propulsion system",
        "P2" to "Fuel and air metering (injectors and mixture)",
        "B0" to "Body system (locks, lights, airbags, wipers)",
        "C0" to "Chassis system (ABS, traction or stability control)",
        "U0" to "Vehicle network communication",
    )

    private val EXACT = mapOf(
        // Intake air and fuel metering.
        "P0100" to "Mass air flow sensor circuit fault",
        "P0101" to "Mass air flow sensor range or performance",
        "P0102" to "Mass air flow sensor signal low",
        "P0103" to "Mass air flow sensor signal high",
        "P0105" to "Manifold pressure circuit fault",
        "P0106" to "Manifold pressure range or performance",
        "P0107" to "Manifold pressure signal low",
        "P0108" to "Manifold pressure signal high",
        "P0110" to "Intake air temperature sensor circuit fault",
        "P0111" to "Intake air temperature range or performance",
        "P0112" to "Intake air temperature signal low",
        "P0113" to "Intake air temperature signal high",
        "P0115" to "Engine coolant temperature circuit fault",
        "P0116" to "Engine coolant temperature range or performance",
        "P0117" to "Engine coolant temperature signal low",
        "P0118" to "Engine coolant temperature signal high",
        "P0120" to "Throttle position sensor circuit fault",
        "P0121" to "Throttle position range or performance",
        "P0122" to "Throttle position signal low",
        "P0123" to "Throttle position signal high",
        "P0125" to "Coolant too cold for closed-loop fuel control",
        "P0128" to "Coolant below thermostat regulating temperature",
        "P0171" to "Fuel trim lean, bank 1",
        "P0172" to "Fuel trim rich, bank 1",
        "P0174" to "Fuel trim lean, bank 2",
        "P0175" to "Fuel trim rich, bank 2",
        "P0190" to "Fuel rail pressure sensor circuit fault",
        "P0191" to "Fuel rail pressure range or performance",
        "P0192" to "Fuel rail pressure signal low",
        "P0193" to "Fuel rail pressure signal high",
        "P0200" to "Fuel injector circuit fault",
        // Misfire and ignition.
        "P0300" to "Random or multiple cylinder misfire",
        "P0316" to "Misfire detected during starting",
        "P0325" to "Knock sensor circuit, bank 1",
        "P0328" to "Knock sensor signal high, bank 1",
        "P0330" to "Knock sensor circuit, bank 2",
        "P0335" to "Crankshaft position sensor circuit fault",
        "P0340" to "Camshaft position sensor circuit, bank 1",
        // Emissions.
        "P0400" to "Exhaust gas recirculation flow fault",
        "P0401" to "Exhaust gas recirculation flow low",
        "P0420" to "Catalyst efficiency low, bank 1",
        "P0430" to "Catalyst efficiency low, bank 2",
        "P0440" to "Evaporative emission leak (general)",
        "P0441" to "Evaporative purge flow wrong",
        "P0442" to "Evaporative small leak",
        "P0443" to "Evaporative purge valve circuit fault",
        "P0446" to "Evaporative vent valve fault",
        "P0451" to "Evaporative pressure sensor range or performance",
        "P0452" to "Evaporative pressure sensor signal low",
        "P0453" to "Evaporative pressure sensor signal high",
        "P0455" to "Evaporative large leak",
        "P0456" to "Evaporative very small leak",
        "P0457" to "Evaporative leak found with the cap off — check the fuel cap first",
        // Speed, idle, voltage.
        "P0500" to "Vehicle speed sensor fault",
        "P0505" to "Idle control fault",
        "P0507" to "Idle speed higher than expected",
        "P0520" to "Engine oil pressure sensor circuit fault",
        "P0560" to "System voltage fault",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0606" to "Engine computer internal fault",
        // Transmission.
        "P0218" to "Transmission fluid overheated",
        "P0700" to "Transmission computer requested the check-engine light",
        "P0705" to "Transmission range sensor fault",
        "P0710" to "Transmission fluid temperature sensor circuit fault",
        "P0711" to "Transmission fluid temperature range or performance",
        "P0712" to "Transmission fluid temperature signal low",
        "P0713" to "Transmission fluid temperature signal high",
        "P0715" to "Transmission input speed sensor circuit fault",
        "P0720" to "Transmission output speed sensor circuit fault",
        "P0730" to "Wrong gear ratio",
        "P0740" to "Torque converter clutch circuit fault",
        "P0741" to "Torque converter clutch stuck off",
        "P0748" to "Pressure control solenoid fault",
        "P0750" to "Shift solenoid A fault",
        "P0755" to "Shift solenoid B fault",
        "P0780" to "Shift fault",
        // Network.
        "U0073" to "A control module has dropped off the communication bus",
        "U0100" to "Lost communication with the engine computer",
        "U0101" to "Lost communication with the transmission computer",
        "U0129" to "Lost communication with the brake control module",
        "U0140" to "Lost communication with the body computer",
        "U0155" to "Lost communication with the instrument cluster",
    )
}
