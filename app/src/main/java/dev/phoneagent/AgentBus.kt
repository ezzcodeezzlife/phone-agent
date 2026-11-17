package dev.phoneagent

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared log + status between the accessibility service (agent) and the activity (UI). */
object AgentBus {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var statusListener: (() -> Unit)? = null
    @Volatile var runFile: File? = null
    @Volatile var lastAnswer: String? = null

    fun log(msg: String) {
        val line = synchronized(fmt) { fmt.format(Date()) } + " " + msg
        Log.i("PhoneAgent", msg)
        synchronized(lines) {
            lines.add(line)
            if (lines.size > 600) lines.removeAt(0)
        }
        runFile?.let { f -> try { f.appendText(line + "\n") } catch (_: Exception) {} }
        listener?.invoke(line)
    }

    fun all(): List<String> = synchronized(lines) { ArrayList(lines) }

    fun changed() { statusListener?.invoke() }
}
