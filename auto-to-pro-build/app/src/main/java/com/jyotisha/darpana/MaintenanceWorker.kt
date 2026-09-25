package com.jyotisha.darpana

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class MaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            MaintenanceScheduler.PREFS,
            Context.MODE_PRIVATE
        )

        NotificationSupport.ensureChannels(applicationContext)

        if (!prefs.getBoolean(MaintenanceScheduler.KEY_ENABLED, true)) {
            return Result.success()
        }

        if (!NotificationSupport.permissionGranted(applicationContext) ||
            !NotificationSupport.appNotificationsEnabled(applicationContext)
        ) {
            return Result.success()
        }

        val forceNotify = inputData.getBoolean("force_notify", false)

        val raw = prefs.getString(MaintenanceScheduler.KEY_PLAN, "[]") ?: "[]"
        val plan = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }

        val documentRaw = prefs.getString(
            MaintenanceScheduler.KEY_DOCUMENT_PLAN,
            "[]"
        ) ?: "[]"
        val documentPlan = try { JSONArray(documentRaw) } catch (_: Throwable) { JSONArray() }

        var attentionCount = 0

        for (i in 0 until plan.length()) {
            val item = plan.optJSONObject(i) ?: continue
            if (processMaintenance(item, prefs, forceNotify)) attentionCount++
        }

        attentionCount += processDocuments(documentPlan, prefs, forceNotify)

        if (forceNotify && attentionCount == 0) {
            NotificationSupport.postSystemCheck(
                applicationContext,
                "Проверка завершена: срочных ТО, ОСАГО и техосмотра нет."
            )
        }

        return Result.success()
    }

    private fun processMaintenance(
        item: JSONObject,
        prefs: android.content.SharedPreferences,
        forceNotify: Boolean
    ): Boolean {
        val type = item.optString("type").trim()
        if (type.isEmpty()) return false

        val nextKm = item.optLong("nextKm", Long.MAX_VALUE)
        val leftKm = item.optLong("leftKm", Long.MAX_VALUE)
        val nextDate = item.optString("nextDate").trim()
        val storedStatus = item.optString("status", "OK")
        val daysLeft = daysUntil(nextDate, item.optInt("leftDays", Int.MAX_VALUE))

        val critical = leftKm <= 0L || daysLeft <= 0 ||
                storedStatus == "ТРЕБУЕТСЯ ЗАМЕНА"
        val warning = !critical && (
                storedStatus == "СКОРО ТО" ||
                daysLeft in 1..14
        )

        val key = lastMaintenanceKey(type)

        if (!critical && !warning) {
            prefs.edit().remove(key).apply()
            return false
        }

        val category = when {
            critical -> "critical"
            leftKm in 1..500 && storedStatus == "СКОРО ТО" -> "km500"
            else -> "days14"
        }

        val fingerprint = listOf(
            type,
            category,
            nextKm.toString(),
            nextDate
        ).joinToString("|")

        if (!forceNotify && prefs.getString(key, "") == fingerprint) {
            return true
        }

        val message = when {
            critical && leftKm <= 0L ->
                "Пробег превышен на " + abs(leftKm) + " км. Требуется: " + type + "."
            critical && daysLeft <= 0 ->
                "Срок обслуживания наступил. Требуется: " + type + "."
            leftKm in 1..500 && storedStatus == "СКОРО ТО" ->
                "Через " + leftKm + " км рекомендуется: " + type + "."
            daysLeft in 1..14 ->
                "До обслуживания «" + type + "» осталось " + daysLeft + " дн."
            else ->
                "Приближается обслуживание: " + type + "."
        }

        showMaintenanceNotification(type, message, critical)
        prefs.edit().putString(key, fingerprint).apply()
        return true
    }

    private fun processDocuments(
        plan: JSONArray,
        prefs: android.content.SharedPreferences,
        forceNotify: Boolean
    ): Int {
        val activeTypes = linkedSetOf<String>()
        var attentionCount = 0

        for (i in 0 until plan.length()) {
            val item = plan.optJSONObject(i) ?: continue
            val type = item.optString("type").trim().lowercase(Locale.ROOT)
            val expiresDate = item.optString("expiresDate").trim()
            if (type !in setOf("osago", "to") || expiresDate.isBlank()) continue

            activeTypes.add(type)
            val daysLeft = daysUntil(expiresDate, Int.MAX_VALUE)
            val key = lastDocumentKey(type)

            if (daysLeft == Int.MAX_VALUE || daysLeft > 30) {
                prefs.edit().remove(key).apply()
                continue
            }

            attentionCount++

            val category = when {
                daysLeft < 0 -> "expired"
                daysLeft == 0 -> "today"
                daysLeft <= 1 -> "d1"
                daysLeft <= 7 -> "d7"
                daysLeft <= 14 -> "d14"
                else -> "d30"
            }
            val fingerprint = listOf(type, category, expiresDate).joinToString("|")

            if (!forceNotify && prefs.getString(key, "") == fingerprint) {
                continue
            }

            val title = if (type == "osago") "ОСАГО" else "Техосмотр"
            val message = when {
                daysLeft < 0 ->
                    "Срок документа «" + title + "» истёк " + abs(daysLeft) + " дн. назад."
                daysLeft == 0 ->
                    "Срок документа «" + title + "» истекает сегодня."
                daysLeft == 1 ->
                    "Срок документа «" + title + "» истекает завтра."
                else ->
                    "До окончания «" + title + "» осталось " + daysLeft + " дн."
            }

            showDocumentNotification(title, message, daysLeft <= 7)
            prefs.edit().putString(key, fingerprint).apply()
        }

        for (type in listOf("osago", "to")) {
            if (type !in activeTypes) {
                prefs.edit().remove(lastDocumentKey(type)).apply()
            }
        }

        return attentionCount
    }

    private fun daysUntil(iso: String, fallback: Int): Int {
        if (iso.isBlank()) return fallback
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
            }
            val due = format.parse(iso) ?: return fallback
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time
            val diff = due.time - today.time
            kotlin.math.ceil(diff / 86400000.0).toInt()
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showMaintenanceNotification(type: String, message: String, critical: Boolean) {
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(
                applicationContext,
                NotificationSupport.MAINTENANCE_CHANNEL_ID
            )
        } else {
            Notification.Builder(applicationContext)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AUTO ТО PRO • " + type)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(
                if (critical) Notification.PRIORITY_HIGH
                else Notification.PRIORITY_DEFAULT
            )
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.notify(1000 + abs(type.hashCode() % 100000), notification)
    }

    private fun showDocumentNotification(
        title: String,
        message: String,
        critical: Boolean
    ) {
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(
                applicationContext,
                NotificationSupport.DOCUMENT_CHANNEL_ID
            )
        } else {
            Notification.Builder(applicationContext)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AUTO ТО PRO • " + title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(
                if (critical) Notification.PRIORITY_HIGH
                else Notification.PRIORITY_DEFAULT
            )
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.notify(200000 + abs(title.hashCode() % 100000), notification)
    }

    private fun lastMaintenanceKey(type: String) = "last_notice_" + type.hashCode()
    private fun lastDocumentKey(type: String) = "last_doc_notice_" + type
}
