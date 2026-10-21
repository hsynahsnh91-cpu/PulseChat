package com.example.data.repository

import com.example.data.db.AppDatabase
import com.example.data.model.Message
import com.example.data.model.MessageTranslation
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.Locale

class TranslationException(val reason: Reason, val languages: List<String> = emptyList()) : Exception(reason.name) {
    enum class Reason { NEEDS_DOWNLOAD, SAME_LANGUAGE, UNDETECTED, UNSUPPORTED, DOWNLOAD_FAILED, FAILED, EMPTY }
}

/**
 * In-chat translation on the device (ML Kit). Message text never leaves the phone: only the
 * language packs (~30 MB each, no user data) are downloaded from Google, after the user agrees.
 * The original message is never modified; translations are cached per message and target
 * language and dropped automatically when the message is edited.
 */
class TranslationRepository(private val db: AppDatabase) {
    data class Result(val text: String, val sourceLang: String, val targetLang: String)

    private val models = RemoteModelManager.getInstance()

    /** BCP-47 tags of all languages the translator supports. */
    fun supportedLanguages(): List<String> = TranslateLanguage.getAllLanguages().sortedBy { displayName(it) }

    fun displayName(tag: String, locale: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(tag).getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    /** The app/device language when supported, else English. */
    fun defaultTarget(appLanguageTag: String?): String {
        val tag = appLanguageTag?.takeIf { it.isNotBlank() } ?: Locale.getDefault().language
        return TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
    }

    suspend fun cached(m: Message, target: String): Result? =
        db.translationDao().get(m.messageId, target)?.takeIf { it.sourceHash == hash(m.content) }?.let { Result(it.text, it.sourceLang, target) }

    suspend fun translate(m: Message, target: String, allowDownload: Boolean): Result {
        val text = m.content.trim()
        if (text.isEmpty()) throw TranslationException(TranslationException.Reason.EMPTY)
        cached(m, target)?.let { return it }
        val detected = runCatching { LanguageIdentification.getClient().identifyLanguage(text).await() }.getOrNull()
        if (detected == null || detected == "und") throw TranslationException(TranslationException.Reason.UNDETECTED)
        val source = TranslateLanguage.fromLanguageTag(detected) ?: throw TranslationException(TranslationException.Reason.UNSUPPORTED, listOf(detected))
        val targetLang = TranslateLanguage.fromLanguageTag(target) ?: throw TranslationException(TranslationException.Reason.UNSUPPORTED, listOf(target))
        if (source == targetLang) throw TranslationException(TranslationException.Reason.SAME_LANGUAGE, listOf(source))
        val missing = listOf(source, targetLang).filter { !isDownloaded(it) }
        if (missing.isNotEmpty() && !allowDownload) throw TranslationException(TranslationException.Reason.NEEDS_DOWNLOAD, missing)
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(targetLang).build())
        try {
            if (missing.isNotEmpty()) {
                try {
                    withTimeout(DOWNLOAD_TIMEOUT_MS) { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                } catch (e: Exception) {
                    throw TranslationException(TranslationException.Reason.DOWNLOAD_FAILED, missing)
                }
            }
            val out = try {
                withTimeout(TRANSLATE_TIMEOUT_MS) { translator.translate(text).await() }
            } catch (e: Exception) {
                throw TranslationException(TranslationException.Reason.FAILED)
            }
            db.translationDao().upsert(MessageTranslation(m.messageId, targetLang, source, hash(m.content), out, System.currentTimeMillis()))
            return Result(out, source, targetLang)
        } finally {
            translator.close()
        }
    }

    private suspend fun isDownloaded(lang: String): Boolean =
        lang == TranslateLanguage.ENGLISH ||
            runCatching { models.isModelDownloaded(TranslateRemoteModel.Builder(lang).build()).await() }.getOrDefault(false)

    /** Language packs on this device (for Settings). */
    suspend fun downloadedLanguages(): List<String> =
        runCatching { models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language } }.getOrDefault(emptyList())

    suspend fun deleteLanguage(lang: String) {
        runCatching { models.deleteDownloadedModel(TranslateRemoteModel.Builder(lang).build()).await() }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        private const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
        private const val TRANSLATE_TIMEOUT_MS = 30_000L
    }
}
