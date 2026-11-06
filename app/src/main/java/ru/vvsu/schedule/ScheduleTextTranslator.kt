package ru.vvsu.schedule

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device Russian -> English/Chinese translation.
 * Models are downloaded only after an explicit user action.
 * Translation results are cached locally.
 */
class ScheduleTextTranslator(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "schedule_translation_cache",
        Context.MODE_PRIVATE
    )
    private val translators = mutableMapOf<String, Translator>()
    private val verifiedModels = mutableSetOf<String>()
    private val modelManager = RemoteModelManager.getInstance()

    private fun targetLanguage(language: String): String = when (language) {
        "en" -> TranslateLanguage.ENGLISH
        "zh" -> TranslateLanguage.CHINESE
        else -> error("Unsupported translation language: $language")
    }

    private fun modelFor(language: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(targetLanguage(language)).build()

    private fun translatorFor(language: String): Translator =
        translators.getOrPut(language) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.RUSSIAN)
                .setTargetLanguage(targetLanguage(language))
                .build()
            Translation.getClient(options)
        }

    suspend fun isModelDownloaded(language: String): Boolean {
        if (language == "ru") return true
        AppLogger.info(appContext, "Проверка наличия и работоспособности модели перевода: $language")
        return try {
            val downloaded = modelManager.isModelDownloaded(modelFor(language)).awaitResult()
            if (!downloaded) {
                AppLogger.info(appContext, "Менеджер ML Kit сообщает: модель $language не загружена")
                return false
            }

            if (language in verifiedModels) return true

            // RemoteModelManager may report "downloaded" while Translator cannot
            // access the model files. A tiny real translation checks the usable path once.
            try {
                translatorFor(language).translate("Проверка").awaitResult()
                verifiedModels.add(language)
                AppLogger.info(appContext, "Модель $language найдена и успешно прошла тестовый перевод")
                true
            } catch (verificationError: Exception) {
                if (isModelFilesMissing(verificationError)) {
                    AppLogger.warning(appContext, "ML Kit видит модель $language, но файлы недоступны. Сбрасываем ошибочный статус.")
                    deleteModel(language)
                    false
                } else {
                    AppLogger.error(appContext, "Не удалось проверить работоспособность модели $language", verificationError)
                    throw verificationError
                }
            }
        } catch (exception: Exception) {
            AppLogger.error(appContext, "Ошибка проверки модели перевода: $language", exception)
            throw exception
        }
    }

    /**
     * Called only from the Settings download button.
     * Wi-Fi is required to avoid unexpected mobile-data usage.
     */
    suspend fun downloadModel(language: String) {
        require(language == "en" || language == "zh") {
            "Unsupported translation language: $language"
        }
        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()
        AppLogger.info(appContext, "Запуск загрузки модели через Translator.downloadModelIfNeeded: $language")
        try {
            // Use the same Translator instance that will later perform translations.
            // ML Kit checks/downloads the actual files required by this translator.
            // Clear a stale "downloaded" flag if ML Kit's model files are missing.
            val managerSaysDownloaded = modelManager.isModelDownloaded(modelFor(language)).awaitResult()
            if (managerSaysDownloaded) {
                try {
                    translatorFor(language).translate("Проверка").awaitResult()
                    verifiedModels.add(language)
                    AppLogger.info(appContext, "Модель $language уже установлена и прошла проверку")
                    return
                } catch (verificationError: Exception) {
                    if (!isModelFilesMissing(verificationError)) throw verificationError
                    AppLogger.warning(appContext, "Обнаружена неполная модель $language. Удаляем перед повторной загрузкой.")
                    deleteModel(language)
                }
            }

            translatorFor(language).downloadModelIfNeeded(conditions).awaitResult()
            AppLogger.info(appContext, "Загрузка ML Kit завершена. Проверяем реальный перевод.")
            translatorFor(language).translate("Проверка").awaitResult()
            verifiedModels.add(language)
            AppLogger.info(appContext, "Модель $language загружена и прошла тестовый перевод")
        } catch (exception: Exception) {
            AppLogger.error(appContext, "Загрузка модели через Translator завершилась ошибкой: $language", exception)
            throw exception
        }
    }

    suspend fun deleteModel(language: String) {
        require(language == "en" || language == "zh") {
            "Unsupported translation language: $language"
        }
        translators.remove(language)?.close()
        verifiedModels.remove(language)
        AppLogger.info(appContext, "Удаление локальной модели перевода: $language")
        modelManager.deleteDownloadedModel(modelFor(language)).awaitResult()
        AppLogger.info(appContext, "Локальная модель перевода удалена: $language")
    }

    suspend fun translate(text: String, language: String): String {
        val source = text.trim()
        if (source.isBlank() || language == "ru") return text

        val key = cacheKey(language, source)
        preferences.getString(key, null)?.let { return it }

        check(isModelDownloaded(language)) {
            "Translation model for $language has not been downloaded"
        }

        return try {
            val translated = translatorFor(language).translate(source).awaitResult()
            preferences.edit().putString(key, translated).apply()
            translated
        } catch (exception: Exception) {
            AppLogger.error(appContext, "ML Kit не смог перевести элемент (язык=$language, длина текста=${source.length})", exception)
            if (isModelFilesMissing(exception)) {
                AppLogger.warning(appContext, "Обнаружены отсутствующие файлы модели $language. Запускается удаление неисправной модели.")
                try {
                    deleteModel(language)
                } catch (deleteException: Exception) {
                    AppLogger.error(appContext, "Не удалось удалить неисправную модель $language", deleteException)
                }
                throw TranslationModelFilesMissingException(language, exception)
            }
            throw exception
        }
    }

    private fun isModelFilesMissing(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message?.contains("Translation model files not found", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                completed.set(true)
            }
            addOnSuccessListener { result ->
                if (completed.compareAndSet(false, true)) {
                    continuation.resume(result)
                }
            }
            addOnFailureListener { error ->
                if (completed.compareAndSet(false, true)) {
                    continuation.resumeWithException(error)
                }
            }
            addOnCanceledListener {
                if (completed.compareAndSet(false, true)) {
                    continuation.cancel()
                }
            }
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
        verifiedModels.clear()
    }
}

class TranslationModelFilesMissingException(
    val language: String,
    cause: Throwable
) : Exception("Translation model files are missing for $language", cause)
