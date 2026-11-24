package dev.phoneagent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** App settings shared by the activity and the accessibility service. */
object Prefs {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var model: String?
        get() = p.getString("model", null)
        set(v) = p.edit().putString("model", v).apply()
    var effort: String
        get() = p.getString("effort", "low") ?: "low"
        set(v) = p.edit().putString("effort", v).apply()
    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) = p.edit().putString("mode", v.key).apply()
    /** Ask before sending, posting, paying, deleting. */
    var askFirst: Boolean
        get() = p.getBoolean("ask_first", true)
        set(v) = p.edit().putBoolean("ask_first", v).apply()
    /** Hold the agent while the user touches the screen. */
    var touchWait: Boolean
        get() = p.getBoolean("touch_wait", true)
        set(v) = p.edit().putBoolean("touch_wait", v).apply()
    var voiceLang: String
        get() = p.getString("voice_lang", "") ?: ""
        set(v) = p.edit().putString("voice_lang", v).apply()
    var task: String
        get() = p.getString("task", "") ?: ""
        set(v) = p.edit().putString("task", v).apply()

    fun models(): List<OpenAI.Model> = try {
        val arr = JSONArray(p.getString("models", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> OpenAI.Model(o.getString("slug"), o.getString("name")) } }
    } catch (_: Exception) { emptyList() }

    fun saveModels(list: List<OpenAI.Model>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("slug", it.slug).put("name", it.name)) }
        p.edit().putString("models", arr.toString()).apply()
    }

    fun modelName(slug: String?): String = slug?.let { s -> models().firstOrNull { it.slug == s }?.name ?: s } ?: "No model"

    /** The selected model, or the first known one. */
    fun currentModel(): String? = model ?: models().firstOrNull()?.slug
}

/** One finished run, read from its summary.json. */
class RunInfo(
    val dir: File,
    val task: String,
    val startedAt: Long,
    val seconds: Double,
    val llmSeconds: Double,
    val steps: Int,
    val inTok: Long,
    val outTok: Long,
    val status: String,
    val answer: String,
    val model: String,
    val mode: String,
    val effort: String,
    val shots: Int,
    val json: JSONObject,
) {
    val tokens get() = inTok + outTok
    val done get() = status == "done"
}

/** One line of a run's timeline. kind: action, user, question, confirm, note. */
class RunEvent(val step: Int, val kind: String, val text: String, val sub: String, val ok: Boolean)

/** Run history on disk: files/runs/<yyyyMMdd-HHmmss>-<mode>/ with summary.json, log.txt and step screenshots. */
object Runs {
    private val cache = HashMap<String, RunInfo>()
    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private const val KEEP_RUNS = 500
    private const val KEEP_SCREENSHOTS = 20

    fun root(ctx: Context): File = (ctx.getExternalFilesDir("runs") ?: File(ctx.filesDir, "runs")).apply { mkdirs() }

    fun list(ctx: Context): List<RunInfo> {
        val dirs = root(ctx).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val out = ArrayList<RunInfo>()
        synchronized(cache) {
            for (d in dirs) {
                val r = cache[d.name] ?: load(d)?.also { cache[d.name] = it } ?: continue
                out += r
            }
        }
        return out.sortedByDescending { it.startedAt }
    }

    fun load(dir: File): RunInfo? {
        val f = File(dir, "summary.json")
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            val success = j.optBoolean("success")
            val answer = j.optString("answer")
            val status = j.optString("status").ifEmpty {
                when {
                    success -> "done"
                    answer.startsWith("Stopped.") || answer.startsWith("Stopped by") -> "stopped"
                    else -> "failed"
                }
            }
            val started = j.optLong("started_at", 0L).takeIf { it > 0 }
                ?: try { stampFmt.parse(dir.name.take(15))?.time } catch (_: Exception) { null }
                ?: dir.lastModified()
            RunInfo(dir, j.optString("task"), started, j.optDouble("seconds", 0.0), j.optDouble("llm_seconds", 0.0), j.optInt("steps"),
                j.optLong("input_tokens"), j.optLong("output_tokens"), status, answer, j.optString("model"), j.optString("mode"),
                j.optString("effort"), j.optInt("screenshots_sent"), j)
        } catch (_: Exception) { null }
    }

    /** What happened, step by step. New runs store it in summary.json; older ones are read from log.txt. */
    fun timeline(r: RunInfo): List<RunEvent> {
        r.json.optJSONArray("events")?.let { arr ->
            return (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                RunEvent(e.optInt("step"), e.optString("kind", "action"), e.optString("text"), e.optString("sub"), e.optBoolean("ok", true))
            }
        }
        val log = File(r.dir, "log.txt")
        if (!log.exists()) return emptyList()
        val out = ArrayList<RunEvent>()
        var step = 0
        for (line in log.readLines()) {
            val msg = line.substringAfter(' ', line)
            when {
                msg.startsWith("— Step ") || msg.startsWith("— Schritt ") ->
                    step = msg.substringAfter("— ").substringAfter(' ').substringBefore(' ').toIntOrNull() ?: step
                msg.startsWith("➜ ") -> {
                    val body = msg.removePrefix("➜ ")
                    val res = body.substringAfterLast(" → ", "")
                    val act = body.substringBeforeLast(" → ")
                    val call = act.substringBefore(" – ")
                    if (call.startsWith("finish(")) continue
                    out += RunEvent(step, "action", readable(call, res), act.substringAfter(" – ", ""),
                        !(res.startsWith("error") || res.contains("failed") || res.contains("fehlgeschlagen")))
                }
            }
        }
        return out
    }

    /** "tap(element=9 «No thanks»)" → "Tapped “No thanks”" for runs logged before readable events existed. */
    private fun readable(call: String, res: String): String {
        val name = call.substringBefore('(')
        val args = call.substringAfter('(').removeSuffix(")")
        fun arg(k: String) = Regex("""\b$k=("([^"]*)"|[^,)]+)""").find(args)?.let { m -> m.groups[2]?.value ?: m.groups[1]?.value }
        val label = Regex("«([^»]*)»").find(args)?.groupValues?.get(1)
        val target = if (label != null) "“$label”" else "the screen"
        return when (name) {
            "tap" -> "Tapped $target"
            "long_press" -> "Long-pressed $target"
            "type_text" -> "Typed “${arg("text") ?: ""}”"
            "swipe" -> "Swiped"
            "scroll" -> "Scrolled ${arg("direction") ?: "down"}"
            "press_key" -> when (arg("key")) {
                "back" -> "Went back"; "home" -> "Went to the home screen"; "enter" -> "Pressed Enter"
                "notifications" -> "Opened notifications"; "recents" -> "Opened recent apps"; "quick_settings" -> "Opened quick settings"
                else -> "Pressed ${arg("key") ?: "a key"}"
            }
            "open_app" -> if (res.startsWith("opened ") || res.startsWith("geöffnet")) "Opened ${arg("name") ?: "an app"}" else "Couldn't find the app “${arg("name") ?: ""}”"
            "wait" -> "Waited ${arg("seconds") ?: "a moment"} s"
            "look" -> "Took a look at the screen"
            else -> call
        }
    }

    fun lastScreen(r: RunInfo): File? = r.dir.listFiles { f -> f.name.startsWith("step-") && f.name.endsWith(".jpg") }?.maxByOrNull { it.name }

    fun delete(r: RunInfo) {
        synchronized(cache) { cache.remove(r.dir.name) }
        r.dir.deleteRecursively()
    }

    fun clear(ctx: Context) {
        synchronized(cache) { cache.clear() }
        root(ctx).listFiles()?.forEach { it.deleteRecursively() }
    }

    fun sizeBytes(ctx: Context): Long = root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Keeps the history small: screenshots and trees only for the newest runs, at most [KEEP_RUNS] runs. */
    fun prune(ctx: Context) {
        val dirs = root(ctx).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return
        dirs.drop(KEEP_RUNS).forEach { d -> synchronized(cache) { cache.remove(d.name) }; d.deleteRecursively() }
        dirs.take(KEEP_RUNS).drop(KEEP_SCREENSHOTS).forEach { d ->
            val shots = d.listFiles { f -> f.name.startsWith("step-") }?.sortedBy { it.name } ?: return@forEach
            val keep = shots.lastOrNull { it.name.endsWith(".jpg") }
            shots.filter { it != keep }.forEach { it.delete() }
        }
    }
}
