package com.fixmylife.selfiescreen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nordic-UART link to the selfie screen.
 * Every BLE packet = payload chunk + 1 trailing byte (1 = more follows, 0 = last).
 * Commands are prefixed AA 55; JPEG frames are sent raw (no prefix).
 */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String)
        fun onReady()
        fun onDisconnected()
        fun onCommand(cmd: ByteArray)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9d")
        val WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9d")
        val NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9d")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val HEADER = byteArrayOf(0xAA.toByte(), 0x55.toByte())
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val queue = LinkedBlockingDeque<ByteArray>()
    private val writing = AtomicBoolean(false)
    private var frac = ByteArray(0)

    @Volatile var mtu = 23
        private set
    @Volatile var ready = false
        private set

    val pendingPackets: Int get() = queue.size

    fun connect(device: BluetoothDevice) {
        disconnect()
        listener.onStatus("Connecting to ${device.name ?: device.address}…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        ready = false
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        writeChar = null
        queue.clear()
        writing.set(false)
        frac = ByteArray(0)
    }

    fun sendCommand(vararg bytes: Int) {
        send(HEADER + ByteArray(bytes.size) { bytes[it].toByte() })
    }

    @Synchronized
    fun send(data: ByteArray) {
        if (!ready || data.isEmpty()) return
        val chunk = (mtu - 4).coerceAtLeast(1) // 3 bytes ATT header + 1 flag byte
        var off = 0
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            val pkt = ByteArray(n + 1)
            System.arraycopy(data, off, pkt, 0, n)
            off += n
            pkt[n] = if (off < data.size) 1 else 0
            queue.add(pkt)
        }
        pump()
    }

    private fun pump() {
        if (!writing.compareAndSet(false, true)) return
        val pkt = queue.poll()
        if (pkt == null) {
            writing.set(false)
            return
        }
        val g = gatt
        val c = writeChar
        if (g == null || c == null) {
            writing.set(false)
            return
        }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            legacyWrite(g, c, pkt)
        }
        if (!ok) {
            // Stack busy: put it back and retry shortly
            queue.addFirst(pkt)
            writing.set(false)
            handler.postDelayed({ pump() }, 5)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, pkt: ByteArray): Boolean {
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = pkt
        return g.writeCharacteristic(c)
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v)
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    private fun handleIncoming(raw: ByteArray) {
        if (raw.isEmpty()) return
        // License reply (AA 55 F0 ...) arrives unframed; we don't need it
        if (raw.size >= 3 && raw[0] == 0xAA.toByte() && raw[1] == 0x55.toByte() && raw[2] == 0xF0.toByte()) return

        frac += raw.copyOfRange(0, raw.size - 1)
        if (raw.last().toInt() == 1) return
        val msg = frac
        frac = ByteArray(0)

        if (msg.size > 2 && msg[0] == 0xAA.toByte() && msg[1] == 0x55.toByte()) {
            val cmd = msg.copyOfRange(2, msg.size)
            if ((cmd[0].toInt() and 0xFF) == 0xF7 && cmd.size >= 3) {
                mtu = ByteBuffer.wrap(cmd, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            }
            listener.onCommand(cmd)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onStatus("Discovering services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
                listener.onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                listener.onStatus("Not a selfie screen (no UART service)")
                disconnect()
                listener.onDisconnected()
                return
            }
            writeChar = svc.getCharacteristic(WRITE)
            val notify = svc.getCharacteristic(NOTIFY)
            if (writeChar == null || notify == null) {
                listener.onStatus("Missing characteristics")
                disconnect()
                listener.onDisconnected()
                return
            }
            g.setCharacteristicNotification(notify, true)
            val d = notify.getDescriptor(CCCD)
            if (d != null) enableNotify(g, d) else g.requestMtu(500)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            g.requestMtu(500)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            ready = true
            listener.onReady()
            sendCommand(0xF4, 1) // COMMAND_ANDROID, same as the official app
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing.set(false)
            pump()
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(value)
        }

        // Android 10–12
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleIncoming(c.value ?: return)
        }
    }
}
