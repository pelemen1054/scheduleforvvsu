package ru.vvsu.schedule

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device Russian -> English/Chinese translation.
 * Translation results are cached locally so repeated timetable loads do not
 * translate the same text again.
 */
class ScheduleTextTranslator(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "schedule_translation_cache",
        Context.MODE_PRIVATE
    )
    private val translators = mutableMapOf<String, Translator>()
    private val downloadedLanguages = mutableSetOf<String>()

    private fun translatorFor(language: String): Translator {
        val targetLanguage = when (language) {
            "en" -> TranslateLanguage.ENGLISH
            "zh" -> TranslateLanguage.CHINESE
            else -> return error("Unsupported translation language: $language")
        }
        return translators.getOrPut(language) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.RUSSIAN)
                .setTargetLanguage(targetLanguage)
                .build()
            Translation.getClient(options)
        }
    }

    suspend fun translate(
        text: String,
        language: String,
        onModelDownloadStateChanged: (Boolean) -> Unit = {}
    ): String {
        val source = text.trim()
        if (source.isBlank() || language == "ru") return text

        val key = cacheKey(language, source)
        preferences.getString(key, null)?.let { return it }

        val translator = translatorFor(language)
        if (language !in downloadedLanguages) {
            onModelDownloadStateChanged(true)
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
                        .addOnSuccessListener {
                            downloadedLanguages.add(language)
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        .addOnFailureListener { error ->
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                }
            } finally {
                onModelDownloadStateChanged(false)
            }
        }

        val translated = suspendCancellableCoroutine<String> { continuation ->
            translator.translate(source)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

        preferences.edit().putString(key, translated).apply()
        return translated
    }

    private fun cacheKey(language: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${language}_${digest}"
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
