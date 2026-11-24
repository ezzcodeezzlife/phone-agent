package dev.phoneagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android speech-to-text with live partial results. Uses the phone's recognition service (on most phones the
 * Google app). Must be used from the main thread.
 */
class Voice(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onEnd: (error: String?) -> Unit,
) {
    private var rec: SpeechRecognizer? = null
    var active = false
        private set
    private var lastPartial = ""

    companion object {
        fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        fun available(ctx: Context) = SpeechRecognizer.isRecognitionAvailable(ctx)

        /** Languages offered in settings; "" = phone default. */
        val LANGUAGES = listOf("" to "Phone default", "en-US" to "English (US)", "en-GB" to "English (UK)", "de-DE" to "Deutsch",
            "es-ES" to "Español", "fr-FR" to "Français", "it-IT" to "Italiano", "nl-NL" to "Nederlands", "pt-BR" to "Português (Brasil)",
            "pl-PL" to "Polski", "tr-TR" to "Türkçe")
    }

    fun start(language: String) {
        if (active) return
        if (!hasPermission(ctx)) { onEnd("Microphone permission needed"); return }
        if (!available(ctx)) { onEnd("No speech recognition on this phone (it usually comes with the Google app)"); return }
        lastPartial = ""
        val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (e: Exception) { onEnd("Speech recognition failed: ${e.message}"); return }
        rec = r
        active = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: return
                if (t.isNotEmpty()) { lastPartial = t; onPartial(t) }
            }

            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().ifEmpty { lastPartial }
                finish()
                if (t.isNotEmpty()) { onFinal(t); onEnd(null) } else onEnd("Didn't catch that")
            }

            override fun onError(error: Int) {
                val partial = lastPartial
                finish()
                // Some recognizers end with "no match" although partial results came in; use them.
                if (partial.isNotEmpty() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    onFinal(partial); onEnd(null); return
                }
                onEnd(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection"
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone not available"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy"
                    else -> "Speech recognition error $error"
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        if (language.isNotEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        try { r.startListening(i) } catch (e: Exception) { finish(); onEnd("Speech recognition failed: ${e.message}") }
    }

    /** Stop listening and deliver what was heard so far. */
    fun stop() { rec?.stopListening() }

    fun cancel() {
        if (!active) return
        finish()
        onEnd("cancelled")
    }

    private fun finish() {
        active = false
        rec?.let { try { it.destroy() } catch (_: Exception) {} }
        rec = null
    }
}
