package dev.phoneagent

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpResult(val code: Int, val body: String)

/** Minimal HTTP helpers on HttpURLConnection (no third-party client). */
object Net {
    private fun open(url: String, method: String, headers: Map<String, String>, readTimeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = readTimeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun read(c: HttpURLConnection): HttpResult {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return HttpResult(code, text)
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult =
        read(open(url, "GET", headers, 60_000))

    fun postForm(url: String, form: Map<String, String>): HttpResult {
        val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val c = open(url, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"), 60_000)
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        return read(c)
    }

    /**
     * POSTs JSON and reads a server-sent event stream, calling [onData] with each event's data payload.
     * On an HTTP error the returned result carries the status and the error body.
     */
    fun postSse(url: String, headers: Map<String, String>, json: String, onData: (String) -> Unit): HttpResult {
        val c = open(url, "POST", headers + mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream"), 180_000)
        c.doOutput = true
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        if (code >= 400) return read(c)
        BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
            val data = StringBuilder()
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) { onData(data.toString()); data.setLength(0) }
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
            if (data.isNotEmpty()) onData(data.toString())
        }
        c.disconnect()
        return HttpResult(code, "")
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Query-string encoding (%20 instead of + for spaces). */
    fun encQuery(s: String): String = enc(s).replace("+", "%20")
}
