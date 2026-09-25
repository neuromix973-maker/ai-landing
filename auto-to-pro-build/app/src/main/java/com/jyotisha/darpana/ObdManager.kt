package com.jyotisha.darpana

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class ObdManager(
    private val context: Context,
    private val emit: (String, JSONObject) -> Unit
) {
    companion object {
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PREFS = "auto_to_pro_native"
        private const val KEY_LAST_ADDRESS = "obd_last_address"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val closing = AtomicBoolean(false)

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var connectedAddress: String = ""
    @Volatile private var connectedName: String = ""
    @Volatile private var state: String = "disconnected"
    @Volatile private var lastError: String = ""

    @SuppressLint("MissingPermission")
    fun listPairedDevices(): JSONObject {
        return try {
            val adapter = bluetoothManager()?.adapter
                ?: return JSONObject()
                    .put("ok", false)
                    .put("bluetoothAvailable", false)
                    .put("devices", JSONArray())

            val arr = JSONArray()
            val last = prefs().getString(KEY_LAST_ADDRESS, "") ?: ""
            val devices = adapter.bondedDevices
                .sortedWith(compareByDescending<BluetoothDevice> { looksLikeElm(it.name) }
                    .thenBy { it.name ?: "" })

            for (d in devices) {
                arr.put(
                    JSONObject()
                        .put("name", d.name ?: "Bluetooth-устройство")
                        .put("address", d.address ?: "")
                        .put("likelyObd", looksLikeElm(d.name))
                        .put("last", (d.address ?: "") == last)
                )
            }

            JSONObject()
                .put("ok", true)
                .put("bluetoothAvailable", true)
                .put("bluetoothEnabled", adapter.isEnabled)
                .put("devices", arr)
        } catch (t: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("message", safeMessage(t))
                .put("devices", JSONArray())
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val clean = address.trim().uppercase()
        if (!clean.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) {
            emitError("BAD_ADDRESS", "Некорректный адрес Bluetooth-адаптера")
            return
        }

        executor.execute {
            if (closing.get()) return@execute
            disconnectInternal(false)
            updateState("connecting")
            emit(
                "connecting",
                JSONObject()
                    .put("address", clean)
                    .put("message", "Подключаемся к ELM327…")
            )

            try {
                val adapter = bluetoothManager()?.adapter
                    ?: throw IllegalStateException("Bluetooth не поддерживается")
                if (!adapter.isEnabled) {
                    throw IllegalStateException("Bluetooth выключен")
                }

                val device = adapter.getRemoteDevice(clean)
                val first = device.createRfcommSocketToServiceRecord(SPP_UUID)
                var activeSocket: BluetoothSocket? = null

                try {
                    first.connect()
                    activeSocket = first
                } catch (firstError: Throwable) {
                    closeQuietly(first)
                    activeSocket = tryLegacyChannelOne(device)
                        ?: throw firstError
                }

                socket = activeSocket
                input = activeSocket.inputStream
                output = activeSocket.outputStream
                connectedAddress = clean
                connectedName = device.name ?: "ELM327"

                val init = initializeElm()
                if (!init.optBoolean("ok", false)) {
                    throw IllegalStateException(init.optString("message", "ELM327 не отвечает"))
                }

                prefs().edit().putString(KEY_LAST_ADDRESS, clean).apply()
                lastError = ""
                updateState("connected")

                emit(
                    "connected",
                    JSONObject()
                        .put("address", clean)
                        .put("name", connectedName)
                        .put("adapter", init.optString("adapter", "ELM327"))
                        .put("protocol", init.optString("protocol", "AUTO"))
                )
            } catch (t: Throwable) {
                lastError = safeMessage(t)
                disconnectInternal(false)
                updateState("error")
                emitError(
                    "CONNECT_FAILED",
                    "Не удалось подключиться к ELM327: " + safeMessage(t)
                )
            }
        }
    }

    fun disconnect() {
        executor.execute {
            disconnectInternal(true)
        }
    }

    fun readDtc() {
        executor.execute {
            if (!isConnected()) {
                emitError("NOT_CONNECTED", "Сначала подключите ELM327")
                return@execute
            }

            updateState("reading")
            emit("reading", JSONObject().put("message", "Читаем ошибки ЭБУ…"))

            try {
                val raw = sendCommand("03", 8000)
                if (raw.isBlank()) {
                    throw IllegalStateException("Нет ответа от адаптера")
                }

                val upper = raw.uppercase()
                if (upper.contains("NO DATA")) {
                    updateState("connected")
                    emit(
                        "dtc",
                        JSONObject()
                            .put("codes", JSONArray())
                            .put("raw", "NO DATA")
                            .put("message", "Активных кодов ошибок не найдено")
                    )
                    return@execute
                }

                if (upper.contains("UNABLE TO CONNECT") ||
                    upper.contains("BUS ERROR") ||
                    upper.contains("CAN ERROR")
                ) {
                    throw IllegalStateException("Нет связи с ЭБУ автомобиля")
                }

                val codes = parseMode03(raw)
                updateState("connected")
                emit(
                    "dtc",
                    JSONObject()
                        .put("codes", codes)
                        .put("raw", raw.take(2500))
                        .put(
                            "message",
                            if (codes.length() == 0)
                                "Коды ошибок не распознаны. Сырой ответ сохранён для диагностики."
                            else
                                "Найдено кодов: " + codes.length()
                        )
                )
            } catch (t: Throwable) {
                updateState(if (isConnected()) "connected" else "error")
                emitError("READ_DTC_FAILED", "Не удалось прочитать DTC: " + safeMessage(t))
            }
        }
    }

    fun statusJson(): JSONObject {
        return JSONObject()
            .put("state", state)
            .put("connected", isConnected())
            .put("name", connectedName)
            .put("address", connectedAddress)
            .put("lastError", lastError)
    }

    fun close() {
        closing.set(true)
        try {
            disconnectInternal(false)
        } catch (_: Throwable) {
        }
        executor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private fun tryLegacyChannelOne(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod(
                "createRfcommSocket",
                Int::class.javaPrimitiveType
            )
            val fallback = method.invoke(device, 1) as BluetoothSocket
            fallback.connect()
            fallback
        } catch (_: Throwable) {
            null
        }
    }

    private fun initializeElm(): JSONObject {
        drainInput()

        val reset = sendCommand("ATZ", 6000)
        val adapterName = reset
            .replace(">", " ")
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(80)

        val initCommands = listOf(
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH0",
            "ATAT1",
            "ATSP0"
        )

        for (cmd in initCommands) {
            val response = sendCommand(cmd, 3500)
            if (!isCommandAccepted(response)) {
                return JSONObject()
                    .put("ok", false)
                    .put("message", "ELM327 не принял команду " + cmd)
            }
        }

        val protocolProbe = sendCommand("0100", 8000)
        if (protocolProbe.uppercase().contains("UNABLE TO CONNECT")) {
            return JSONObject()
                .put("ok", false)
                .put("message", "ELM327 найден, но нет связи с ЭБУ. Включите зажигание.")
        }

        return JSONObject()
            .put("ok", true)
            .put("adapter", if (adapterName.isBlank()) "ELM327" else adapterName)
            .put(
                "protocol",
                when {
                    protocolProbe.uppercase().contains("NO DATA") -> "AUTO / NO DATA"
                    protocolProbe.isBlank() -> "AUTO"
                    else -> "AUTO"
                }
            )
    }

    private fun isCommandAccepted(response: String): Boolean {
        val s = response.uppercase()
        return s.contains("OK") ||
                s.contains("ELM") ||
                (!s.contains("?") && !s.contains("ERROR") && response.isNotBlank())
    }

    @Synchronized
    private fun sendCommand(command: String, timeoutMs: Long): String {
        val out = output ?: throw IllegalStateException("Нет соединения с ELM327")
        val inp = input ?: throw IllegalStateException("Нет соединения с ELM327")

        drainInput()

        out.write((command.trim() + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()

        val deadline = System.currentTimeMillis() + timeoutMs
        val result = StringBuilder()
        val buffer = ByteArray(512)
        var receivedAny = false

        while (System.currentTimeMillis() < deadline && !closing.get()) {
            val available = try {
                inp.available()
            } catch (_: Throwable) {
                0
            }

            if (available > 0) {
                val count = inp.read(buffer, 0, min(buffer.size, available))
                if (count > 0) {
                    receivedAny = true
                    val part = String(buffer, 0, count, Charsets.US_ASCII)
                    result.append(part)
                    if (result.contains(">")) break
                }
            } else {
                if (receivedAny && result.contains(">")) break
                Thread.sleep(25)
            }
        }

        return result.toString()
            .replace(command, "", ignoreCase = true)
            .replace(">", "")
            .trim()
    }

    private fun drainInput() {
        val inp = input ?: return
        try {
            var loops = 0
            val buffer = ByteArray(512)
            while (inp.available() > 0 && loops < 12) {
                inp.read(buffer, 0, min(buffer.size, inp.available()))
                loops++
            }
        } catch (_: Throwable) {
        }
    }

    private fun parseMode03(raw: String): JSONArray {
        val payload = StringBuilder()
        var collecting = false

        for (sourceLine in raw.uppercase().split(Regex("[\\r\\n]+"))) {
            var line = sourceLine.trim()
            if (line.isEmpty()) continue
            if (line.contains("SEARCHING") ||
                line.contains("NO DATA") ||
                line.contains("STOPPED") ||
                line.contains("UNABLE") ||
                line.contains("ERROR") ||
                line.contains("BUS INIT")
            ) continue

            if (line.contains(":")) {
                line = line.substringAfter(":")
            }

            var hex = line.filter { it in "0123456789ABCDEF" }
            if (hex.length < 2) continue
            if (hex.length % 2 != 0) hex = hex.dropLast(1)

            val idx = findEvenIndex(hex, "43")
            if (idx >= 0) {
                collecting = true
                payload.append(hex.substring(idx + 2))
                continue
            }

            if (collecting) {
                // ISO-TP continuation frames often start 21, 22, 23...
                if (hex.length >= 2 && hex[0] == '2' && hex[1] in "0123456789ABCDEF") {
                    hex = hex.substring(2)
                }
                payload.append(hex)
            }
        }

        val unique = linkedSetOf<String>()
        val data = payload.toString()
        var i = 0
        while (i + 4 <= data.length) {
            val rawCode = data.substring(i, i + 4)
            i += 4
            if (rawCode == "0000") continue
            try {
                val code = decodeDtc(rawCode)
                if (code.matches(Regex("^[PCBU][0-3][0-9A-F]{3}$"))) {
                    unique.add(code)
                }
            } catch (_: Throwable) {
            }
        }

        val arr = JSONArray()
        for (code in unique) {
            arr.put(
                JSONObject()
                    .put("code", code)
                    .put("description", describeDtc(code))
                    .put("system", dtcSystem(code))
                    .put("standard", if (code.length > 1 && code[1] == '0') "SAE / generic" else "manufacturer / extended")
            )
        }
        return arr
    }

    private fun findEvenIndex(text: String, needle: String): Int {
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return -1
            if (i % 2 == 0) return i
            from = i + 1
        }
    }

    private fun decodeDtc(raw: String): String {
        val a = raw.substring(0, 2).toInt(16)
        val b = raw.substring(2, 4).toInt(16)
        val system = charArrayOf('P', 'C', 'B', 'U')[(a shr 6) and 0x03]
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        val d3 = (b shr 4) and 0x0F
        val d4 = b and 0x0F
        return "" + system + d1 + d2.toString(16).uppercase() +
                d3.toString(16).uppercase() + d4.toString(16).uppercase()
    }

    private fun dtcSystem(code: String): String = when (code.firstOrNull()) {
        'P' -> "Силовой агрегат"
        'C' -> "Шасси"
        'B' -> "Кузов"
        'U' -> "Сеть / обмен данными"
        else -> "Неизвестная система"
    }

    private fun describeDtc(code: String): String {
        if (code.matches(Regex("^P03(0[1-9]|1[0-2])$"))) {
            val cylinder = code.takeLast(2).toIntOrNull() ?: 0
            if (cylinder > 0) return "Пропуски зажигания в цилиндре " + cylinder
        }

        return COMMON_DTC[code] ?: when {
            code.startsWith("P03") -> "Ошибка системы зажигания / пропусков воспламенения"
            code.startsWith("P01") -> "Ошибка измерения воздуха / топлива или датчиков двигателя"
            code.startsWith("P04") -> "Ошибка системы контроля выбросов"
            code.startsWith("P07") -> "Ошибка трансмиссии"
            code.startsWith("U") -> "Ошибка обмена данными между электронными блоками"
            code.startsWith("C") -> "Ошибка системы шасси"
            code.startsWith("B") -> "Ошибка кузовной электроники"
            else -> "Код OBD-II. Для точной расшифровки используйте AI-помощник и руководство автомобиля."
        }
    }

    private fun isConnected(): Boolean {
        return try {
            socket?.isConnected == true && input != null && output != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateState(value: String) {
        state = value
    }

    private fun emitError(code: String, message: String) {
        lastError = message
        emit(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message)
        )
    }

    private fun disconnectInternal(emitEvent: Boolean) {
        closeQuietly(input)
        closeQuietly(output)
        closeQuietly(socket)
        input = null
        output = null
        socket = null
        connectedAddress = ""
        connectedName = ""
        updateState("disconnected")
        if (emitEvent) {
            emit("disconnected", JSONObject().put("message", "ELM327 отключён"))
        }
    }

    private fun bluetoothManager(): BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private fun prefs() =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun looksLikeElm(name: String?): Boolean {
        val n = (name ?: "").uppercase()
        return listOf(
            "ELM", "OBD", "V-LINK", "VLINK", "VEEPEAK",
            "VGATE", "V-GATE", "KONNWEI", "KW902", "OBDLINK"
        ).any { n.contains(it) }
    }

    private fun safeMessage(t: Throwable): String =
        (t.message ?: t.javaClass.simpleName).replace(Regex("[\\r\\n]+"), " ").take(180)

    private fun closeQuietly(value: Any?) {
        try {
            when (value) {
                is InputStream -> value.close()
                is OutputStream -> value.close()
                is BluetoothSocket -> value.close()
            }
        } catch (_: Throwable) {
        }
    }

    private val COMMON_DTC = mapOf(
        "P0100" to "Неисправность цепи датчика массового расхода воздуха (MAF)",
        "P0101" to "MAF: диапазон / производительность сигнала",
        "P0110" to "Неисправность цепи датчика температуры впускного воздуха",
        "P0120" to "Неисправность цепи датчика положения дроссельной заслонки",
        "P0130" to "Неисправность цепи датчика кислорода, банк 1 датчик 1",
        "P0171" to "Слишком бедная смесь, банк 1",
        "P0172" to "Слишком богатая смесь, банк 1",
        "P0200" to "Неисправность цепи форсунок",
        "P0300" to "Случайные / множественные пропуски зажигания",
        "P0325" to "Неисправность цепи датчика детонации",
        "P0335" to "Неисправность цепи датчика положения коленвала",
        "P0340" to "Неисправность цепи датчика положения распредвала",
        "P0400" to "Неисправность системы рециркуляции отработавших газов (EGR)",
        "P0420" to "Эффективность катализатора ниже порога, банк 1",
        "P0430" to "Эффективность катализатора ниже порога, банк 2",
        "P0440" to "Общая неисправность системы улавливания паров топлива (EVAP)",
        "P0455" to "Крупная утечка в системе EVAP",
        "P0500" to "Неисправность датчика скорости автомобиля",
        "P0562" to "Низкое напряжение бортовой сети",
        "P0700" to "Система управления трансмиссией сообщает о неисправности"
    )
}
