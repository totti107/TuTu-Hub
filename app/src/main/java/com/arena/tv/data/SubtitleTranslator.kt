package com.arena.tv.data

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AI subtitle translation, on the device (Google ML Kit): each subtitle line is translated as it appears.
 * The first time a language pair is used its model (~30 MB) downloads; after that it works offline.
 */
class SubtitleTranslator {
    companion object {
        /** Languages offered in the app (all supported by the on-device translator). */
        val LANGS = listOf("en" to "English", "ar" to "Arabic", "fr" to "French", "es" to "Spanish", "de" to "German", "it" to "Italian",
            "pt" to "Portuguese", "tr" to "Turkish", "nl" to "Dutch", "hi" to "Hindi", "ur" to "Urdu", "ru" to "Russian", "fa" to "Persian")
        fun name(code: String) = LANGS.firstOrNull { it.first == code }?.second ?: code
    }

    /** "Downloading Arabic…" while a language model downloads; null otherwise. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 600
    }
    private val langId by lazy { LanguageIdentification.getClient() }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }

    /** Works out the language of a subtitle line ("und" when unsure). */
    suspend fun detect(text: String): String = runCatching { langId.identifyLanguage(text).await() }.getOrDefault("und")

    /**
     * @param source the subtitle track's language if the stream says it (else it's detected from the text)
     * @return the translated line, or the original when it's already in [target] or can't be translated
     */
    suspend fun translate(text: String, source: String?, target: String): String {
        if (text.isBlank()) return text
        val from = (source?.takeIf { it.length >= 2 && it != "und" } ?: detect(text)).substringBefore('-').lowercase()
        if (from == "und" || from == target) return text
        val src = TranslateLanguage.fromLanguageTag(from) ?: return text
        val dst = TranslateLanguage.fromLanguageTag(target) ?: return text
        val key = "$src>$dst|$text"
        synchronized(cache) { cache[key] }?.let { return it }
        val pair = "$src>$dst"
        val t = synchronized(translators) {
            translators.getOrPut(pair) { Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()) }
        }
        if (pair !in ready) {
            _status.value = "Downloading the ${name(dst)} translator (first time only)…"
            try { t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await(); ready += pair } finally { _status.value = null }
        }
        val out = t.translate(text).await()
        synchronized(cache) { cache[key] = out }
        return out
    }
}
