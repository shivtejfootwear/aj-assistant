package com.shivtej.aj

import java.net.HttpURLConnection
import java.net.URL

/** छोटा सा internet helper (कोई अलग library नहीं) */
object Http {
    data class Res(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        readTimeoutMs: Int = 30000,
    ): Res {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Res(code, text)
        } finally {
            c.disconnect()
        }
    }
}
