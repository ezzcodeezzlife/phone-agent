package dev.phoneagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodManager
import java.util.Locale

/**
 * Android speech-to-text with live partial results. Uses the phone's recognition service (on most phones the
 * Google app or "Speech Services by Google"). Must be used from the main thread.
 *
 * "Automatic" language: the candidates are the phone's and the keyboard's languages. It starts in the language it
 * heard last time (or the language the user types tasks in). On Android 14+ the on-device recognizer detects the
 * spoken language; if the user spoke another candidate language, the transcript is dropped, it listens again in
 * that language ("say it again"), and starts in it from then on.
 */
class Voice(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onEnd: (error: String?) -> Unit,
    /** It switched language and listens again; the argument is the language's name. */
    private val onRetry: (String) -> Unit = {},
) {
    private var rec: SpeechRecognizer? = null
    var active = false
        private set
    private var lastPartial = ""
    private var detected: String? = null
    private var switched = false
    private var setting = ""
    private var plain = false
    private var heardSpeech = false
    private var detectionUsed = false
    /** Language for a second attempt after detection. */
    private var forced: String? = null
    /** Language this attempt listens in, e.g. "de-DE". */
    var language = ""
        private set

    /** Debug builds only: recognize audio (16-bit mono PCM) from this source instead of the microphone. */
    var testAudio: (() -> Pair<ParcelFileDescriptor, Int>)? = null
    var testLanguages: List<String>? = null

    companion object {
        fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        fun available(ctx: Context) = SpeechRecognizer.isRecognitionAvailable(ctx)

        /** Languages offered in settings; "" = automatic. */
        val LANGUAGES = listOf("" to "Automatic", "en-US" to "English (US)", "en-GB" to "English (UK)", "de-DE" to "Deutsch",
            "es-ES" to "Español", "fr-FR" to "Français", "it-IT" to "Italiano", "nl-NL" to "Nederlands", "pt-BR" to "Português (Brasil)",
            "pl-PL" to "Polski", "tr-TR" to "Türkçe")

        private val REGION = mapOf("en" to "US", "de" to "DE", "es" to "ES", "fr" to "FR", "it" to "IT", "nl" to "NL", "pt" to "BR",
            "pl" to "PL", "tr" to "TR", "ja" to "JP", "ko" to "KR", "zh" to "CN", "ru" to "RU", "sv" to "SE", "da" to "DK", "nb" to "NO",
            "fi" to "FI", "cs" to "CZ", "uk" to "UA", "ar" to "SA", "hi" to "IN")

        /** Common short words, to guess the language of typed tasks. */
        private val STOPWORDS = mapOf(
            "en" to setOf("the", "and", "is", "to", "my", "me", "please", "open", "set", "turn", "what", "for", "on", "off", "with", "how", "send", "find"),
            "de" to setOf("und", "die", "der", "das", "ist", "ich", "mir", "mach", "bitte", "auf", "mit", "ein", "eine", "nicht", "wie", "was", "für", "öffne", "stell", "zeig", "schick", "aus", "an"),
            "es" to setOf("el", "la", "y", "de", "que", "por", "favor", "abre", "pon", "los", "las", "una", "para"),
            "fr" to setOf("le", "la", "et", "les", "des", "est", "ouvre", "mets", "pour", "une", "dans", "du"),
            "it" to setOf("il", "la", "e", "di", "che", "per", "apri", "metti", "una", "gli", "della"),
            "nl" to setOf("de", "het", "en", "een", "is", "zet", "maak", "voor", "mijn", "niet"),
        )

        private fun lang(tag: String) = tag.substringBefore('-')

        /** "de" / "de_DE" / "de-DE" → "de-DE"; null for unusable tags. */
        private fun normalize(tag: String?): String? {
            val t = tag?.trim()?.replace('_', '-') ?: return null
            if (t.isEmpty() || t == "und" || t.startsWith("zz")) return null
            val l = Locale.forLanguageTag(t)
            val lang = l.language.ifEmpty { return null }
            val region = l.country.ifEmpty { REGION[lang] ?: "" }
            return if (region.isEmpty()) lang else "$lang-$region"
        }

        /** The user's languages: phone languages first, then keyboard languages. At most 4, one per language. */
        fun autoLanguages(ctx: Context): List<String> {
            val out = LinkedHashMap<String, String>()
            fun add(tag: String?) { normalize(tag)?.let { out.putIfAbsent(lang(it), it) } }
            val locales = Resources.getSystem().configuration.locales
            for (i in 0 until locales.size()) add(locales[i].toLanguageTag())
            try {
                val imm = ctx.getSystemService(InputMethodManager::class.java)
                for (imi in imm.enabledInputMethodList) {
                    for (st in imm.getEnabledInputMethodSubtypeList(imi, true)) {
                        if (st.mode.isNotEmpty() && st.mode != "keyboard") continue
                        add(st.languageTag.ifEmpty { @Suppress("DEPRECATION") st.locale })
                    }
                }
            } catch (_: Exception) {}
            return out.values.take(4).ifEmpty { listOf("en-US") }
        }

        /** The candidate the user's recent typed tasks are written in, if it is clear. */
        private fun typedLanguage(ctx: Context, cands: List<String>): String? {
            val texts = (Runs.list(ctx).take(12).map { it.task } + Prefs.task).joinToString(" ").lowercase()
            val words = texts.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
            val counts = cands.associateWith { c -> STOPWORDS[lang(c)]?.let { sw -> words.count { it in sw } } ?: 0 }
            val best = counts.maxByOrNull { it.value } ?: return null
            val second = counts.values.sortedDescending().getOrElse(1) { 0 }
            return if (best.value >= 3 && best.value >= 2 * second) best.key else null
        }

        fun displayName(tag: String): String =
            LANGUAGES.firstOrNull { it.first == tag && tag.isNotEmpty() }?.second
                ?: Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.uppercase() } }.ifEmpty { "Automatic" }

        /** Settings text for the current choice, e.g. "Automatic · English, Deutsch". */
        fun settingText(ctx: Context): String {
            val s = Prefs.voiceLang
            if (s.isNotEmpty()) return displayName(s)
            return "Automatic · " + autoLanguages(ctx).joinToString(", ") { displayName(lang(it)) }
        }
    }

    fun start(setting: String) {
        if (active) return
        if (!hasPermission(ctx)) { onEnd("Microphone permission needed"); return }
        if (!available(ctx)) { onEnd("No speech recognition on this phone (it usually comes with the Google app)"); return }
        this.setting = setting
        plain = false
        forced = null
        listen()
    }

    private fun listen() {
        lastPartial = ""
        detected = null
        switched = false
        heardSpeech = false
        val auto = setting.isEmpty()
        val cands = testLanguages ?: if (auto) autoLanguages(ctx) else listOf(setting)
        val primary = forced ?: if (!auto) setting else
            Prefs.lastVoiceLang.takeIf { it in cands } ?: typedLanguage(ctx, cands) ?: cands.first()
        language = primary
        val others = cands.filter { lang(it) != lang(primary) }
        // Language detection needs Android 14 and, in practice, the on-device recognizer.
        detectionUsed = auto && forced == null && others.isNotEmpty() && !plain && Build.VERSION.SDK_INT >= 34 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        val r = try {
            if (detectionUsed) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (e: Exception) { onEnd("Speech recognition failed: ${e.message}"); return }
        rec = r
        active = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { heardSpeech = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onLanguageDetection(results: Bundle) {
                val l = normalize(results.getString(SpeechRecognizer.DETECTED_LANGUAGE)) ?: return
                val level = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)
                if (results.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT) == SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SUCCEEDED) switched = true
                if (level >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT) detected = l
            }

            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: return
                if (t.isNotEmpty()) { lastPartial = t; onPartial(t) }
            }

            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().ifEmpty { lastPartial }
                finish()
                if (retryInDetected(cands, primary)) return
                learn(cands)
                if (t.isNotEmpty()) { onFinal(t); onEnd(null) } else onEnd("Didn't catch that")
            }

            override fun onError(error: Int) {
                val partial = lastPartial
                finish()
                if (retryInDetected(cands, primary)) return
                learn(cands)
                // Some recognizers end with "no match" although partial results came in; use them.
                if (partial.isNotEmpty() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    onFinal(partial); onEnd(null); return
                }
                // The on-device recognizer can't do this language, or refused detection: listen again the plain way.
                val langError = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (detectionUsed && partial.isEmpty() && !heardSpeech && (langError || error == SpeechRecognizer.ERROR_CLIENT)) {
                    plain = true
                    listen()
                    return
                }
                onEnd(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection"
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone not available"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Speech recognition for ${displayName(primary)} isn't available. Pick the language in Phone Agent → Settings → Voice language."
                    else -> "Speech recognition error $error"
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
        if (auto && forced == null && others.isNotEmpty()) {
            // Google's recognizer listens for these too.
            i.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", others.toTypedArray())
        }
        if (detectionUsed) {
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(cands))
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(cands))
        }
        if (BuildConfig.DEBUG) {
            testAudio?.invoke()?.let { (pfd, rate) ->
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
            }
            AgentBus.log("Voice: listening in $primary, candidates $cands, detection $detectionUsed")
        }
        try { r.startListening(i) } catch (e: Exception) { finish(); onEnd("Speech recognition failed: ${e.message}") }
    }

    /**
     * The user spoke another of their languages and the recognizer didn't switch by itself: the transcript is in the
     * wrong language, so listen again in the detected one. Only once per session.
     */
    private fun retryInDetected(cands: List<String>, primary: String): Boolean {
        val d = detected ?: return false
        if (switched || forced != null || lang(d) == lang(primary)) return false
        val target = cands.firstOrNull { lang(it) == lang(d) } ?: return false
        if (BuildConfig.DEBUG) AgentBus.log("Voice: heard $d while listening in $primary; listening again in $target")
        Prefs.lastVoiceLang = target
        forced = target
        onRetry(displayName(lang(target)))
        listen()
        return true
    }

    /** Remembers the language that was heard, so the next session starts in it. */
    private fun learn(cands: List<String>) {
        val d = detected ?: return
        cands.firstOrNull { lang(it) == lang(d) }?.let { Prefs.lastVoiceLang = it }
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
