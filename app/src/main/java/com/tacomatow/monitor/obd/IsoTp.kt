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
 */
object IsoTp {

    /** Every complete message in the reply: data bytes after any PCI, headers gone. */
    fun messages(raw: String): List<ByteArray> {
        val upper = raw.uppercase()
        if (upper.contains("NO DATA") || upper.contains("UNABLE TO CONNECT") ||
            upper.contains("ERROR") || upper.contains("STOPPED") || upper.contains("?")
        ) return emptyList()
        val tokens = upper.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return if (tokens.size == 1) messagesPacked(tokens[0]) else messagesTokenized(tokens)
    }

    /** First message whose data starts with these bytes, or null. */
    fun firstStartingWith(raw: String, echo: ByteArray): ByteArray? =
        messages(raw).firstOrNull { m ->
            m.size >= echo.size && m.copyOfRange(0, echo.size).contentEquals(echo)
        }

    // ---- spaced frames: "7E8 10 0A 43 04 … 7E8 21 …" ----

    private fun messagesTokenized(tokens: List<String>): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (isHeader(t)) {
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
                            // else is another ECU starting its own reply.
                            val next = tokens.getOrNull(i + 1)?.toIntOrNull(16)
                            if (room != 0 || next == null || next ushr 4 != 2) {
                                broken = true
                                break
                            }
                            i++
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

    private fun messagesPacked(s: String): List<ByteArray> {
        if (s.length < 2 || s.any { !it.isDigit() && it !in 'A'..'F' }) return emptyList()
        // Frames may or may not carry their header, but a session is
        // consistent — and whichever way parses the whole string exactly is
        // the right reading. Partial parses fail closed, never misaligned.
        return parsePacked(s, withHeaders = true).takeIf { it.second == s.length }?.first
            ?: parsePacked(s, withHeaders = false).takeIf { it.second == s.length }?.first
            ?: emptyList()
    }

    private fun parsePacked(s: String, withHeaders: Boolean): Pair<List<ByteArray>, Int> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < s.length) {
            val frameStart = i
            if (withHeaders && isCanIdAt(s, i)) i += 3
            if (i + 2 > s.length) return out to frameStart
            val pci = s.substring(i, i + 2).toIntOrNull(16) ?: return out to frameStart
            i += 2
            when (pci ushr 4) {
                0 -> {
                    val n = pci and 0x0F
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
                            if (withHeaders && isCanIdAt(s, i)) i += 3
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
