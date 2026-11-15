package com.tacomatow.monitor.obd

/**
 * Settings a Tacoma owner can actually change, so a recorded diff can be named
 * from a list instead of typed from memory.
 *
 * The names here are short technical descriptions of functions, grouped the way
 * the factory radio and dealer tools group them. They are a checklist, not a
 * payload table: the app still learns the bytes from your own truck, and knowing
 * that a setting exists says nothing about which block it lives in.
 *
 * Wording is deliberately this project's own shorthand rather than a copy of any
 * vendor's menu text.
 */
data class CatalogSetting(
    val category: String,
    val name: String,
    /** Known values, when the option is a small list. Empty means free text. */
    val values: List<String> = emptyList(),
)

object CustomizeCatalogue {

    private const val LOCKS_AUTO = "Locking: automatic"
    private const val LOCKS_BEEP = "Locking: beep & blink"
    private const val LOCKS_DOORS = "Locking: doors & alarm"
    private const val LOCKS_KEY = "Locking: smart key"
    private const val LIGHTS_EXT = "Lights: exterior"
    private const val LIGHTS_AUTO = "Lights: automatic"
    private const val LIGHTS_INT = "Lights: interior"
    private const val WINDOWS = "Windows & sunroof"
    private const val CLUSTER = "Instruments & nav"
    private const val HVAC = "Heater & A/C"
    private const val ASSIST = "Driver assist"
    private const val PARKING = "Parking sensors"
    private const val DINGS = "Dings & warnings"
    private const val OTHER = "Other"

    val all: List<CatalogSetting> = listOf(
        // Dings & warnings
        CatalogSetting(DINGS, "Driver seat belt ding", listOf("On", "Off")),
        CatalogSetting(DINGS, "Front passenger seat belt ding", listOf("On", "Off")),
        CatalogSetting(DINGS, "Key left in car ding", listOf("On", "Off")),
        CatalogSetting(DINGS, "Headlights left on ding", listOf("On", "Off")),
        CatalogSetting(DINGS, "Key-in-ignition ding speed", listOf("Slow", "Medium", "Fast")),
        CatalogSetting(DINGS, "Remote low battery warning", listOf("On", "Off")),

        // Locking
        CatalogSetting(LOCKS_AUTO, "Auto-lock all doors", listOf("Off", "On shift from P", "On reaching speed")),
        CatalogSetting(LOCKS_AUTO, "Auto-unlock all doors", listOf("Off", "On shift to P", "On driver door open")),
        CatalogSetting(LOCKS_AUTO, "Wireless door locking", listOf("On", "Off")),
        CatalogSetting(LOCKS_AUTO, "Re-lock if no door opened", listOf("Yes", "No")),
        CatalogSetting(LOCKS_AUTO, "Re-lock after", listOf("30 s", "60 s", "120 s")),
        CatalogSetting(LOCKS_BEEP, "Beep on lock/unlock", listOf("Yes", "No")),
        CatalogSetting(LOCKS_BEEP, "Beep volume", listOf("Off", "1", "2", "3", "4", "5", "6", "Max")),
        CatalogSetting(LOCKS_BEEP, "Blink on lock/unlock", listOf("Yes", "No")),
        CatalogSetting(LOCKS_DOORS, "Unlock via remote", listOf("Driver first", "All at once")),
        CatalogSetting(LOCKS_DOORS, "Unlock via physical key", listOf("Driver first", "All at once")),
        CatalogSetting(LOCKS_DOORS, "Unlock via smart-key handle", listOf("Driver first", "All at once")),
        CatalogSetting(LOCKS_DOORS, "Max consecutive lock/unlock cycles", listOf("Unlimited", "2")),
        CatalogSetting(LOCKS_DOORS, "Warn when a door cannot lock", listOf("Yes", "No")),
        CatalogSetting(LOCKS_DOORS, "Warn when sunroof is open", listOf("Yes", "No")),
        CatalogSetting(LOCKS_KEY, "Key search stops after", listOf()),
        CatalogSetting(LOCKS_KEY, "No re-open for", listOf("0.5 s", "1.5 s", "2.5 s", "5 s")),
        CatalogSetting(LOCKS_KEY, "Smart-key start location", listOf("Front seats", "Anywhere in vehicle")),

        // Lights
        CatalogSetting(LIGHTS_EXT, "Daytime running lights", listOf("On", "Off")),
        CatalogSetting(LIGHTS_EXT, "Lane-change flash count", listOf("Off", "3", "4", "5", "6", "7")),
        CatalogSetting(LIGHTS_EXT, "Coming-home light duration", listOf("Off", "30 s", "60 s", "90 s")),
        CatalogSetting(
            LIGHTS_AUTO,
            "Auto-headlight threshold",
            listOf("Darkest", "Darker", "Normal", "Lighter", "Lightest"),
        ),
        CatalogSetting(LIGHTS_INT, "Interior light control", listOf("On", "Off")),
        CatalogSetting(LIGHTS_INT, "Interior lights on engine off", listOf("Yes", "No")),
        CatalogSetting(LIGHTS_INT, "Interior lights on unlock", listOf("Yes", "No")),
        CatalogSetting(LIGHTS_INT, "Interior lights on key in range", listOf("Yes", "No")),
        CatalogSetting(LIGHTS_INT, "Interior dim after", listOf("7 s", "15 s", "30 s")),

        // Windows
        CatalogSetting(WINDOWS, "Driver window one-touch open", listOf("Yes", "No")),
        CatalogSetting(WINDOWS, "Driver window one-touch close", listOf("Yes", "No")),
        CatalogSetting(WINDOWS, "Passenger window one-touch open", listOf("Yes", "No")),
        CatalogSetting(WINDOWS, "Passenger window one-touch close", listOf("Yes", "No")),
        CatalogSetting(WINDOWS, "Driver switch opens passenger window", listOf("Yes", "No")),
        CatalogSetting(WINDOWS, "Driver switch closes passenger window", listOf("Yes", "No")),

        // Cluster
        CatalogSetting(
            CLUSTER,
            "Instrument dimming sensitivity",
            listOf("Darkest", "Darker", "Normal", "Lighter", "Lightest"),
        ),
        CatalogSetting(
            CLUSTER,
            "Instrument restoring sensitivity",
            listOf("Darkest", "Darker", "Normal", "Lighter", "Lightest"),
        ),
        CatalogSetting(CLUSTER, "Engine start indicator", listOf("On", "Off")),
        CatalogSetting(CLUSTER, "Odometer after ignition off", listOf("Off", "30 s", "1 min", "10 min")),
        CatalogSetting(CLUSTER, "Microphone sensitivity", listOf()),
        CatalogSetting(CLUSTER, "Speaker volume", listOf()),

        // HVAC
        CatalogSetting(HVAC, "A/C on with AUTO", listOf("Yes", "No")),
        CatalogSetting(HVAC, "Outside air when ignition off", listOf("Yes", "No")),
        CatalogSetting(HVAC, "Blower boost on defrost", listOf("Yes", "No")),
        CatalogSetting(HVAC, "Link airflow to A/C", listOf("Yes", "No")),
        CatalogSetting(HVAC, "Fan speed ramp", listOf("Slow", "Normal", "Fast")),

        // Driver assist
        CatalogSetting(ASSIST, "PCS remembers last state", listOf("On", "Off")),
        CatalogSetting(ASSIST, "PCS default distance (switch off, then on)", listOf("Far", "Remember last")),
        CatalogSetting(ASSIST, "PCS default distance (ignition on, switch off)", listOf("Far", "Remember last")),
        CatalogSetting(ASSIST, "PCS default distance (ignition on, switch on)", listOf("Far", "Remember last")),
        CatalogSetting(ASSIST, "Rear cross traffic alert", listOf("On", "Off")),

        // Parking
        CatalogSetting(PARKING, "Parking sensor beep volume", listOf("Low", "Medium", "High")),
        CatalogSetting(PARKING, "Parking sensors active in neutral", listOf("Yes", "No")),
        CatalogSetting(PARKING, "Rear sensors in N or forward", listOf("Rear", "Rear corner", "Off")),
        CatalogSetting(PARKING, "Rear sensor range", listOf("Narrow", "Wide")),

        // Other
        CatalogSetting(OTHER, "Remote panic function", listOf("On", "Off")),
    )

    val categories: List<String> = all.map { it.category }.distinct()

    /** Filter by name or category; a blank query lists everything. */
    fun search(query: String): List<CatalogSetting> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter {
            it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
        }
    }
}
