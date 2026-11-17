package dev.phoneagent

import org.json.JSONObject

/** Responses API on the user's ChatGPT plan (store=false, stream=true are required on this route). */
object OpenAI {
    private const val BASE = "https://api.openai.com/v1"

    data class Model(val slug: String, val name: String)

    class ApiError(val status: Int, val code: String?, val param: String?, msg: String) : Exception(msg)

    fun listModels(): List<Model> {
        val r = Net.get("$BASE/models", mapOf("Authorization" to "Bearer ${Auth.accessToken()}"))
        if (r.code != 200) throw IllegalStateException("Modelle ${r.code}: ${r.body.take(300)}")
        val j = JSONObject(r.body)
        val out = ArrayList<Model>()
        j.optJSONArray("models")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optString("visibility", "list") == "list") {
                    out += Model(m.getString("slug"), m.optString("display_name", m.getString("slug")))
                }
            }
        }
        if (out.isEmpty()) j.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) arr.getJSONObject(i).optString("id").takeIf { it.isNotEmpty() }?.let { out += Model(it, it) }
        }
        return out
    }

    /** Streams one Responses request and returns the final `response` object from response.completed. */
    fun respond(body: JSONObject): JSONObject {
        body.put("store", false)
        body.put("stream", true)
        var completed: JSONObject? = null
        var failure: String? = null
        var failCode: String? = null
        val r = Net.postSse("$BASE/responses", mapOf("Authorization" to "Bearer ${Auth.accessToken()}"), body.toString()) { data ->
            val ev = try { JSONObject(data) } catch (_: Exception) { null }
            when (ev?.optString("type")) {
                "response.completed" -> completed = ev.getJSONObject("response")
                "response.failed", "response.incomplete" -> {
                    val resp = ev.optJSONObject("response")
                    val err = resp?.optJSONObject("error")
                    failCode = err?.optString("code")
                    failure = ev.optString("type") + ": " + (err?.optString("message") ?: resp?.optJSONObject("incomplete_details")?.toString() ?: "")
                }
                "error" -> {
                    val err = ev.optJSONObject("error") ?: ev
                    failCode = err.optString("code")
                    failure = "error: " + err.optString("message").ifEmpty { ev.toString().take(300) }
                }
            }
        }
        if (r.code >= 400) {
            var code: String? = null
            var param: String? = null
            var msg = r.body.take(600)
            try {
                val j = JSONObject(r.body)
                val err = j.optJSONObject("error")
                if (err != null) {
                    code = err.optString("code").ifEmpty { null }
                    param = err.optString("param").ifEmpty { null }
                    msg = err.optString("message", msg)
                } else if (j.has("detail")) {
                    msg = j.optString("detail")
                }
            } catch (_: Exception) {}
            throw ApiError(r.code, code, param, "HTTP ${r.code}${code?.let { " $it" } ?: ""}${param?.let { " (param $it)" } ?: ""}: $msg")
        }
        completed?.let { return it }
        throw ApiError(0, failCode, null, failure ?: "Stream ohne response.completed beendet")
    }
}
