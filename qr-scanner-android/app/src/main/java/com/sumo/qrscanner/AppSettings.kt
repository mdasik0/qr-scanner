package com.sumo.qrscanner

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, normalizeBaseUrl(value)).apply()
        }

    val hasSetup: Boolean
        get() = baseUrl.isNotEmpty()

    companion object {
        private const val PREFS = "qr_scanner_settings"
        private const val KEY_BASE_URL = "base_url"

        fun normalizeBaseUrl(raw: String): String {
            var value = raw.trim().trimEnd('/')
            value = value.replace(Regex("/api/v1$", RegexOption.IGNORE_CASE), "")
            return value.trimEnd('/')
        }

        fun isValidHttpUrl(raw: String): Boolean {
            val value = normalizeBaseUrl(raw)
            return value.startsWith("http://") || value.startsWith("https://")
        }

        fun apiRoot(baseUrl: String): String = normalizeBaseUrl(baseUrl)
    }
}
