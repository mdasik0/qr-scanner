package com.sumo.qrscanner

import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class QrApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun postScan(baseUrl: String, qr: String, accessToken: String = ""): JSONObject {
        return postQr(baseUrl, "/scan", qr, accessToken)
    }

    fun postSeeData(baseUrl: String, qr: String, accessToken: String = ""): JSONObject {
        return postQr(baseUrl, "/see-data", qr, accessToken)
    }

    private fun postQr(
        baseUrl: String,
        path: String,
        qr: String,
        accessToken: String,
    ): JSONObject {
        val body = JSONObject().put("qr", qr)
        return request("${AppSettings.apiRoot(baseUrl)}$path", body, accessToken)
    }

    fun sendFeedback(fromEmail: String, message: String, appLabel: String = "") {
        val letter = buildString {
            append(message.trim())
            append("\n\n—\n")
            append("From: ")
            append(fromEmail)
            if (appLabel.isNotBlank()) {
                append('\n')
                append(appLabel)
            }
        }
        val form = FormBody.Builder()
            .add("_replyto", fromEmail)
            .add("_subject", "QR Scan report")
            .add("message", letter)
            .add("_template", "basic")
            .add("_captcha", "false")
            .add("_honey", "")
            .build()
        var lastError: IOException? = null
        for (url in FEEDBACK_URLS) {
            try {
                postFeedback(url, form)
                return
            } catch (e: IOException) {
                lastError = e
                Log.w("QrApiClient", "Feedback post failed for $url", e)
            }
        }
        throw lastError ?: IOException("Could not send")
    }

    private fun postFeedback(url: String, form: FormBody) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/html")
            .header("Origin", "https://github.com")
            .header("Referer", "https://github.com/mdasik0/qr-scanner")
            .header("User-Agent", BROWSER_UA)
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code !in 300..399) {
                throw IOException("Could not send (${response.code})")
            }
            val trimmed = text.trim()
            if (trimmed.startsWith("{")) {
                val json = try {
                    JSONObject(trimmed)
                } catch (_: Exception) {
                    return
                }
                if (!json.has("success")) return
                val ok = json.optBoolean("success") ||
                    json.optString("success").equals("true", ignoreCase = true)
                if (ok) return
                throw IOException(json.optString("message").ifBlank { "Could not send" })
            }
        }
    }

    private fun request(url: String, body: JSONObject, accessToken: String): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
        val token = accessToken.trim()
        if (token.isNotEmpty()) {
            val header =
                if (token.startsWith("Bearer ", ignoreCase = true)) token
                else "Bearer $token"
            builder.addHeader("Authorization", header)
        }
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (looksLikeHtml(text)) {
                throw IOException("That URL is the website, not the API.")
            }
            if (!response.isSuccessful) {
                throw IOException(readError(text, response.code))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
    }

    private fun readError(text: String, code: Int): String {
        return try {
            JSONObject(text).optString("message").ifBlank { "HTTP $code" }
        } catch (_: Exception) {
            "HTTP $code"
        }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val FEEDBACK_URLS = listOf(
            "https://formsubmit.co/ajax/124aee378c09d2151559c2da77eea1b2",
            "https://formsubmit.co/124aee378c09d2151559c2da77eea1b2",
        )
    }
}
