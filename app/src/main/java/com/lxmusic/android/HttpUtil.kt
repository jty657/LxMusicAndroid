package com.lxmusic.android

import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object HttpUtil {
    data class Response(val status: Int, val statusMessage: String, val headers: Map<String,String>, val body: String, val raw: ByteArray)

    fun request(url: String, method: String = "GET", headers: Map<String,String> = emptyMap(), body: String? = null, timeoutMs: Int = 20_000): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            doInput = true
            headers.forEach { (k,v) -> setRequestProperty(k,v) }
            if (body != null && method.uppercase() != "GET") {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            val raw = stream?.readBytes() ?: ByteArray(0)
            val hs = conn.headerFields.filterKeys { it != null }.mapValues { it.value?.firstOrNull().orEmpty() }.mapKeys { it.key!! }
            Response(status, conn.responseMessage.orEmpty(), hs, raw.toString(Charsets.UTF_8), raw)
        } finally { conn.disconnect() }
    }

    fun get(url: String, headers: Map<String,String> = emptyMap()): Response = request(url, "GET", headers)

    fun post(url: String, body: String, headers: Map<String,String> = emptyMap()): Response = request(url, "POST", headers, body)

    fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

    fun jsonBody(form: Map<String,Any?>): String = JSONObject(form).toString()
}
