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

    /**
     * Where setup got stuck, when the transport tracks it. On the interface so
     * a wrapper — the transcript recorder — can pass it through: a cast to
     * BleTransport/SppTransport would fail on the wrapped instance and report
     * an empty stage exactly when the error text needs it.
     */
    fun setupStage(): String = ""

    /** The BLE channel this session proved, if any. Null on classic transport. */
    fun confirmedChannel(): UartChannel? = null
}

/** Bluetooth Classic (SPP) transport. */
class SppTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : ElmTransport {
    companion object {
        // The standard Serial Port Profile UUID. A transposed digit here used
        // to mean classic Bluetooth could never connect — only BLE dongles
        // worked at all.
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PROMPT = '>'
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val log = mutableListOf<String>()
    private var linkLostHandler: (() -> Unit)? = null

    /** True once a full exchange has completed on this socket. */
    @Volatile private var hadLink = false

    override fun onLinkLost(handler: () -> Unit) {
        linkLostHandler = handler
    }

    /** Setup stage, mirrored into the adapter log / error text. */
    @Volatile var stage = "starting"
        private set

    override fun setupStage(): String = stage

    override fun transcript(): List<String> = synchronized(log) { log.toList() }

    private fun note(s: String) = synchronized(log) { if (log.size < 60) log.add(s) }

    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
        close()
        log.clear()
        return try {
            stage = "classic-connect"
            note("classic: opening RFCOMM to ${device.address}")
            adapter.cancelDiscovery()
            // Insecure first: ELM327 clones need no authentication, and the
            // secure socket makes Android pop a system pairing dialog on an
            // unpaired dongle — a distraction on every auto-reconnect for a
            // set-and-forget monitor. Secure stays as the fallback for the
            // rare clone that refuses an unauthenticated channel.
            val sock = try {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also {
                    it.connect()
                    note("classic: insecure socket up")
                }
            } catch (_: Exception) {
                note("classic: insecure refused, trying secure (may ask to pair)")
                device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            }
            socket = sock
            input = sock.inputStream
            output = sock.outputStream
            stage = "ready"
            note("classic: socket up")
            hadLink = true
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
            onBrokenLink("classic: write failed")
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
                                hadLink = true
                                return sb.toString().uppercase()
                            }
                            sb.append(c)
                        }
                    }
                } else {
                    Thread.sleep(15)
                }
            }
        } catch (_: Exception) {
            onBrokenLink("classic: read failed")
            return null
        }
        return null
    }

    /**
     * An IOException means the socket died — the dongle lost power, drove
     * out of range, or another app stole it. Before this, isConnected() stayed
     * true and the app showed "connected" with blank gauges forever.
     * Fires once per link; a clean close() never fires.
     */
    private fun onBrokenLink(note: String) {
        if (!hadLink) return
        hadLink = false
        note(note)
        try {
            linkLostHandler?.invoke()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        hadLink = false
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
/**
 * A proven BLE UART: service, write and notify. Once a channel has identified
 * itself as an ELM327, later sessions try it first instead of re-hunting —
 * the hunt is a one-time cost per dongle, not a per-connect tax.
 */
data class UartChannel(val service: UUID, val write: UUID, val notify: UUID)

@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    /**
     * The channel that proved itself on this adapter before. Tried first so a
     * session that knows its dongle connects in a second; silently skipped if
     * it is no longer in the GATT table, and never overwritten by a guess.
     */
    private val remembered: UartChannel? = null,
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

    override fun setupStage(): String = stage

    private var connLatch = CountDownLatch(1)
    private var svcLatch = CountDownLatch(1)
    private var descLatch = CountDownLatch(1)
    @Volatile private var linkUp = false
    @Volatile private var subscribed = false

    /** Set only when a channel identified itself as an ELM327 this session. */
    @Volatile private var confirmed: UartChannel? = null

    /** The channel this session proved, for the caller to remember — or null. */
    override fun confirmedChannel(): UartChannel? = confirmed

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
            // collectCandidates already orders these deliberately: known ELM327
            // shapes first, guesses last. Re-sorting by "same characteristic"
            // put the documented FFF0 cross pair behind vendor guesses, which is
            // exactly backwards for the common Veepeak-class dongle.
            val candidates = collectCandidates(g)
            note("ble: ${candidates.size} candidate UART pair(s)")
            if (candidates.isEmpty()) {
                stage = "no-uart-characteristic"
                close()
                return false
            }
            // Ask each pair for its identity. "Any reply" is not a usable
            // test: a half-duplex echo channel or a vendor data channel answers
            // too — with framed bytes, not ELM text — and then fails every OBD
            // request in a way that looks like the truck refusing identifiers.
            // That is worse than silence, because the app then tells the driver
            // the ECU refused this PID and no adapter will help.
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
                var ident = transact("ATI", 3_000)?.trim()
                repeat(3) {
                    if (!looksLikeElm(ident)) {
                        Thread.sleep(700)
                        ident = transact("ATI", 3_000)?.trim()
                    }
                }
                note("ble: #$idx ATI -> ${ident?.take(40) ?: "<silence>"}")
                if (looksLikeElm(ident)) {
                    stage = "ready"
                    confirmed = UartChannel(
                        cand.write.service.uuid,
                        cand.write.uuid,
                        cand.notify.uuid,
                    )
                    note("ble: using ${cand.label}")
                    return true
                }
                // Rank the losers, but never walk away from the whole device:
                // a doubtful channel still beats refusing to connect, because
                // the session that follows is what diagnoses the dongle.
                if (ident.isNullOrEmpty()) {
                    if (silent == null) silent = cand
                } else if (confusing == null) {
                    confusing = cand
                }
            }
            // Silence is inert; a channel that answers with bytes that are not
            // ELM text actively corrupts every reading, so the quiet one is the
            // better guess when nothing identified itself.
            val last = silent ?: confusing ?: candidates.firstOrNull()
            if (last != null && subscribe(g, last.notify)) {
                writeChar = last.write
                Thread.sleep(400)
                stage = "unverified-channel"
                note("ble: using ${last.label} — no channel identified as ELM327")
                note("ble: readings may be wrong; if data looks strange, retry or power-cycle the dongle")
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
     * A real ELM327 answers ATI with "ELM327 v1.x"; STN-based clones answer
     * "STN1110"; some OEM-licensed ones print "OBD...". Anything else — echoed
     * bytes, framed CAN, a bare '?' — is not a command channel, whatever else
     * it does.
     */
    private fun looksLikeElm(reply: String?): Boolean {
        val s = reply?.uppercase() ?: return false
        return s.contains("ELM") || s.contains("STN") || s.contains("OBD")
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

        // 0. The winner from last time. A proven channel is not a guess, so it
        // goes before the guesses; if it is gone from the GATT table, add()
        // skips it and the hunt proceeds exactly as before.
        remembered?.let { r ->
            g.getService(r.service)?.let { svc ->
                add(svc.getCharacteristic(r.write), svc.getCharacteristic(r.notify), "remembered")
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
            // Honor what the clone actually supports: many BLE ELMs expose
            // WRITE_NO_RESPONSE only, and a DEFAULT write is refused outright.
            val noAck = (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
            // Fire-and-forget writes have no acknowledgements for the stack to
            // fragment on, so clones silently drop anything past ~20 bytes.
            // Cap those at the size every clone accepts; acknowledged writes
            // keep the negotiated MTU.
            val chunk = if (noAck) 20 else (mtu - 3).coerceAtLeast(20)
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
                // Small gap between pieces keeps cheap clones happy — paced
                // writes are what future multi-byte block writes will rely on.
                Thread.sleep(if (noAck) 25 else 40)
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
