package com.jyotisha.darpana

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONObject

class AutoToBridge(private val activity: Activity) {
    init {
        MaintenanceScheduler.ensureScheduled(activity.applicationContext)
    }

    @JavascriptInterface
    fun scheduleMaintenanceReminders(planJson: String) {
        MaintenanceScheduler.updatePlan(activity.applicationContext, planJson)
    }

    @JavascriptInterface
    fun setMaintenanceRemindersEnabled(enabled: Boolean) {
        MaintenanceScheduler.setEnabled(activity.applicationContext, enabled)
        if (enabled) requestNotificationPermission()
    }

    @JavascriptInterface
    fun runMaintenanceCheckNow() {
        MaintenanceScheduler.runNow(activity.applicationContext)
    }

    @JavascriptInterface
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) return

        activity.runOnUiThread {
            try {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    9132
                )
            } catch (_: Throwable) {
            }
        }
    }

    @JavascriptInterface
    fun getReminderModuleStatus(): String {
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val permission = when {
            Build.VERSION.SDK_INT < 33 -> "not_required"
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED -> "granted"
            else -> "denied"
        }

        return JSONObject()
            .put("supported", true)
            .put("enabled", MaintenanceScheduler.isEnabled(activity.applicationContext))
            .put("permission", permission)
            .put("notificationsEnabled", nm.areNotificationsEnabled())
            .toString()
    }
}
