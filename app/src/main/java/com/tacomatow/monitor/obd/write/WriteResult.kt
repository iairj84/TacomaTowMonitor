package com.tacomatow.monitor.obd.write

/** What a guarded write attempt produced. Refusals send nothing. */
sealed interface WriteOutcome {
    /** Write accepted (7B) and the re-read shows the new value. */
    data class SentOk(val verifiedHex: String) : WriteOutcome

    /** Write accepted but the re-read did not confirm — do NOT repeat. */
    data class SentUnverified(val detail: String) : WriteOutcome

    /** Module refused the write itself: `7F 3B <nrc>`. Nothing retried. */
    data class ModuleRefused(val nrc: String) : WriteOutcome

    /** Policy gate fired before anything was sent. */
    data class Refused(val rule: RefusalRule, val message: String, val detail: String) : WriteOutcome

    /** Adapter/session problem before any evaluation completed. */
    data class AdapterError(val message: String) : WriteOutcome
}
