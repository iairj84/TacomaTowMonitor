package com.tacomatow.monitor.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Byte pipe to the ELM327. Classic clones (OBDII / ELM327 / VGate-SPP) talk
 * RFCOMM; the small plug-in dongles Torque shows as "odbBLE" / "odbble"
 * (VGate iCar, Veepeak BLE and clones) speak GATT over Bluetooth LE and
 * have NO classic socket — connecting to them via SPP always fails.
 */
interface ElmTransport {
    fun open(): Boolean
    fun transact(cmd: String, timeoutMs: Int): String?
    fun close()

    /** Human-readable setup trail, newest last. Shown in the app for support. */
    fun transcript(): List<String>

    /**
     * Called when the link drops on its own — supervision timeout, the dongle
     * losing power, the phone wandering out of range. Callers that can
     * reconnect override this; a transport that never reports is fine too.
     */
    fun onLinkLost(handler: () -> Unit) {}
}

/** Bluetooth Classic (SPP) transport. */
class SppTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : ElmTransport {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-0085F9B42D9F")
        private const val PROMPT = '>'
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val log = mutableListOf<String>()

    /** Setup stage, mirrored into the adapter log / error text. */
    @Volatile var stage = "starting"
        private set

    override fun transcript(): List<String> = synchronized(log) { log.toList() }

    private fun note(s: String) = synchronized(log) { if (log.size < 60) log.add(s) }

    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
        close()
        log.clear()
        return try {
            stage = "classic-connect"
            note("classic: opening RFCOMM to ${device.address}")
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            sock.connect()
            socket = sock
            input = sock.inputStream
            output = sock.outputStream
            stage = "ready"
            note("classic: socket up")
            true
        } catch (e: Exception) {
            // The usual cause: a BLE-only dongle has no RFCOMM service at all.
            stage = "classic-connect-failed"
            note("classic: connect failed (${e.javaClass.simpleName})")
            close()
            false
        }
    }

    @Synchronized
    override fun transact(cmd: String, timeoutMs: Int): String? {
        val out = output ?: return null
        val inp = input ?: return null
        try {
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
                            if (c == PROMPT) return sb.toString().uppercase()
                            sb.append(c)
                        }
                    }
                } else {
                    Thread.sleep(15)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }
}

/**
 * Bluetooth LE (GATT) transport for "odbble"-style dongles.
 *
 * Choosing the right characteristic pair matters more than anything else:
 * cheap clones expose several writable/notifying characteristics (battery,
 * device info, DFU) and writing OBD text to the wrong one silently does
 * nothing. So we PROBE each candidate pair with a live `AT` command and keep
 * the first one that actually answers.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ElmTransport {
    companion object {
        private const val PROMPT: Byte = '>'.code.toByte()
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone -> dongle
        val NUS_RX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // dongle -> phone
        val FFE_SERVICE: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val FFE_CHAR: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /**
         * The generic ELM327 BLE shape: service FFF0, write FFF2, notify FFF1.
         * This is what a Veepeak-class adapter exposes, and it is a CROSS pair —
         * so it has to be tried before the "one characteristic does both"
         * ordering below, which otherwise ranks it last.
         */
        val FFF_SERVICE: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val FFF_WRITE: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        val FFF_NOTIFY: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")

        /** GATT housekeeping services: never a UART, and they cost probe time. */
        private val NOT_UART_SERVICES = setOf(
            "00001800-0000-1000-8000-00805F9B34FB", // generic access
            "00001801-0000-1000-8000-00805F9B34FB", // generic attribute
            "00001802-0000-1000-8000-00805F9B34FB", // immediate alert
            "0000180A-0000-1000-8000-00805F9B34FB", // device information
            "0000180F-0000-1000-8000-00805F9B34FB", // battery
            "00001812-0000-1000-8000-00805F9B34FB", // HID
        )
    }

    private data class Pair2(
        val write: BluetoothGattCharacteristic,
        val notify: BluetoothGattCharacteristic,
        val label: String,
    )

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val rx = LinkedBlockingQueue<Byte>()
    private val log = mutableListOf<String>()

    /** Updated at each setup step so failures can say WHERE they stuck. */
    @Volatile var stage = "starting"
        private set

    private var connLatch = CountDownLatch(1)
    private var svcLatch = CountDownLatch(1)
    private var descLatch = CountDownLatch(1)
    @Volatile private var linkUp = false
    @Volatile private var subscribed = false

    /** True once a link was actually up, so a close we caused is not a loss. */
    @Volatile private var hadLink = false
    @Volatile private var linkLostHandler: (() -> Unit)? = null

    override fun onLinkLost(handler: () -> Unit) { linkLostHandler = handler }

    override fun transcript(): List<String> = synchronized(log) { log.toList() }

    private fun note(s: String) = synchronized(log) { if (log.size < 60) log.add(s) }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            linkUp = newState == BluetoothProfile.STATE_CONNECTED
            note("link state=$newState status=$status")
            connLatch.countDown()
            if (linkUp) {
                hadLink = true
            } else {
                try { g.close() } catch (_: Exception) {}
                // Only a link that WAS up can be lost. The disconnect we send
                // ourselves during setup arrives here as state 0 as well.
                if (hadLink) {
                    hadLink = false
                    note("link lost — caller may reconnect")
                    linkLostHandler?.invoke()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleTransport.mtu = mtu
            note("mtu=$mtu status=$status")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            note("discovery status=$status")
            svcLatch.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            value.forEach { rx.add(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.forEach { rx.add(it) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            subscribed = status == BluetoothGatt.GATT_SUCCESS
            note("cccd write status=$status")
            descLatch.countDown()
        }
    }

    /**
     * One attempt, plus one retry on a fresh connection.
     *
     * A BLE dongle that was connected moments ago can hand back a GATT link
     * that accepts writes and never answers — it looks connected and behaves
     * dead. The application-level fix is to tear the connection down and build
     * it again, which is exactly what a power cycle would do.
     */
    override fun open(): Boolean {
        if (openOnce(clearLog = true)) return true
        note("ble: retrying on a fresh connection")
        close()
        try { Thread.sleep(1_200) } catch (_: Exception) {}
        return openOnce(clearLog = false)
    }

    private fun openOnce(clearLog: Boolean): Boolean {
        close()
        if (clearLog) log.clear()
        connLatch = CountDownLatch(1)
        svcLatch = CountDownLatch(1)
        return try {
            stage = "gatt-connect"
            note("ble: connecting to ${device.address}")
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: return false
            stage = "waiting-link"
            if (!connLatch.await(15, TimeUnit.SECONDS) || !linkUp) {
                stage = "link-timeout"
                note("ble: never reached connected state")
                close()
                return false
            }
            val g = gatt ?: return false
            stage = "mtu-negotiation"
            try { g.requestMtu(247) } catch (_: Exception) {}
            Thread.sleep(500)
            stage = "service-discovery"
            if (!g.discoverServices()) {
                note("ble: discoverServices() refused")
                close()
                return false
            }
            stage = "waiting-services"
            if (!svcLatch.await(10, TimeUnit.SECONDS)) {
                note("ble: discovery timed out")
                close()
                return false
            }
            // A single characteristic that both writes and notifies is the
            // usual wiring for these clones; cross-pairs are a guess and one
            // of them (fff2 -> fff1) answers AT commands with "?", which then
            // fails every OBD request. Try the natural shape first.
            val candidates = collectCandidates(g)
                .sortedBy { if (it.write.uuid == it.notify.uuid) 0 else 1 }
            note("ble: ${candidates.size} candidate UART pair(s)")
            if (candidates.isEmpty()) {
                stage = "no-uart-characteristic"
                close()
                return false
            }
            // Probe each pair with a real AT command; keep the first that replies.
            stage = "probing-uart"
            var silent: Pair2? = null
            var confusing: Pair2? = null
            for ((idx, cand) in candidates.withIndex()) {
                note("ble: probing #$idx ${cand.label}")
                if (!subscribe(g, cand.notify)) {
                    note("ble: #$idx notify subscribe failed")
                    continue
                }
                writeChar = cand.write
                // Cheap clones need most of a second after the notify
                // subscription arms, and longer on a re-connect.
                Thread.sleep(700)
                var probe = transact("AT", 2_500)?.trim()
                // Silence here proves nothing yet; and a clone that has just
                // woken up answers '?' to the first command it sees.
                repeat(3) {
                    if (probe.isNullOrEmpty() || probe == "?") {
                        Thread.sleep(700)
                        probe = transact("AT", 2_500)?.trim()
                    }
                }
                note("ble: #$idx probe -> ${probe?.take(40) ?: "<silence>"}")
                if (probe != null && probe.isNotEmpty() && probe != "?") {
                    stage = "ready"
                    note("ble: using ${cand.label}")
                    return true
                }
                // Rank the losers but never walk away: attaching to a doubtful
                // channel beats refusing to connect, because the session that
                // follows is what actually diagnoses the dongle.
                if (probe.isNullOrEmpty()) {
                    if (silent == null) silent = cand
                } else if (confusing == null) {
                    confusing = cand
                }
            }
            // Prefer a pair that ANSWERED something over one that said nothing.
            // '?' means the firmware received bytes and parsed them — the
            // channel is real, the command or the state is wrong. Silence may
            // simply be the wrong characteristic. In the field log this exact
            // ordering picked a silent vendor channel over FFF0/F2->F1, which
            // had answered '?'.
            val last = confusing ?: silent ?: candidates.firstOrNull()
            if (last != null && subscribe(g, last.notify)) {
                writeChar = last.write
                Thread.sleep(400)
                stage = "connected-but-silent"
                note("ble: using ${last.label} (no clean probe — best of a bad set)")
                // Answering nothing at all is a different failure from the
                // truck refusing; say so, because the fix is to power-cycle
                // the dongle rather than to buy another one.
                note("ble: connected, but the adapter answered no AT command. Unplug it for 5 s and retry.")
                return true
            }
            stage = "no-answer-on-probe"
            close()
            false
        } catch (e: Exception) {
            stage = "exception"
            note("ble: ${e.javaClass.simpleName}: ${e.message}")
            close()
            false
        }
    }

    /**
     * Ordered candidates. Known-good shapes first, generic guesses last.
     *
     * The order matters more than the probe does: on a re-connect the correct
     * pair answers on the first attempt, while a wrong pair answers `?` or
     * nothing and burns seconds apiece.
     */
    private fun collectCandidates(g: BluetoothGatt): List<Pair2> {
        val out = mutableListOf<Pair2>()
        fun add(w: BluetoothGattCharacteristic?, n: BluetoothGattCharacteristic?, label: String) {
            if (w == null || n == null) return
            if (!canWrite(w) || !canNotify(n)) return
            if (out.none { it.write.uuid == w.uuid && it.notify.uuid == n.uuid }) {
                out += Pair2(w, n, label)
            }
        }

        // 1. Nordic UART.
        g.getService(NUS_SERVICE)?.let { svc ->
            add(svc.getCharacteristic(NUS_TX), svc.getCharacteristic(NUS_RX), "NUS (6E400001)")
        }

        // 2. Generic ELM327 BLE: FFF0 with write FFF2 and notify FFF1.
        g.getService(FFF_SERVICE)?.let { svc ->
            add(svc.getCharacteristic(FFF_WRITE), svc.getCharacteristic(FFF_NOTIFY), "FFF0/F2->F1")
            val both = svc.getCharacteristic(FFF_NOTIFY)
            add(both, both, "FFF1 both")
        }

        // 3. HM-10 style single characteristic.
        g.getService(FFE_SERVICE)?.let { svc ->
            val ch = svc.getCharacteristic(FFE_CHAR)
            add(ch, ch, "FFE1 (HM-10)")
        }

        // 4. Anything else that looks like a UART, one characteristic first.
        val uartServices = g.services.filter { it.uuid.toString().uppercase() !in NOT_UART_SERVICES }
        for (svc in uartServices) {
            val candidates = svc.characteristics.filter { it.uuid != CCCD }
            for (ch in candidates) {
                if (canWrite(ch) && canNotify(ch)) {
                    add(ch, ch, "${svc.uuid.toString().take(8)} both ${ch.uuid.toString().take(8)}")
                }
            }
        }
        // 5. Then cross pairs. A CCCD exposed as a characteristic is a clone
        //    quirk seen in the field; subscribing to it never delivers data.
        for (svc in uartServices) {
            val chars = svc.characteristics.filter { it.uuid != CCCD }
            for (w in chars.filter { canWrite(it) }) {
                for (n in chars.filter { canNotify(it) }) {
                    add(
                        w,
                        n,
                        "${svc.uuid.toString().take(8)} w=${w.uuid.toString().take(8)} " +
                            "n=${n.uuid.toString().take(8)}",
                    )
                }
            }
        }
        return out
    }

    private fun canWrite(c: BluetoothGattCharacteristic): Boolean {
        val p = c.properties
        return (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
            (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    private fun canNotify(c: BluetoothGattCharacteristic): Boolean {
        val p = c.properties
        return (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
            (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, notifyChar: BluetoothGattCharacteristic): Boolean {
        return try {
            if (!g.setCharacteristicNotification(notifyChar, true)) return false
            val cccd = notifyChar.getDescriptor(CCCD)
            if (cccd == null) {
                // Some clones notify without a CCCD — proceed optimistically.
                return true
            }
            descLatch = CountDownLatch(1)
            subscribed = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (rc != BluetoothGatt.GATT_SUCCESS) return false
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(cccd)) return false
            }
            descLatch.await(5, TimeUnit.SECONDS)
            subscribed
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    override fun transact(cmd: String, timeoutMs: Int): String? {
        val g = gatt ?: return null
        val w = writeChar ?: return null
        // Swallow any late bytes from the previous command first, otherwise a
        // slow clone's leftover frame gets parsed as this command's answer.
        rx.clear()
        Thread.sleep(60)
        rx.clear()
        return try {
            val payload = (cmd + "\r").toByteArray()
            val chunk = (mtu - 3).coerceAtLeast(20)
            // Honor what the clone actually supports: many BLE ELMs expose
            // WRITE_NO_RESPONSE only, and a DEFAULT write is refused outright.
            val noAck = (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
            var off = 0
            while (off < payload.size) {
                val end = minOf(off + chunk, payload.size)
                val piece = payload.copyOfRange(off, end)
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        w,
                        piece,
                        if (noAck) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    w.value = piece
                    w.writeType = if (noAck) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    g.writeCharacteristic(w)
                }
                if (!ok) {
                    stage = "write-refused"
                    return null
                }
                if (!noAck) Thread.sleep(40) // small gap keeps cheap clones happy
                off = end
            }
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val b = rx.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (b == PROMPT) return sb.toString().uppercase()
                sb.append(b.toInt().toChar())
            }
            if (sb.isNotEmpty()) return sb.toString().uppercase()
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        linkUp = false
        hadLink = false
        rx.clear()
    }
}
