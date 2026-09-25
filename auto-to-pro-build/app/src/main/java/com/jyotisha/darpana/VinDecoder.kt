package com.jyotisha.darpana

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VinDecoder(
    private val context: Context,
    private val emit: (String, JSONObject) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)

    fun decode(rawVin: String, modelYear: String, rawTarget: String) {
        val vin = normalizeVin(rawVin)
        val target = if (rawTarget == "onboarding") "onboarding" else "vehicle"

        val validation = validateVin(vin)
        if (validation != null) {
            emit(
                target,
                JSONObject()
                    .put("ok", false)
                    .put("code", "INVALID_VIN")
                    .put("message", validation)
            )
            return
        }

        val local = localDecode(vin)

        executor.execute {
            if (closed.get()) return@execute

            emit(
                target,
                JSONObject()
                    .put("ok", false)
                    .put("loading", true)
                    .put("vin", vin)
                    .put("localRecognized", local != null)
            )

            try {
                val api = requestVin(vin, modelYear.trim())
                emit(target, mergeResults(vin, local, api))
            } catch (t: Throwable) {
                if (local != null) {
                    local.put(
                        "warning",
                        "Российский VIN распознан локально. Дополнительная проверка NHTSA сейчас недоступна; найденные данные проверьте перед сохранением."
                    )
                    local.put("source", "Локальная база AUTO ТО PRO")
                    emit(target, local)
                } else {
                    emit(
                        target,
                        JSONObject()
                            .put("ok", false)
                            .put("vin", vin)
                            .put("code", "NETWORK_ERROR")
                            .put(
                                "message",
                                when (t) {
                                    is java.net.SocketTimeoutException ->
                                        "VIN-сервис не ответил вовремя. Проверьте интернет и повторите."
                                    else ->
                                        "Не удалось получить данные VIN. Проверьте интернет и повторите."
                                }
                            )
                    )
                }
            }
        }
    }

    fun close() {
        closed.set(true)
        executor.shutdownNow()
    }

    private fun localDecode(vin: String): JSONObject? {
        val wmi = vin.substring(0, 3)
        if (wmi != "Z94") return null

        val result = JSONObject()
            .put("ok", true)
            .put("vin", vin)
            .put("localRecognized", true)
            .put("manufacturer", "Hyundai Motor Manufacturing Rus")
            .put("plantCountry", "Россия")
            .put("source", "Локальная база AUTO ТО PRO + NHTSA vPIC")

        modelYearFromCode(vin[9])?.let { result.put("year", it.toString()) }
        if (vin[10] == 'R') result.put("plant", "Санкт-Петербург")

        // Z94G... — российский Hyundai Creta первого поколения.
        if (vin[3] == 'G') {
            result
                .put("make", "Hyundai")
                .put("model", "Creta")
                .put("bodyClass", "Кроссовер / 5-дверный универсал")

            when (vin[7]) {
                '1' -> result
                    .put("engine", "1.6 MPI")
                    .put("fuelRu", "Бензин")
                '3' -> result
                    .put("engine", "2.0 MPI")
                    .put("fuelRu", "Бензин")
            }

            when (vin[8]) {
                'A' -> result.put("gearRu", "Механика")
                'B' -> result
                    .put("gearRu", "Автомат")
                    .put("driveRu", "Передний")
                'D' -> result
                    .put("gearRu", "Автомат")
                    .put("driveRu", "Полный")
            }

            result.put(
                "warning",
                "Российский VIN Z94 распознан локально. База NHTSA ориентирована на рынок США и может выдавать служебные предупреждения о контрольной цифре или регистрации производителя; это само по себе не означает, что VIN недействителен."
            )
        } else {
            result.put(
                "warning",
                "Производитель по WMI Z94 распознан как российское производство Hyundai Motor Manufacturing Rus. Модель по этому шаблону не определена — проверьте данные автомобиля вручную."
            )
        }

        return result
    }

    private fun mergeResults(vin: String, local: JSONObject?, api: JSONObject): JSONObject {
        if (local == null) return api

        if (api.optBoolean("ok", false)) {
            val keys = listOf(
                "make", "model", "year", "engine", "fuel", "fuelRu",
                "bodyClass", "manufacturer", "plantCountry", "vehicleType"
            )
            for (key in keys) {
                if (local.optString(key).isBlank() && api.optString(key).isNotBlank()) {
                    local.put(key, api.optString(key))
                }
            }
        }

        val apiCode = api.optString("errorCode")
        val apiMessage = api.optString("message")
        if (apiCode.isNotBlank() && apiCode != "0") {
            local.put("nhtsaWarning", translateVpicErrors(apiCode, apiMessage))
        }
        local.put("vin", vin)
        local.put("ok", true)
        return local
    }

    private fun requestVin(vin: String, modelYear: String): JSONObject {
        val year = modelYear.filter { it.isDigit() }.takeIf { it.length == 4 }
        val query = buildString {
            append("?format=json")
            if (year != null) append("&modelyear=").append(year)
        }
        val url = URL(
            "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/" +
                    vin + query
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 12000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AUTO-TO-PRO/3.5.1 Android")
        }

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: throw IllegalStateException("Пустой ответ VIN-сервиса")

            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use {
                it.readText()
            }

            if (status !in 200..299) {
                throw IllegalStateException("VIN API HTTP $status")
            }

            val root = JSONObject(body)
            val results = root.optJSONArray("Results")
            val row = results?.optJSONObject(0)
                ?: return JSONObject()
                    .put("ok", false)
                    .put("vin", vin)
                    .put("code", "EMPTY_RESULT")
                    .put("message", "VIN-сервис не вернул данные.")

            return mapResult(vin, row)
        } finally {
            connection.disconnect()
        }
    }

    private fun mapResult(vin: String, row: JSONObject): JSONObject {
        val make = clean(row.optString("Make"))
        val model = clean(row.optString("Model"))
        val year = clean(row.optString("ModelYear"))
        val fuel = clean(row.optString("FuelTypePrimary"))
        val electrification = clean(row.optString("ElectrificationLevel"))
        val engine = buildEngine(row)

        val hasCoreData = listOf(make, model, year, engine).any { it.isNotBlank() }
        val errorCode = clean(row.optString("ErrorCode"))
        val errorText = clean(row.optString("ErrorText"))
        val translated = translateVpicErrors(errorCode, errorText)

        if (!hasCoreData) {
            return JSONObject()
                .put("ok", false)
                .put("vin", vin)
                .put("code", if (errorCode.isBlank()) "NO_MATCH" else errorCode)
                .put("errorCode", errorCode)
                .put(
                    "message",
                    if (translated.isNotBlank())
                        translated
                    else
                        "По этому VIN недостаточно данных для автозаполнения."
                )
        }

        return JSONObject()
            .put("ok", true)
            .put("vin", vin)
            .put("make", make)
            .put("model", model)
            .put("year", year)
            .put("engine", engine)
            .put("fuel", fuel)
            .put("fuelRu", mapFuel(fuel, electrification))
            .put("bodyClass", clean(row.optString("BodyClass")))
            .put("manufacturer", clean(row.optString("Manufacturer")))
            .put("plantCountry", clean(row.optString("PlantCountry")))
            .put("vehicleType", clean(row.optString("VehicleType")))
            .put("errorCode", errorCode)
            .put("warning", if (errorCode.isBlank() || errorCode == "0") "" else translated)
            .put("source", "NHTSA vPIC")
    }

    private fun translateVpicErrors(errorCode: String, errorText: String): String {
        if (errorCode.isBlank() || errorCode == "0") return ""

        val codes = Regex("\\d+").findAll(errorCode).map { it.value }.toSet()
        val messages = mutableListOf<String>()

        if ("1" in codes) {
            messages.add("контрольная цифра не совпала с американским алгоритмом проверки")
        }
        if ("7" in codes) {
            messages.add("производитель не зарегистрирован в базе NHTSA для рынка США")
        }
        if ("400" in codes) {
            messages.add("NHTSA считает часть символов недопустимой для своей схемы")
        }

        if (messages.isNotEmpty()) {
            return "NHTSA сообщает: " + messages.joinToString("; ") +
                    ". Для российских VIN это может быть ограничением американской базы, а не признаком недействительного VIN."
        }

        return if (errorText.isNotBlank()) {
            "NHTSA вернула предупреждение (код $errorCode). Данные VIN могут быть неполными."
        } else {
            "NHTSA вернула предупреждение (код $errorCode)."
        }
    }

    private fun buildEngine(row: JSONObject): String {
        val parts = mutableListOf<String>()

        clean(row.optString("EngineModel"))
            .takeIf { it.isNotBlank() && it.length <= 40 }
            ?.let { parts.add(it) }

        clean(row.optString("DisplacementL"))
            .takeIf { it.isNotBlank() && it != "0" && it != "0.0" }
            ?.let { parts.add(it.trimEnd('0').trimEnd('.') + " л") }

        clean(row.optString("EngineCylinders"))
            .takeIf { it.isNotBlank() && it != "0" }
            ?.let { parts.add(it + " цил.") }

        val configuration = clean(row.optString("EngineConfiguration"))
        if (configuration.isNotBlank()) {
            val ru = when (configuration.lowercase(Locale.ROOT)) {
                "in-line", "inline" -> "рядный"
                "v-shaped", "v shaped" -> "V-образный"
                "flat" -> "оппозитный"
                else -> configuration
            }
            parts.add(ru)
        }

        return parts.distinct().joinToString(" • ")
    }

    private fun mapFuel(fuel: String, electrification: String): String {
        val e = electrification.lowercase(Locale.ROOT)
        if (e.contains("hybrid") || e.contains("hev") || e.contains("phev")) {
            return "Гибрид"
        }
        if (e.contains("bev") || e.contains("electric")) {
            return "Электро"
        }

        return when {
            fuel.contains("diesel", true) -> "Дизель"
            fuel.contains("gasoline", true) || fuel.contains("petrol", true) -> "Бензин"
            fuel.contains("electric", true) -> "Электро"
            fuel.contains("natural gas", true) ||
                    fuel.contains("propane", true) ||
                    fuel.contains("liquefied petroleum", true) -> "Газ"
            fuel.contains("flexible", true) || fuel.contains("ethanol", true) -> "Бензин"
            else -> ""
        }
    }

    private fun modelYearFromCode(code: Char): Int? {
        val sequence = "ABCDEFGHJKLMNPRSTVWXY123456789"
        val index = sequence.indexOf(code)
        if (index < 0) return null
        // Current AUTO ТО PRO support window: 2010–2039.
        val base = 2010
        val year = base + ((index - sequence.indexOf('A') + 30) % 30)
        return if (year in 2010..2039) year else null
    }

    private fun normalizeVin(value: String): String =
        value.uppercase(Locale.ROOT)
            .replace(Regex("[\\s-]+"), "")
            .trim()

    private fun validateVin(vin: String): String? {
        if (vin.length != 17) return "VIN должен содержать 17 символов."
        if (!vin.matches(Regex("^[A-HJ-NPR-Z0-9]{17}$"))) {
            return "VIN содержит недопустимые символы. Буквы I, O и Q не используются."
        }
        return null
    }

    private fun clean(value: String): String {
        val x = value.trim()
        return if (x.equals("null", true) || x.equals("not applicable", true)) "" else x
    }
}
