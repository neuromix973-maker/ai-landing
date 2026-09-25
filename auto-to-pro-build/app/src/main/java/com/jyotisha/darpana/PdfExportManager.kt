package com.jyotisha.darpana

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class PdfExportManager(private val activity: Activity) {
    companion object {
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 42f
        private const val BOTTOM = 48f
        private const val GOLD = 0xFFD4AF37.toInt()
        private const val DARK = 0xFF101214.toInt()
        private const val MUTED = 0xFF667078.toInt()
    }

    fun exportAndShare(payloadJson: String): Boolean {
        return try {
            val payload = JSONObject(payloadJson)
            val exportsDir = File(activity.cacheDir, "exports").apply { mkdirs() }
            exportsDir.listFiles()?.forEach { old ->
                if (old.isFile && System.currentTimeMillis() - old.lastModified() > 3L * 24 * 60 * 60 * 1000) {
                    old.delete()
                }
            }

            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            val file = File(exportsDir, "AUTO_TO_PRO_$stamp.pdf")
            buildPdf(payload, file)
            share(file)
            true
        } catch (t: Throwable) {
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    "Не удалось создать PDF: " + safe(t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
            false
        }
    }

    private fun buildPdf(payload: JSONObject, file: File) {
        val document = PdfDocument()
        val writer = PdfWriter(document)
        try {
            writer.start()
            writer.title("AUTO ТО PRO", "История обслуживания и расходов")
            drawVehicle(writer, payload.optJSONObject("vehicle"))
            drawSummary(writer, payload)
            drawServices(writer, payload.optJSONArray("services") ?: JSONArray())
            drawExpenses(writer, payload.optJSONArray("expenses") ?: JSONArray())
            writer.finish()
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            try { writer.finishIfOpen() } catch (_: Throwable) { }
            document.close()
        }
    }

    private fun drawVehicle(w: PdfWriter, vehicle: JSONObject?) {
        w.section("Автомобиль")
        if (vehicle == null) {
            w.body("Автомобиль не добавлен")
            return
        }

        val brand = vehicle.optString("brand")
        val model = vehicle.optString("model")
        val year = vehicle.optString("year")
        val title = listOf(brand, model, year).filter { it.isNotBlank() }.joinToString(" ")
        w.body(if (title.isBlank()) "Данные автомобиля" else title, bold = true)

        val km = vehicle.optLong("km", 0L)
        if (km > 0) w.keyValue("Пробег", formatNumber(km) + " км")
        vehicle.optString("engine").takeIf { it.isNotBlank() }?.let { w.keyValue("Двигатель", it) }
        vehicle.optString("fuel").takeIf { it.isNotBlank() }?.let { w.keyValue("Топливо", it) }
        vehicle.optString("gear").takeIf { it.isNotBlank() }?.let { w.keyValue("Коробка", it) }
        vehicle.optString("plate").takeIf { it.isNotBlank() }?.let { w.keyValue("Госномер", it) }
        vehicle.optString("vin").takeIf { it.isNotBlank() }?.let { w.keyValue("VIN", it) }
        w.spacer(8f)
    }

    private fun drawSummary(w: PdfWriter, payload: JSONObject) {
        val services = payload.optJSONArray("services") ?: JSONArray()
        val expenses = payload.optJSONArray("expenses") ?: JSONArray()
        var serviceCost = 0.0
        for (i in 0 until services.length()) {
            serviceCost += services.optJSONObject(i)?.optDouble("cost", 0.0) ?: 0.0
        }
        var expenseCost = 0.0
        for (i in 0 until expenses.length()) {
            expenseCost += expenses.optJSONObject(i)?.optDouble("cost", 0.0) ?: 0.0
        }

        w.section("Сводка")
        w.keyValue("Записей ТО", services.length().toString())
        w.keyValue("Расходов", expenses.length().toString())
        w.keyValue("Стоимость ТО", formatMoney(serviceCost))
        w.keyValue("Прочие расходы", formatMoney(expenseCost))
        w.keyValue("Итого", formatMoney(serviceCost + expenseCost), emphasized = true)
        w.spacer(10f)
    }

    private fun drawServices(w: PdfWriter, services: JSONArray) {
        w.section("История технического обслуживания")
        if (services.length() == 0) {
            w.body("Записей обслуживания пока нет.")
            w.spacer(8f)
            return
        }

        val list = mutableListOf<JSONObject>()
        for (i in 0 until services.length()) {
            services.optJSONObject(i)?.let { list.add(it) }
        }
        list.sortByDescending { it.optString("date") }

        list.forEachIndexed { index, s ->
            w.ensure(78f)
            val title = s.optString("type").ifBlank { "Обслуживание" }
            w.itemTitle((index + 1).toString() + ". " + title)
            val details = mutableListOf<String>()
            s.optString("date").takeIf { it.isNotBlank() }?.let { details.add(formatDate(it)) }
            val km = s.optLong("km", 0L)
            if (km > 0) details.add(formatNumber(km) + " км")
            val cost = s.optDouble("cost", 0.0)
            if (cost > 0) details.add(formatMoney(cost))
            if (details.isNotEmpty()) w.body(details.joinToString(" • "), muted = true)
            s.optString("shop").takeIf { it.isNotBlank() }?.let { w.body("Сервис: $it") }
            s.optString("note").takeIf { it.isNotBlank() }?.let { w.body("Примечание: $it") }
            w.divider()
        }
        w.spacer(8f)
    }

    private fun drawExpenses(w: PdfWriter, expenses: JSONArray) {
        w.section("Расходы")
        if (expenses.length() == 0) {
            w.body("Записей расходов пока нет.")
            return
        }

        val list = mutableListOf<JSONObject>()
        for (i in 0 until expenses.length()) {
            expenses.optJSONObject(i)?.let { list.add(it) }
        }
        list.sortByDescending { it.optString("date") }

        list.forEachIndexed { index, e ->
            w.ensure(62f)
            val category = e.optString("cat").ifBlank { "Расход" }
            val amount = e.optDouble("cost", 0.0)
            w.itemTitle((index + 1).toString() + ". " + category + " — " + formatMoney(amount))
            e.optString("date").takeIf { it.isNotBlank() }?.let { w.body(formatDate(it), muted = true) }
            e.optString("note").takeIf { it.isNotBlank() }?.let { w.body(it) }
            w.divider()
        }
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AUTO ТО PRO — история ТО и расходов")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.runOnUiThread {
            activity.startActivity(Intent.createChooser(send, "Сохранить или отправить PDF"))
        }
    }

    private fun formatDate(value: String): String {
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            val output = SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU"))
            output.format(input.parse(value) ?: return value)
        } catch (_: Throwable) {
            value
        }
    }

    private fun formatMoney(value: Double): String = formatNumber(value.toLong()) + " ₽"

    private fun formatNumber(value: Long): String =
        NumberFormat.getIntegerInstance(Locale("ru", "RU")).format(value)

    private fun safe(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").take(180)

    private inner class PdfWriter(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = MARGIN
        private var pageNumber = 0

        private val titlePaint = paint(24f, DARK, true)
        private val subtitlePaint = paint(11f, MUTED, false)
        private val sectionPaint = paint(15f, GOLD, true)
        private val bodyPaint = paint(10.5f, DARK, false)
        private val bodyBoldPaint = paint(10.5f, DARK, true)
        private val mutedPaint = paint(9.5f, MUTED, false)
        private val keyPaint = paint(9.5f, MUTED, false)
        private val valuePaint = paint(9.5f, DARK, true)
        private val footerPaint = paint(8.5f, MUTED, false)
        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE6E6E6.toInt()
            strokeWidth = 1f
        }

        fun start() = newPage()

        fun title(main: String, sub: String) {
            canvas?.drawText(main, MARGIN, y, titlePaint)
            canvas?.drawRect(
                MARGIN,
                y + 8f,
                MARGIN + 80f,
                y + 11f,
                Paint().apply { color = GOLD }
            )
            y += 26f
            canvas?.drawText(sub, MARGIN, y, subtitlePaint)
            y += 24f
        }

        fun section(text: String) {
            ensure(36f)
            y += 4f
            canvas?.drawText(text, MARGIN, y, sectionPaint)
            y += 18f
        }

        fun itemTitle(text: String) {
            drawWrapped(text, bodyBoldPaint, PAGE_WIDTH - MARGIN * 2, 14f)
        }

        fun body(text: String, bold: Boolean = false, muted: Boolean = false) {
            val p = when {
                muted -> mutedPaint
                bold -> bodyBoldPaint
                else -> bodyPaint
            }
            drawWrapped(text, p, PAGE_WIDTH - MARGIN * 2, if (muted) 12.5f else 14f)
        }

        fun keyValue(key: String, value: String, emphasized: Boolean = false) {
            ensure(18f)
            val c = canvas ?: return
            c.drawText(key, MARGIN, y, keyPaint)
            val p = if (emphasized) {
                Paint(valuePaint).apply {
                    textSize = 11f
                    color = GOLD
                }
            } else valuePaint
            val width = p.measureText(value)
            c.drawText(value, max(MARGIN + 190f, PAGE_WIDTH - MARGIN - width), y, p)
            y += 15f
        }

        fun divider() {
            ensure(14f)
            y += 3f
            canvas?.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint)
            y += 10f
        }

        fun spacer(px: Float) {
            ensure(px + 2f)
            y += px
        }

        fun ensure(required: Float) {
            if (y + required > PAGE_HEIGHT - BOTTOM) newPage()
        }

        private fun drawWrapped(text: String, p: Paint, maxWidth: Float, lineHeight: Float) {
            val normalized = text.replace(Regex("[\\r\\n]+"), " ").trim()
            if (normalized.isEmpty()) return
            var line = ""
            for (word in normalized.split(Regex("\\s+"))) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= maxWidth) {
                    line = candidate
                } else {
                    if (line.isNotEmpty()) drawLine(line, p, lineHeight)
                    if (p.measureText(word) <= maxWidth) {
                        line = word
                    } else {
                        var chunk = ""
                        for (ch in word) {
                            val next = chunk + ch
                            if (p.measureText(next) > maxWidth && chunk.isNotEmpty()) {
                                drawLine(chunk, p, lineHeight)
                                chunk = ch.toString()
                            } else {
                                chunk = next
                            }
                        }
                        line = chunk
                    }
                }
            }
            if (line.isNotEmpty()) drawLine(line, p, lineHeight)
        }

        private fun drawLine(text: String, p: Paint, lineHeight: Float) {
            ensure(lineHeight + 2f)
            canvas?.drawText(text, MARGIN, y, p)
            y += lineHeight
        }

        private fun newPage() {
            finishIfOpen()
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(
                PAGE_WIDTH,
                PAGE_HEIGHT,
                pageNumber
            ).create()
            page = document.startPage(info)
            canvas = page?.canvas
            canvas?.drawColor(Color.WHITE)
            y = MARGIN
        }

        fun finish() = finishIfOpen()

        fun finishIfOpen() {
            val p = page ?: return
            val c = canvas
            if (c != null) {
                val footer = "AUTO ТО PRO • стр. $pageNumber"
                c.drawText(footer, MARGIN, PAGE_HEIGHT - 24f, footerPaint)
            }
            document.finishPage(p)
            page = null
            canvas = null
        }

        private fun paint(size: Float, colorValue: Int, bold: Boolean): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorValue
                textSize = size
                typeface = Typeface.create(
                    "sans-serif",
                    if (bold) Typeface.BOLD else Typeface.NORMAL
                )
            }
    }
}
