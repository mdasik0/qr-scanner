package com.sumo.qrscanner

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class QrApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun login(baseUrl: String, identifier: String, password: String): String {
        val body = JSONObject()
            .put("identifier", identifier.trim())
            .put("password", password)
        val json = request(
            url = "${AppSettings.apiRoot(baseUrl)}/login",
            token = null,
            method = "POST",
            body = body,
        )
        val token = json.optJSONObject("data")?.optString("accessToken").orEmpty()
        if (token.isBlank()) throw IOException(json.optString("message").ifBlank { "Login failed" })
        return token
    }

    fun postScan(
        baseUrl: String,
        token: String,
        rawPayload: String,
        deviceId: String,
        deviceLabel: String?,
    ) {
        val body = JSONObject()
            .put("raw_payload", rawPayload)
            .put("device_id", deviceId)
            .put("device_label", deviceLabel)
            .put("format", "QR_CODE")
            .put(
                "meta",
                JSONObject()
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("sdk", android.os.Build.VERSION.SDK_INT),
            )
        request(
            url = "${AppSettings.apiRoot(baseUrl)}/qr-scans",
            token = token,
            method = "POST",
            body = body,
        )
    }

    fun getJson(
        baseUrl: String,
        token: String,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JSONObject {
        val builder = "${AppSettings.apiRoot(baseUrl)}$path".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return request(url = builder.build().toString(), token = token, method = "GET", body = null)
    }

    private fun request(
        url: String,
        token: String?,
        method: String,
        body: JSONObject?,
    ): JSONObject {
        val builder = Request.Builder().url(url)
        if (!token.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $token")
        }
        if (method == "POST") {
            builder.post((body ?: JSONObject()).toString().toRequestBody(jsonMedia))
            builder.addHeader("Content-Type", "application/json")
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (looksLikeHtml(text)) {
                throw IOException("That URL is the website, not the API. Use the backend host on port 5011.")
            }
            if (!response.isSuccessful) {
                throw IOException(readError(text, response.code))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    fun dataObject(json: JSONObject): JSONObject? {
        val data = json.opt("data")
        return data as? JSONObject
    }

    fun dataArray(json: JSONObject): JSONArray {
        return when (val data = json.opt("data")) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("lines") ?: JSONArray()
            else -> JSONArray()
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
