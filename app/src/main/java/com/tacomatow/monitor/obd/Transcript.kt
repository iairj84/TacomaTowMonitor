package com.tacomatow.monitor.obd

/**
 * A recorded ELM conversation: every command, its timeout and the exact reply.
 *
 * This exists because of how this app's protocol bugs were found. Each one was
 * a reply *shape* that had no fixture — a 9-byte block shown as 4 bytes, a
 * packed `7F` read as our refusal, `7F 21 78` ending a read as silence — and
 * each was reconstructed by hand afterwards from a screenshot. A session on the
 * truck becomes a text file here, and a test replays it byte for byte, so the
 * next shape is a test case instead of a retyped guess.
 *
 * Line-based and greppable:
 *
 * ```
 * # tacoma-transcript 1
 * # app: 0.9.16 (69)
 * > 2114 @900
 * < 758 40 03 61 14 9A
 * ```
 *
 * `>` is one request with the window it was sent with, `<` is the reply with
 * carriage returns escaped. A bare `<` records no answer at all, which is a
 * distinct and important case. Unknown lines are ignored so an older reader
 * can open a newer file.
 */
object Transcript {

    const val MAGIC = "tacoma-transcript 1"

    /** One exchange. [reply] is null when the adapter said nothing. */
    data class Entry(val command: String, val timeoutMs: Int, val reply: String?)

    data class Record(
        val header: Map<String, String>,
        val entries: List<Entry>,
    )

    fun encode(entries: List<Entry>, header: Map<String, String>): String = buildString {
        appendLine("# $MAGIC")
        header.forEach { (k, v) -> appendLine("# $k: ${oneLine(v)}") }
        entries.forEach { e ->
            appendLine("> ${e.command} @${e.timeoutMs}")
            append("< ")
            e.reply?.let { append(escape(it)) }
            appendLine()
        }
    }

    fun parse(text: String): Record {
        val header = LinkedHashMap<String, String>()
        val entries = mutableListOf<Entry>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> {
                    val body = line.removePrefix("#").trim()
                    if (body != MAGIC) {
                        val at = body.indexOf(':')
                        if (at > 0) header[body.substring(0, at).trim()] = body.substring(at + 1).trim()
                    }
                }
                line.startsWith(">") -> {
                    val body = line.removePrefix(">")
                    val at = body.lastIndexOf('@')
                    val cmd = (if (at >= 0) body.substring(0, at) else body).trim()
                    val ms = if (at >= 0) body.substring(at + 1).trim().toIntOrNull() ?: 0 else 0
                    // A reply line always follows; push a placeholder and fill it.
                    entries += Entry(cmd, ms, null)
                }
                line.startsWith("<") -> {
                    val body = line.removePrefix("<").removePrefix(" ")
                    val reply = unescape(body).ifBlank { null }
                    val last = entries.lastOrNull()
                    if (last != null) entries[entries.size - 1] = last.copy(reply = reply)
                }
            }
        }
        return Record(header, entries)
    }

    /** Header values are one line each; nothing else belongs in a fixture header. */
    private fun oneLine(s: String): String = s.replace("\r", " ").replace("\n", " ")

    private fun escape(s: String): String = buildString {
        s.forEach {
            when (it) {
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                else -> append(it)
            }
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'r' -> { append('\r'); i += 2 }
                    'n' -> { append('\n'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    else -> { append(c); i++ }
                }
            } else {
                append(c)
                i++
            }
        }
    }
}
