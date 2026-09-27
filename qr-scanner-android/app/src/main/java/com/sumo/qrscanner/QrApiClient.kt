package com.sumo.qrscanner

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

    fun postScan(baseUrl: String, qr: String): JSONObject {
        return postQr(baseUrl, "/scan", qr)
    }

    fun postSeeData(baseUrl: String, qr: String): JSONObject {
        return postQr(baseUrl, "/see-data", qr)
    }

    private fun postQr(baseUrl: String, path: String, qr: String): JSONObject {
        val body = JSONObject().put("qr", qr)
        return request("${AppSettings.apiRoot(baseUrl)}$path", body)
    }

    private fun request(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
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
}
