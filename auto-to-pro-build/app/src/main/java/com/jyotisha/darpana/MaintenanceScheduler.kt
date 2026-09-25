package com.jyotisha.darpana

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object MaintenanceScheduler {
    const val PREFS = "auto_to_pro_native"
    const val KEY_PLAN = "maintenance_plan"
    const val KEY_DOCUMENT_PLAN = "document_plan"
    const val KEY_ENABLED = "reminders_enabled"
    private const val UNIQUE_DAILY = "auto_to_pro_maintenance_daily"
    private const val UNIQUE_NOW = "auto_to_pro_maintenance_now"

    @JvmStatic
    fun updatePlan(context: Context, json: String) {
        prefs(context).edit().putString(KEY_PLAN, json).apply()
        if (isEnabled(context)) scheduleDaily(context) else cancel(context)
    }

    @JvmStatic
    fun updateDocumentPlan(context: Context, json: String) {
        prefs(context).edit().putString(KEY_DOCUMENT_PLAN, json).apply()
        if (isEnabled(context)) scheduleDaily(context) else cancel(context)
    }

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            scheduleDaily(context)
        } else {
            cancel(context)
            clearNotificationFingerprints(context)
        }
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    @JvmStatic
    fun ensureScheduled(context: Context) {
        if (isEnabled(context)) scheduleDaily(context)
    }

    @JvmStatic
    fun scheduleDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(
            24, TimeUnit.HOURS,
            1, TimeUnit.HOURS
        )
            .setInitialDelay(delayUntilNextNineAm(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_DAILY,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    @JvmStatic
    fun runNow(context: Context) {
        if (!isEnabled(context)) return
        val request = OneTimeWorkRequestBuilder<MaintenanceWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_NOW,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    @JvmStatic
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_DAILY)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NOW)
    }

    private fun delayUntilNextNineAm(): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clearNotificationFingerprints(context: Context) {
        val p = prefs(context)
        val editor = p.edit()
        for (key in p.all.keys) {
            if (key.startsWith("last_notice_") || key.startsWith("last_doc_notice_")) {
                editor.remove(key)
            }
        }
        editor.apply()
    }
}
