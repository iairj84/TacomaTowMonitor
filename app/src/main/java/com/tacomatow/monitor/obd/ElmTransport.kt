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

    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
        close()
        return try {
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            sock.connect()
            socket = sock
            input = sock.inputStream
            output = sock.outputStream
            true
        } catch (_: Exception) {
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
 * Bluetooth LE (GATT) transport for "odbBLE"-style dongles.
 * Probes the Nordic UART service, then the TI/HM-10 FFE0 service, then any
 * service exposing a write + notify characteristic pair.
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
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val rx = LinkedBlockingQueue<Byte>()
    /** Updated at each setup step so failures can say WHERE they stuck. */
    @Volatile var stage = "starting"
        private set

    private var connLatch = CountDownLatch(1)
    private var svcLatch = CountDownLatch(1)
    private var descLatch = CountDownLatch(1)
    @Volatile private var linkUp = false
    @Volatile private var subscribed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            linkUp = newState == BluetoothProfile.STATE_CONNECTED
            connLatch.countDown()
            if (!linkUp) {
                try { g.close() } catch (_: Exception) {}
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleTransport.mtu = mtu
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            svcLatch.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            value.forEach { rx.add(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.forEach { rx.add(it) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            subscribed = status == BluetoothGatt.GATT_SUCCESS
            descLatch.countDown()
        }
    }

    override fun open(): Boolean {
        close()
        connLatch = CountDownLatch(1)
        svcLatch = CountDownLatch(1)
        return try {
            stage = "gatt-connect"
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: return false
            stage = "waiting-link"
            if (!connLatch.await(12, TimeUnit.SECONDS) || !linkUp) {
                stage = "link-timeout"
                close()
                return false
            }
            val g = gatt ?: return false
            stage = "mtu-negotiation"
            try { g.requestMtu(247) } catch (_: Exception) {}
            Thread.sleep(400)
            stage = "service-discovery"
            if (!g.discoverServices()) {
                close()
                return false
            }
            stage = "waiting-services"
            if (!svcLatch.await(8, TimeUnit.SECONDS)) {
                close()
                return false
            }
            stage = "probing-uart"
            if (!pickCharacteristics(g)) {
                stage = "no-uart-characteristic"
                close()
                return false
            }
            // Give the first notify a moment to arm before ATZ.
            Thread.sleep(300)
            stage = "ready"
            true
        } catch (_: Exception) {
            stage = "exception"
            close()
            false
        }
    }

    private fun pickCharacteristics(g: BluetoothGatt): Boolean {
        // 1) Nordic UART split TX/RX.
        g.getService(NUS_SERVICE)?.let { svc ->
            val tx = svc.getCharacteristic(NUS_TX)
            val rxChar = svc.getCharacteristic(NUS_RX)
            if (tx != null && rxChar != null && canWrite(tx) && canNotify(rxChar)) {
                writeChar = tx
                if (subscribe(g, rxChar)) return true
            }
        }
        // 2) HM-10 / TI single FFE1 characteristic.
        g.getService(FFE_SERVICE)?.let { svc ->
            svc.getCharacteristic(FFE_CHAR)?.let { ch ->
                if (canWrite(ch) && canNotify(ch)) {
                    writeChar = ch
                    if (subscribe(g, ch)) return true
                }
            }
        }
        // 3) Anything with a write + notify pair.
        for (svc in g.services) {
            var w: BluetoothGattCharacteristic? = null
            var n: BluetoothGattCharacteristic? = null
            for (ch in svc.characteristics) {
                if (w == null && canWrite(ch)) w = ch
                if (n == null && canNotify(ch)) n = ch
            }
            if (w != null && n != null) {
                writeChar = w
                if (subscribe(g, n)) return true
            }
        }
        return false
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
            stage = "subscribing"
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
                w.value = payload.copyOfRange(off, end)
                w.writeType = if (noAck) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!g.writeCharacteristic(w)) {
                    stage = "write-refused"
                    return null
                }
                // WRITE_TYPE_DEFAULT waits for the dongle's ACK internally;
                // a breath between chunks keeps cheap clones happy.
                Thread.sleep(40)
                off = end
            }
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val b = rx.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (b == PROMPT) return sb.toString().uppercase()
                sb.append(b.toInt().toChar())
            }
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
        rx.clear()
    }
}
