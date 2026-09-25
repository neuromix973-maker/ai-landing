package com.jyotisha.darpana

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

class AutoToBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        const val BLUETOOTH_PERMISSION_REQUEST = 9133
    }

    private val obdManager = ObdManager(activity.applicationContext) { event, payload ->
        emitObdEvent(event, payload)
    }

    init {
        MaintenanceScheduler.ensureScheduled(activity.applicationContext)
    }

    @JavascriptInterface
    fun scheduleMaintenanceReminders(planJson: String) {
        MaintenanceScheduler.updatePlan(activity.applicationContext, planJson)
    }

    @JavascriptInterface
    fun scheduleDocumentReminders(planJson: String) {
        MaintenanceScheduler.updateDocumentPlan(activity.applicationContext, planJson)
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

    @JavascriptInterface
    fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31 || hasBluetoothPermission()) {
            emitObdEvent("permission", JSONObject().put("granted", true))
            return
        }

        activity.runOnUiThread {
            try {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    BLUETOOTH_PERMISSION_REQUEST
                )
            } catch (_: Throwable) {
                emitObdEvent(
                    "error",
                    JSONObject()
                        .put("code", "PERMISSION_REQUEST_FAILED")
                        .put("message", "Не удалось запросить разрешение Bluetooth")
                )
            }
        }
    }

    @JavascriptInterface
    fun listPairedObdDevices(): String {
        if (!hasBluetoothPermission()) {
            return JSONObject()
                .put("ok", false)
                .put("permissionRequired", true)
                .put("devices", org.json.JSONArray())
                .toString()
        }
        return obdManager.listPairedDevices().toString()
    }

    @JavascriptInterface
    fun openBluetoothSettings() {
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Throwable) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Throwable) {
                }
            }
        }
    }

    @JavascriptInterface
    fun connectObd(address: String) {
        if (!hasBluetoothPermission()) {
            emitObdEvent(
                "permission_required",
                JSONObject().put("message", "Разрешите доступ к Bluetooth")
            )
            requestBluetoothPermission()
            return
        }
        obdManager.connect(address)
    }

    @JavascriptInterface
    fun disconnectObd() {
        obdManager.disconnect()
    }

    @JavascriptInterface
    fun readObdDtc() {
        if (!hasBluetoothPermission()) {
            emitObdEvent(
                "permission_required",
                JSONObject().put("message", "Разрешите доступ к Bluetooth")
            )
            requestBluetoothPermission()
            return
        }
        obdManager.readDtc()
    }

    @JavascriptInterface
    fun getObdStatus(): String = obdManager.statusJson().toString()

    @JavascriptInterface
    fun exportHistoryPdf(payloadJson: String): Boolean {
        return PdfExportManager(activity).exportAndShare(payloadJson)
    }

    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            emitObdEvent("permission", JSONObject().put("granted", granted))
        }
    }

    fun destroy() {
        obdManager.close()
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
                activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun emitObdEvent(event: String, payload: JSONObject) {
        val js = "window.onNativeObdEvent && window.onNativeObdEvent(" +
                JSONObject.quote(event) + "," + payload.toString() + ");"
        webView.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (_: Throwable) {
            }
        }
    }
}
