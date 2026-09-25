package com.jyotisha.darpana

import android.content.Context
import android.os.Build

object CrashReporter {
    private const val PREFS = "auto_to_pro_native"
    private const val KEY_ENABLED = "crash_reports_enabled"

    @JvmStatic
    fun applyStoredPreference(context: Context) {
        setCollectionEnabled(context, isEnabled(context))
    }

    @JvmStatic
    fun setCollectionEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()

        val instance = instanceOrNull() ?: return
        try {
            instance.javaClass
                .getMethod("setCrashlyticsCollectionEnabled", Boolean::class.javaPrimitiveType)
                .invoke(instance, enabled)
            if (enabled) {
                setKey(instance, "app_component", "AUTO_TO_PRO")
                setKey(instance, "android_sdk", Build.VERSION.SDK_INT.toString())
            }
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    @JvmStatic
    fun isAvailable(): Boolean = instanceOrNull() != null

    @JvmStatic
    fun recordNonFatal(tag: String, message: String): Boolean {
        val instance = instanceOrNull() ?: return false
        return try {
            try {
                instance.javaClass.getMethod("log", String::class.java)
                    .invoke(instance, "nonfatal:" + sanitize(tag))
            } catch (_: Throwable) {
            }
            val error = IllegalStateException(sanitize(message))
            instance.javaClass.getMethod("recordException", Throwable::class.java)
                .invoke(instance, error)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun instanceOrNull(): Any? {
        return try {
            val clazz = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            clazz.getMethod("getInstance").invoke(null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun setKey(instance: Any, key: String, value: String) {
        try {
            instance.javaClass.getMethod(
                "setCustomKey",
                String::class.java,
                String::class.java
            ).invoke(instance, key, value)
        } catch (_: Throwable) {
        }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").take(300)
}
