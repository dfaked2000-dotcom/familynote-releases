package com.survey.bt580

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

data class FoundDevice(val name: String, val address: String, val rssi: Int)

/**
 * kind: 0 = служебное, 1 = данные на прибор (TX), 2 = данные с прибора (RX),
 *       3 = AT-команда (TX), 4 = ответ адаптера на AT (RX)
 */
data class LogLine(val kind: Int, val text: String)

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class BleClient(private val context: Context) {

    companion object {
        private fun u(short: String): UUID =
            UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        val SERVICE: UUID = u("fff0")
        val CH_FFF1: UUID = u("fff1")
        val CH_FFF3: UUID = u("fff3")
        val CH_FFF4: UUID = u("fff4")
        val CH_FFF6: UUID = u("fff6")
        val CCCD: UUID = u("2902")

        val BAUD_RATES = intArrayOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)
    }

    // ---------- состояние для интерфейса ----------
    var statusText by mutableStateOf("Не подключено")
    var connected by mutableStateOf(false)
    var linkUp by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    val devices = mutableStateListOf<FoundDevice>()
    val log = mutableStateListOf<LogLine>()
    var logVersion by mutableIntStateOf(0)
    var lastReply by mutableStateOf("")

    var hex by mutableStateOf(false)
    var atCrLf by mutableStateOf(false)
    var dataCrLf by mutableStateOf(true)
    var dataChannel by mutableStateOf("FFF3")

    // ---------- внутреннее ----------
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    @Volatile private var chFff1: BluetoothGattCharacteristic? = null
    @Volatile private var chFff3: BluetoothGattCharacteristic? = null
    @Volatile private var chFff4: BluetoothGattCharacteristic? = null
    @Volatile private var chFff6: BluetoothGattCharacteristic? = null

    private val pendingNotify = ArrayDeque<BluetoothGattCharacteristic>()
    private val writeQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writing = false
    private var writeToken = 0

    private var lastEnded = true
    private var lastTime = 0L

    // ---------- журнал ----------
    private fun addLog(kind: Int, text: String) {
        log.add(LogLine(kind, text))
        if (log.size > 1000) {
            repeat(200) { log.removeAt(0) }
        }
        logVersion++
    }

    private fun printable(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch < ' ' && ch != '\n' && ch != '\r' && ch != '\t') sb.append('·') else sb.append(ch)
        }
        return sb.toString()
    }

    private fun hexString(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }

    /** Склеивает куски потока (BLE режет данные по 20 байт) в нормальные строки. */
    private fun addStream(kind: Int, rawIn: String) {
        val now = System.currentTimeMillis()
        val parts = printable(rawIn).replace("\r", "").split("\n")
        var first = true
        for ((i, p) in parts.withIndex()) {
            val isLast = i == parts.lastIndex
            if (isLast && p.isEmpty()) {
                lastEnded = true
                break
            }
            val join = first && log.isNotEmpty() && log.last().kind == kind &&
                !lastEnded && now - lastTime < 400
            if (join) {
                log[log.lastIndex] = LogLine(kind, log.last().text + p)
                logVersion++
            } else {
                addLog(kind, p)
            }
            first = false
            lastEnded = !isLast
        }
        lastTime = now
    }

    fun clearLog() {
        log.clear()
        lastEnded = true
        logVersion++
    }

    fun logText(): String = log.joinToString("\n") { it.text }

    // ---------- поиск ----------
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: dev.name ?: "(без имени)"
            val found = FoundDevice(name, dev.address, result.rssi)
            main.post {
                val i = devices.indexOfFirst { it.address == found.address }
                if (i >= 0) devices[i] = found else devices.add(found)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                statusText = "Ошибка поиска: $errorCode"
            }
        }
    }

    fun startScan() {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            statusText = "Включите Bluetooth"
            return
        }
        val scanner = ad.bluetoothLeScanner ?: run {
            statusText = "Bluetooth недоступен"
            return
        }
        devices.clear()
        scanner.startScan(scanCallback)
        scanning = true
        statusText = "Поиск устройств..."
        main.postDelayed({ stopScan() }, 10000)
    }

    fun stopScan() {
        if (!scanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanning = false
        if (!connected && gatt == null) statusText = "Не подключено"
    }

    // ---------- подключение ----------
    fun connect(address: String) {
        if (gatt != null) return
        stopScan()
        val dev = adapter?.getRemoteDevice(address) ?: return
        statusText = "Подключение..."
        gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun release() {
        stopScan()
        gatt?.close()
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post {
                    linkUp = true
                    statusText = "Связь есть, читаю сервисы..."
                }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                main.post {
                    gatt = null
                    connected = false
                    linkUp = false
                    chFff1 = null
                    chFff3 = null
                    chFff4 = null
                    chFff6 = null
                    writeQueue.clear()
                    writing = false
                    statusText = if (st == 0) "Отключено" else "Отключено (код $st)"
                    addLog(0, "Отключено")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) {
                main.post {
                    statusText = "Сервис FFF0 не найден"
                    addLog(0, "Сервис FFF0 не найден — это точно BT580?")
                }
                return
            }
            chFff1 = svc.getCharacteristic(CH_FFF1)
            chFff3 = svc.getCharacteristic(CH_FFF3)
            chFff4 = svc.getCharacteristic(CH_FFF4)
            chFff6 = svc.getCharacteristic(CH_FFF6)
            pendingNotify.clear()
            chFff4?.let { pendingNotify.addLast(it) }
            chFff6?.let { pendingNotify.addLast(it) }
            enableNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            enableNext(g)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            val copy = v.copyOf()
            val id = c.uuid
            main.post {
                if (id == CH_FFF6) onAtReply(copy) else onData(copy)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post {
                writing = false
                pump()
            }
        }
    }

    private fun enableNext(g: BluetoothGatt) {
        val c = pendingNotify.removeFirstOrNull()
        if (c == null) {
            main.post {
                connected = true
                statusText = "Подключено"
                addLog(0, "Готово к работе")
            }
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD)
        if (d == null) {
            enableNext(g)
            return
        }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    // ---------- приём ----------
    private fun onData(b: ByteArray) {
        if (hex) addLog(2, hexString(b)) else addStream(2, String(b, Charsets.ISO_8859_1))
    }

    private fun onAtReply(b: ByteArray) {
        val text = String(b, Charsets.ISO_8859_1).trim()
        var shown = text
        val m = Regex("baudrate:\\s*(\\d)").find(text)
        val code = m?.groupValues?.get(1)?.toIntOrNull()
        if (code != null && code in 0..7) shown += "  (${BAUD_RATES[code]} бод)"
        lastReply = shown
        addLog(4, shown)
    }

    // ---------- отправка ----------
    private fun enqueueWrite(c: BluetoothGattCharacteristic, data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val end = minOf(i + 20, data.size)
            writeQueue.addLast(Pair(c, data.copyOfRange(i, end)))
            i = end
        }
        pump()
    }

    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val item = writeQueue.removeFirstOrNull() ?: return
        val c = item.first
        c.writeType =
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = item.second
        val ok = g.writeCharacteristic(c)
        if (!ok) {
            writeQueue.clear()
            addLog(0, "Не удалось отправить")
            return
        }
        writing = true
        val token = ++writeToken
        main.postDelayed({
            if (writing && token == writeToken) {
                writing = false
                pump()
            }
        }, 600)
    }

    /** AT-команда адаптеру (через характеристику FFF6). */
    fun sendAt(cmd: String) {
        val c = chFff6
        if (c == null) {
            addLog(0, "FFF6 не найдена")
            return
        }
        val text = if (atCrLf) cmd + "\r\n" else cmd
        addLog(3, cmd)
        enqueueWrite(c, text.toByteArray(Charsets.ISO_8859_1))
    }

    /** Данные на RS232-прибор. */
    fun sendData(text: String) {
        val c = if (dataChannel == "FFF1") chFff1 else chFff3
        if (c == null) {
            addLog(0, "Характеристика $dataChannel не найдена")
            return
        }
        var bytes: ByteArray
        if (hex) {
            bytes = parseHex(text) ?: run {
                addLog(0, "Неверный HEX: $text")
                return
            }
        } else {
            bytes = text.toByteArray(Charsets.ISO_8859_1)
        }
        if (dataCrLf) bytes += byteArrayOf(13, 10)
        addLog(1, if (hex) hexString(bytes) else text)
        enqueueWrite(c, bytes)
    }

    private fun parseHex(t: String): ByteArray? {
        return try {
            t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            null
        }
    }
}
