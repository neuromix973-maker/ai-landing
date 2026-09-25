package com.jyotisha.darpana

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

object NotificationSupport {
    const val MAINTENANCE_CHANNEL_ID = "maintenance_v2"
    const val DOCUMENT_CHANNEL_ID = "documents_v2"
    const val SYSTEM_CHANNEL_ID = "system_check_v2"

    @JvmStatic
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                MAINTENANCE_CHANNEL_ID,
                "Техническое обслуживание",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Напоминания AUTO ТО PRO о сроках обслуживания автомобиля"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                DOCUMENT_CHANNEL_ID,
                "Документы автомобиля",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Напоминания AUTO ТО PRO об ОСАГО и техосмотре"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                SYSTEM_CHANNEL_ID,
                "Проверка уведомлений",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Служебная проверка системных уведомлений AUTO ТО PRO"
            }
        )
    }

    @JvmStatic
    fun permissionGranted(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun appNotificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }

    @JvmStatic
    fun statusJson(context: Context): JSONObject {
        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun channelEnabled(id: String): Boolean {
            if (Build.VERSION.SDK_INT < 26) return true
            val ch = manager.getNotificationChannel(id) ?: return false
            return ch.importance != NotificationManager.IMPORTANCE_NONE
        }

        return JSONObject()
            .put("permission", if (permissionGranted(context)) {
                if (Build.VERSION.SDK_INT < 33) "not_required" else "granted"
            } else "denied")
            .put("notificationsEnabled", appNotificationsEnabled(context))
            .put("maintenanceChannelEnabled", channelEnabled(MAINTENANCE_CHANNEL_ID))
            .put("documentsChannelEnabled", channelEnabled(DOCUMENT_CHANNEL_ID))
            .put("systemChannelEnabled", channelEnabled(SYSTEM_CHANNEL_ID))
    }

    @JvmStatic
    fun openSettings(activity: Activity) {
        activity.runOnUiThread {
            val intent = try {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                }
            } catch (_: Throwable) {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.packageName)
                )
            }
            try {
                activity.startActivity(intent)
            } catch (_: Throwable) {
                try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.packageName)
                        )
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }

    @JvmStatic
    fun postSystemCheck(context: Context, message: String) {
        ensureChannels(context)
        if (!permissionGranted(context) || !appNotificationsEnabled(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            991,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, SYSTEM_CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AUTO ТО PRO")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(399001, notification)
    }
}
