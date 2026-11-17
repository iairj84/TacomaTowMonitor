package com.tacomatow.monitor.obd

/**
 * Serves a recorded conversation back to the real client, in order.
 *
 * Deliberately strict: if the client asks something the recording did not
 * contain, the test fails with the entry it expected. That is what makes a
 * transcript a contract — a fixture from the truck pins the request sequence
 * *and* the reply shapes, so a change to either is caught instead of quietly
 * producing different numbers.
 *
 * What it does not police is case and spacing within a command. `AT SH 750`,
 * `ATSH750` and `AT SH750` are the same request to the adapter, and a recording
 * from the truck cannot be re-made to match a cosmetic rewrite — failing there
 * would cost a fixture without catching anything.
 *
 * Timeouts are not compared either. The client legitimately widens its window
 * for a `78` and shortens it for a sweep; the recording keeps the value it used
 * so a test can assert on it from [Transcript.Record] itself.
 */
class ReplayTransport(private val record: Transcript.Record) : ElmTransport {

    private var cursor = 0

    val remaining: Int get() = record.entries.size - cursor

    override fun open(): Boolean = true

    override fun transact(cmd: String, timeoutMs: Int): String? {
        check(cursor < record.entries.size) {
            "transcript ran out at \"$cmd\": all ${record.entries.size} entries were replayed"
        }
        val expected = record.entries[cursor]
        check(sameCommand(expected.command, cmd)) {
            "entry $cursor expected \"${expected.command}\" @${expected.timeoutMs} ms, " +
                "client asked \"$cmd\" @$timeoutMs ms"
        }
        cursor++
        return expected.reply
    }

    private fun sameCommand(a: String, b: String): Boolean =
        a.filterNot { it.isWhitespace() }.equals(b.filterNot { it.isWhitespace() }, ignoreCase = true)

    override fun close() {}

    override fun transcript(): List<String> = emptyList()

    /** Fails unless the client consumed the whole recording. */
    fun assertComplete() {
        check(remaining == 0) { "$remaining recorded entries were never replayed" }
    }
}
