package com.tacomatow.monitor.obd

/**
 * ISO-TP reassembly for ELM327 text replies, shared by every mode.
 *
 * A reply is one or more frames: an optional 11-bit header ("7E8"), then a PCI
 * byte. A single frame (0x0N) carries N data bytes; a first frame (0x1N)
 * announces the total length and the rest arrive as consecutive frames
 * (0x21…). The previous parse grabbed hex from the echo to the next non-hex
 * character, which kept the first frame, silently dropped the rest, and folded
 * headers and PCI bytes into the payload when no spaces were present. Block
 * 0x12 at 7C0 is the exhibit: a 9-byte reply displayed as 4 bytes.
 *
 * Replies are normalised once here: split on carriage returns, one CAN frame
 * per line the way the ELM prints them, each line parsed with the fitting
 * parser (spaced or packed). A whole-text pass runs first so a first frame
 * on one line still joins its consecutive frames on the next lines.
 *
 * The 0x750 gateway answers on 0x758 with the sub-target address (e.g.
 * 0x40 Main Body) as the first byte — ISO 15765-2 mixed addressing. That
 * byte is consumed like a second header before PCI parsing, but only when
 * it really is the sub-target: an adapter with extended addressing on
 * strips it on receive, and eating the length byte instead parses every
 * gateway reply to nothing.
 */
object IsoTp {

    /** Reply ID of the 0x750 gateway: first byte after it is the sub-target. */
    private const val GATEWAY_REPLY_ID = "758"

    /**
     * Every complete message in the reply: data bytes after any PCI, headers gone.
     *
     * [gatewaySub] is the sub-target the request went to (0x40 for Main
     * Body), or null when it is unknown. It decides the 758 question above.
     */
    fun messages(raw: String, gatewaySub: Int? = null): List<ByteArray> {
        val upper = raw.uppercase()
        if (upper.contains("NO DATA") || upper.contains("UNABLE TO CONNECT") ||
            upper.contains("ERROR") || upper.contains("STOPPED") || upper.contains("?")
        ) return emptyList()
        val lines = upper.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        // Frames of one message routinely land on different lines — the ELM
        // prints a first frame and its consecutive frames on separate lines —
        // so a message always spans a *run* of lines of the same shape, never
        // a single line. Parsing each packed line alone dropped every
        // multi-frame reply that arrived without spaces.
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].isPackedLine()) {
                val joined = StringBuilder()
                while (i < lines.size && lines[i].isPackedLine()) {
                    joined.append(lines[i])
                    i++
                }
                out += messagesPacked(joined.toString(), gatewaySub)
            } else {
                val tokens = mutableListOf<String>()
                while (i < lines.size && !lines[i].isPackedLine()) {
                    tokens += lines[i].splitToTokens()
                    i++
                }
                out += messagesTokenized(tokens, gatewaySub)
            }
        }
        return out
    }

    /** No spaces at all: a shared log, or a clone ignoring AT S1. */
    private fun String.isPackedLine(): Boolean = isNotEmpty() && none { it.isWhitespace() }

    private fun String.splitToTokens(): List<String> =
        trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** First message whose data starts with these bytes, or null. */
    fun firstStartingWith(raw: String, echo: ByteArray, gatewaySub: Int? = null): ByteArray? =
        messages(raw, gatewaySub).firstOrNull { m ->
            m.size >= echo.size && m.copyOfRange(0, echo.size).contentEquals(echo)
        }

    /**
     * Whether the byte after a 758 header is the sub-target address.
     *
     * Consume it only when it equals the sub-target we asked: 0x40 can never
     * be a PCI length byte, so an adapter that strips the extension on
     * receive (CEA on) and one that prints it (manual framing) then both
     * parse. When the sub-target is unknown (a shared log), fall back to
     * stripping bytes that cannot start — or, mid-message, continue — a
     * frame, which is exactly the old behavior for Main Body and fails
     * closed otherwise.
     */
    private fun isExtensionByte(v: Int, gatewaySub: Int?, continuation: Boolean): Boolean {
        if (gatewaySub != null) return v == (gatewaySub and 0xFF)
        return if (continuation) v ushr 4 != 2 else v ushr 4 > 1
    }

    // ---- spaced frames: "7E8 10 0A 43 04 … 7E8 21 …" ----

    private fun messagesTokenized(tokens: List<String>, gatewaySub: Int?): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (isHeader(t)) {
                // Gateway reply: consume the sub-target address byte, but
                // only when it is one — a stripped layout starts with the
                // PCI here, and eating that parses to nothing.
                if (t == GATEWAY_REPLY_ID) {
                    val ae = tokens.getOrNull(i + 1)
                    val v = if (ae != null && ae.length == 2) ae.toIntOrNull(16) else null
                    if (v != null && isExtensionByte(v, gatewaySub, continuation = false)) i++
                }
                i++
                continue
            }
            if (t.length != 2) {
                i++
                continue
            }
            val pci = t.toIntOrNull(16)
            if (pci == null) {
                i++
                continue
            }
            when (pci ushr 4) {
                0 -> {
                    val n = pci and 0x0F
                    takeBytes(tokens, i + 1, n)?.let { if (n > 0) out += it }
                    i += 1 + n
                }
                1 -> {
                    val len = tokens.getOrNull(i + 1)?.toIntOrNull(16) ?: break
                    val total = ((pci and 0x0F) shl 8) + len
                    i += 2
                    val data = mutableListOf<Byte>()
                    var room = 6
                    var broken = false
                    while (data.size < total && i < tokens.size) {
                        val f = tokens[i]
                        if (isHeader(f)) {
                            // Same message continuing ("7E8 21 …"). Anything
                            // else is another ECU starting its own reply. A
                            // gateway continuation carries its sub-target
                            // address after the 758 header — skip both.
                            var j = i + 1
                            if (f == GATEWAY_REPLY_ID) {
                                val ae = tokens.getOrNull(j)
                                val v = if (ae != null && ae.length == 2) ae.toIntOrNull(16) else null
                                if (v == null) {
                                    broken = true
                                    break
                                }
                                // A stripped layout carries the
                                // consecutive-frame PCI here, not the
                                // sub-target — only skip a real one.
                                if (isExtensionByte(v, gatewaySub, continuation = true)) j++
                            }
                            val next = tokens.getOrNull(j)?.toIntOrNull(16)
                            if (room != 0 || next == null || next ushr 4 != 2) {
                                broken = true
                                break
                            }
                            i = j
                            continue
                        }
                        if (f.length != 2) {
                            broken = true
                            break
                        }
                        val v = f.toIntOrNull(16)
                        if (v == null) {
                            broken = true
                            break
                        }
                        if (room == 0) {
                            if (v ushr 4 != 2) {
                                broken = true
                                break
                            }
                            i++
                            room = 7
                            continue
                        }
                        data += v.toByte()
                        i++
                        room--
                    }
                    // A reply cut off mid-message (timeout) is not data: emit
                    // only complete messages, never a misaligned prefix.
                    if (!broken && data.size == total) out += data.toByteArray()
                }
                2 -> i++ // stray consecutive frame without a first frame; skip
                3 -> i += 3 // flow-control triple; skip
                else -> i++
            }
        }
        return out
    }

    // ---- packed frames: "7E8100A4304…7E821…" (no spaces) ----

    private fun messagesPacked(s: String, gatewaySub: Int?): List<ByteArray> {
        if (s.length < 2 || s.any { !it.isDigit() && it !in 'A'..'F' }) return emptyList()
        // Frames may or may not carry their header, but a session is
        // consistent — and whichever way parses the whole string exactly is
        // the right reading. Partial parses fail closed, never misaligned,
        // except for documented padding bytes some clones append (AA/55).
        fun accept(p: Pair<List<ByteArray>, Int>): List<ByteArray>? =
            if (p.second == s.length ||
                (p.first.isNotEmpty() && isPaddingTail(s, p.second))
            ) p.first else null
        return accept(parsePacked(s, withHeaders = true, gatewaySub))
            ?: accept(parsePacked(s, withHeaders = false, gatewaySub))
            ?: emptyList()
    }

    /** Trailing padding clones append after the last complete message. */
    private fun isPaddingTail(s: String, pos: Int): Boolean {
        if (pos >= s.length || (s.length - pos) % 2 != 0) return false
        var j = pos
        while (j < s.length) {
            when (s.substring(j, j + 2)) {
                "AA", "55", "00" -> j += 2
                else -> return false
            }
        }
        return true
    }

    private fun parsePacked(
        s: String,
        withHeaders: Boolean,
        gatewaySub: Int?,
    ): Pair<List<ByteArray>, Int> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < s.length) {
            val frameStart = i
            if (withHeaders && isCanIdAt(s, i)) {
                val gateway = s.startsWith(GATEWAY_REPLY_ID, i)
                i += 3
                // Gateway replies carry the sub-target address before the
                // PCI — unless the adapter already stripped it on receive,
                // in which case the PCI follows the header directly.
                if (gateway) {
                    if (i + 2 > s.length) return out to frameStart
                    val ext = s.substring(i, i + 2).toIntOrNull(16)
                        ?: return out to frameStart
                    if (isExtensionByte(ext, gatewaySub, continuation = false)) i += 2
                }
            }
            if (i + 2 > s.length) return out to frameStart
            val pci = s.substring(i, i + 2).toIntOrNull(16) ?: return out to frameStart
            i += 2
            when (pci ushr 4) {
                0 -> {
                    val n = pci and 0x0F
                    if (n == 0) {
                        // Zero-length frame: 00 padding between frames. It must
                        // be consumed — advancing by 2*n would never move and
                        // hang the parser on any reply containing a 00 byte.
                        continue
                    }
                    if (i + 2 * n > s.length) return out to frameStart
                    out += s.substring(i, i + 2 * n).chunked(2)
                        .map { it.toInt(16).toByte() }.toByteArray()
                    i += 2 * n
                }
                1 -> {
                    if (i + 2 > s.length) return out to frameStart
                    var need = ((pci and 0x0F) shl 8) + s.substring(i, i + 2).toInt(16)
                    i += 2
                    val data = mutableListOf<Byte>()
                    var room = 6
                    while (need > 0) {
                        if (room == 0) {
                            if (withHeaders && isCanIdAt(s, i)) {
                                val gateway = s.startsWith(GATEWAY_REPLY_ID, i)
                                i += 3
                                if (gateway) {
                                    if (i + 2 > s.length) return out to frameStart
                                    val ext = s.substring(i, i + 2).toIntOrNull(16)
                                        ?: return out to frameStart
                                    if (isExtensionByte(ext, gatewaySub, continuation = true)) i += 2
                                }
                            }
                            if (i + 2 > s.length) return out to frameStart
                            val cf = s.substring(i, i + 2).toIntOrNull(16)
                                ?: return out to frameStart
                            if (cf ushr 4 != 2) return out to frameStart
                            i += 2
                            room = 7
                            continue
                        }
                        if (i + 2 > s.length) return out to frameStart
                        data += s.substring(i, i + 2).toInt(16).toByte()
                        i += 2
                        need--
                        room--
                    }
                    out += data.toByteArray()
                }
                2 -> return out to frameStart // stray consecutive frame
                3 -> {
                    if (i + 6 > s.length) return out to frameStart
                    i += 6 // flow-control triple
                }
                else -> return out to frameStart
            }
        }
        return out to i
    }

    private fun takeBytes(tokens: List<String>, from: Int, n: Int): ByteArray? {
        if (n <= 0 || from + n > tokens.size) return null
        val out = ByteArray(n)
        for (k in 0 until n) {
            val t = tokens[from + k]
            if (t.length != 2) return null
            out[k] = t.toIntOrNull(16)?.toByte() ?: return null
        }
        return out
    }

    /** 11-bit header echoes like 7E8 — reply metadata, never data. */
    private fun isHeader(t: String): Boolean =
        t.length == 3 && (t[0] == '7' || t[0] == '8') &&
            t.all { it.isDigit() || it in 'A'..'F' }

    private fun isCanIdAt(s: String, i: Int): Boolean =
        i + 3 <= s.length && (s[i] == '7' || s[i] == '8') &&
            s[i + 1].let { it.isDigit() || it in 'A'..'F' } &&
            s[i + 2].let { it.isDigit() || it in 'A'..'F' }
}
