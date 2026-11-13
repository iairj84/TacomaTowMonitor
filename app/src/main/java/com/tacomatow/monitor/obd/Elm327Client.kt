package com.tacomatow.monitor.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Minimal blocking ELM327 driver over Bluetooth Classic SPP.
 * Runs on a worker thread owned by [ObdRepository]; never on main.
 *
 * Init: ATZ / ATE0 / ATL0 / ATS0 / ATH1 / ATSP0, then per-PID
 * header switching (AT SH 701 for Tacoma trans temps,
 * AT SH 7DF / default for Mode 01).
 */
class Elm327Client(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-0085F9B42D9F")
        private const val PROMPT = '>'
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var currentHeader: String? = null

    @SuppressLint("MissingPermission")
    fun connect(timeoutMs: Int = 15_000): Boolean {
        close()
        val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
        adapter.cancelDiscovery()
        sock.connect()
        socket = sock
        input = sock.inputStream
        output = sock.outputStream
        return initAdapter()
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    private fun initAdapter(): Boolean {
        // Order matters: reset, quiet echo/linefeeds, show headers so we can
        // validate ECU addresses, auto protocol for the Tacoma's 11-bit 500k CAN.
        val init = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0")
        for (cmd in init) {
            val resp = transact(cmd, 3_000) ?: return false
            if (cmd == "ATZ" && resp.isBlank()) {
                // Cheap clones sometimes swallow the first reset; one retry.
                transact(cmd, 3_000) ?: return false
            }
        }
        // Warm the protocol on the bus so the first gauge read is fast.
        transact("0100", 5_000)
        return true
    }

    /** Read one PID. Returns decoded value or null (unsupported / timeout). */
    fun readPid(def: PidDef): Float? {
        if (!isConnected()) return null
        setHeader(def.header)
        val raw = transact(TacomaPids.command(def), 2_000) ?: return null
        val bytes = parseResponse(TacomaPids.command(def), raw) ?: return null
        return TacomaPids.decode(def, bytes)
    }

    fun readVoltage(): Float? {
        val raw = transact("ATRV", 1_500) ?: return null
        // Clone replies look like "12.6V". Be lenient.
        val m = Regex("""(\d{1,2}\.\d)""").find(raw.replace("V", ""))
        return m?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun setHeader(header: String?) {
        if (header == currentHeader) return
        if (header == null) {
            transact("AT SH 7DF", 1_000)
        } else {
            transact("AT SH $header", 1_000)
        }
        currentHeader = header
    }

    /** Send a command, block until '>' or timeout. Strips echo. */
    @Synchronized
    private fun transact(cmd: String, timeoutMs: Int): String? {
        val out = output ?: return null
        val inp = input ?: return null
        try {
            // Drain stale bytes so a slow previous reply can't leak in.
            while (inp.available() > 0) inp.read()
            out.write((cmd + "\r").toByteArray())
            out.flush()
        } catch (_: Exception) {
            return null
        }
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        try {
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val n = inp.read(buf)
                    if (n > 0) {
                        for (i in 0 until n) {
                            val c = buf[i].toInt().toChar()
                            if (c == PROMPT) {
                                return clean(sb.toString(), cmd)
                            }
                            sb.append(c)
                        }
                    }
                } else {
                    Thread.sleep(15)
                }
                if (sb.contains("NO DATA") || sb.contains("STOPPED") ||
                    sb.contains("?") || sb.contains("ERROR")
                ) {
                    // Let it run to '>' so the stream stays framed, then return.
                    continue
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null // timeout
    }

    private fun clean(raw: String, cmd: String): String {
        // Remove command echo + whitespace; keep NO DATA / error markers.
        return raw.replace("\r", " ").replace("\n", " ")
            .replace(cmd, " ").replace(" ", "")
            .uppercase()
    }

    /**
     * Parse "6221627XXXX" (Mode 22 -> 0x62) or "4105XX" (Mode 01 -> 0x41).
     * Returns data bytes after the PID echo.
     */
    private fun parseResponse(cmd: String, resp: String): ByteArray? {
        if (resp.contains("NODATA") || resp.contains("ERROR") ||
            resp.contains("STOPPED") || resp.contains("?")
        ) return null
        // Mode 22 echo: "62" + pid(4 hex). Mode 01 echo: "41" + pid(2 hex).
        val echoCandidates = when {
            cmd.startsWith("22") -> listOf("62" + cmd.substring(2))
            cmd.startsWith("01") -> listOf("41" + cmd.substring(2))
            else -> emptyList()
        }
        for (echo in echoCandidates) {
            val idx = resp.indexOf(echo)
            if (idx >= 0) {
                val hex = resp.substring(idx + echo.length)
                    .takeWhile { it.isDigit() || it in 'A'..'F' }
                if (hex.length >= 2) return hexToBytes(hex)
            }
        }
        // Multi-ECU / CAN-ID prefixed frames (ATH1 on): find echo anywhere.
        return null
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isDigit() || it in 'A'..'F' }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
        currentHeader = null
    }
}
