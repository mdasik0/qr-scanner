package com.sumo.qrscanner

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, normalizeBaseUrl(value)).apply()
        }

    var identifier: String
        get() = prefs.getString(KEY_IDENTIFIER, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_IDENTIFIER, value.trim()).apply()
        }

    var accessToken: String
        get() = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
        }

    val hasSetup: Boolean
        get() = baseUrl.isNotEmpty() && accessToken.isNotEmpty()

    fun clearSession() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val PREFS = "qr_scanner_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_IDENTIFIER = "identifier"
        private const val KEY_TOKEN = "access_token"

        fun normalizeBaseUrl(raw: String): String {
            var value = raw.trim().trimEnd('/')
            value = value.replace(Regex("/api/v1$", RegexOption.IGNORE_CASE), "")
            value = value.trimEnd('/')
            value = value.replace(Regex(":3011$"), ":5011")
            return value
        }

        fun isValidHttpUrl(raw: String): Boolean {
            val value = normalizeBaseUrl(raw)
            return value.startsWith("http://") || value.startsWith("https://")
        }

        fun apiRoot(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/api/v1"
    }
}
