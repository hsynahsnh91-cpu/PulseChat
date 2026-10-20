package com.example.data.repository

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

enum class AiError { CONSENT_REQUIRED, RATE_LIMITED, NOT_CONFIGURED, BLOCKED, NETWORK, TIMEOUT, EMPTY, UNKNOWN }

class AiException(val error: AiError, detail: String? = null) : Exception(detail ?: error.name)

/**
 * Gemini via Firebase AI Logic. The Gemini API key is held by Firebase on the server side, so no
 * AI credential is embedded in the APK (protect the endpoint with App Check in production).
 * A client-side limiter prevents accidental request floods; the server enforces real quotas.
 */
class AiRepository {
    private val models = listOf("gemini-3.5-flash", "gemini-flash-latest", "gemini-2.5-flash")
    private val mutex = Mutex()
    private val recent = ArrayDeque<Long>()

    private fun checkRate() {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
        if (recent.size >= MAX_PER_MINUTE) throw AiException(AiError.RATE_LIMITED)
        recent.addLast(now)
    }

    /** Multi-turn chat. [history] pairs are (fromUser, text), oldest first. */
    suspend fun chat(history: List<Pair<Boolean, String>>, prompt: String, systemInstruction: String): String {
        if (prompt.isBlank()) throw AiException(AiError.EMPTY)
        mutex.withLock { checkRate() }
        var last: AiException? = null
        for (name in models) {
            try {
                return withContext(Dispatchers.IO) {
                    withTimeout(60_000) {
                        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                            modelName = name,
                            generationConfig = generationConfig { temperature = 0.7f },
                            systemInstruction = content { text(systemInstruction) },
                        )
                        val chat = model.startChat(history.takeLast(20).map { (fromUser, text) ->
                            content(role = if (fromUser) "user" else "model") { text(text) }
                        })
                        chat.sendMessage(prompt).text?.trim().orEmpty().ifEmpty { throw AiException(AiError.EMPTY) }
                    }
                }
            } catch (e: Exception) {
                val mapped = map(e)
                Log.w(TAG, "model $name failed: ${e.javaClass.simpleName}: ${e.message}")
                last = mapped
                if (!isModelUnavailable(e)) throw mapped
            }
        }
        throw last ?: AiException(AiError.UNKNOWN)
    }

    suspend fun complete(prompt: String, systemInstruction: String): String = chat(emptyList(), prompt, systemInstruction)

    suspend fun summarize(transcript: String, languageName: String): String = complete(
        "Summarize this conversation in 3 to 5 short bullet points, in $languageName:\n\n$transcript",
        "You summarize chats faithfully and concisely. Never invent facts. Output only the bullet points.",
    )

    suspend fun smartReplies(lastMessages: List<String>, languageName: String): List<String> {
        val text = complete(
            "Suggest 3 short, natural replies in $languageName to the last message of this chat. " +
                "Return only the three replies separated by |\n\n" + lastMessages.joinToString("\n"),
            "You suggest brief, friendly replies. Output exactly three options separated by |, nothing else.",
        )
        return text.split("|").map { it.trim().trimStart('-', '•', '1', '2', '3', '.', ' ') }.filter { it.isNotEmpty() }.take(3)
    }

    suspend fun polish(draft: String, languageName: String): String = complete(
        "Rewrite this message so it is clear and correct, keeping its meaning and language ($languageName). Output only the rewritten message:\n\n$draft",
        "You are a careful copy editor. Keep the author's meaning and tone.",
    )

    private fun isModelUnavailable(e: Throwable): Boolean {
        val text = (e.message.orEmpty() + " " + (e.cause?.message ?: "")).lowercase()
        return "not found" in text || "404" in text || "is not supported" in text || "unknown model" in text
    }

    private fun map(e: Throwable): AiException {
        if (e is AiException) return e
        val name = e.javaClass.simpleName
        val text = (e.message.orEmpty() + " " + (e.cause?.message ?: "")).lowercase()
        return when {
            name.contains("Quota") || "quota" in text || "429" in text || "resource_exhausted" in text -> AiException(AiError.RATE_LIMITED)
            name.contains("Timeout") || name.contains("TimeoutCancellation") -> AiException(AiError.TIMEOUT)
            name.contains("PromptBlocked") || name.contains("ResponseStopped") || name.contains("ContentBlocked") -> AiException(AiError.BLOCKED)
            name.contains("ServiceDisabled") || name.contains("APINotConfigured") || name.contains("InvalidAPIKey") ||
                "has not been used" in text || "permission_denied" in text || "api key" in text || "app check" in text -> AiException(AiError.NOT_CONFIGURED, e.message)
            e is IOException || e.cause is IOException || "unable to resolve host" in text -> AiException(AiError.NETWORK)
            else -> AiException(AiError.UNKNOWN, e.message)
        }
    }

    companion object {
        private const val TAG = "AiRepository"
        private const val MAX_PER_MINUTE = 10
    }
}
