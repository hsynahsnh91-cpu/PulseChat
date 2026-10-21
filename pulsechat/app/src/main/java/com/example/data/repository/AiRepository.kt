package com.example.data.repository

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
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

enum class AiError { CONSENT_REQUIRED, RATE_LIMITED, NOT_CONFIGURED, BLOCKED, NETWORK, TIMEOUT, EMPTY, UNKNOWN, TOO_LARGE, BAD_RESPONSE }

class AiException(val error: AiError, detail: String? = null) : Exception(detail ?: error.name)

/**
 * Gemini via Firebase AI Logic. The Gemini API key is held by Firebase on the server side, so no
 * AI credential is embedded in the APK (protect the endpoint with App Check in production).
 * A client-side limiter prevents accidental request floods; the server enforces real quotas.
 * Every call here is triggered by an explicit user action after consent (see ChatViewModel).
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

    /** Runs [block] with the first available model; maps SDK errors to [AiError]. */
    private suspend fun <T> withModel(systemInstruction: String, temp: Float, json: Boolean, timeoutMs: Long = 60_000, block: suspend (GenerativeModel) -> T): T {
        mutex.withLock { checkRate() }
        var last: AiException? = null
        for (name in models) {
            try {
                return withContext(Dispatchers.IO) {
                    withTimeout(timeoutMs) {
                        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                            modelName = name,
                            generationConfig = generationConfig {
                                temperature = temp
                                if (json) responseMimeType = "application/json"
                            },
                            systemInstruction = content { text(systemInstruction) },
                        )
                        block(model)
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

    /** Multi-turn chat. [history] pairs are (fromUser, text), oldest first. */
    suspend fun chat(history: List<Pair<Boolean, String>>, prompt: String, systemInstruction: String): String {
        if (prompt.isBlank()) throw AiException(AiError.EMPTY)
        return withModel(systemInstruction, 0.7f, json = false) { model ->
            val chat = model.startChat(history.takeLast(20).map { (fromUser, text) ->
                content(role = if (fromUser) "user" else "model") { text(text) }
            })
            chat.sendMessage(prompt).text?.trim().orEmpty().ifEmpty { throw AiException(AiError.EMPTY) }
        }
    }

    suspend fun complete(prompt: String, systemInstruction: String): String = chat(emptyList(), prompt, systemInstruction)

    private suspend fun completeJson(prompt: String, systemInstruction: String): String =
        withModel(systemInstruction, 0.2f, json = true) { model ->
            model.generateContent(prompt).text?.trim().orEmpty().ifEmpty { throw AiException(AiError.EMPTY) }
        }

    suspend fun summarize(transcript: String, languageName: String): String = complete(
        "Summarize this conversation in 3 to 5 short bullet points, in $languageName:\n\n$transcript",
        "You summarize chats faithfully and concisely. Never invent facts. Output only the bullet points.",
    )

    /**
     * Summary of the messages since the user's last visit. Each line of [lines] starts with an alias
     * like "m3"; the model must cite those aliases. Returns raw JSON for [com.example.domain.AiParsing.parseSummary].
     */
    suspend fun summarizeWithReferences(lines: String, languageName: String): String = completeJson(
        "These are the messages a user missed in a chat since their last visit. Each line is \"<id> | <sender>: <text>\".\n" +
            "Write 2 to 6 short summary points in $languageName. For each point list the ids of the messages it is based on.\n" +
            "Return JSON exactly like {\"points\":[{\"text\":\"...\",\"refs\":[\"m1\",\"m4\"]}]}.\n\n$lines",
        "You summarize chats faithfully. Never invent facts, names, times or decisions that are not in the messages. " +
            "Every point must cite at least one message id that supports it. Output only JSON.",
    )

    /**
     * Candidate tasks and events mentioned in the messages. Nothing is created from this: the user
     * reviews and confirms each item. Returns raw JSON for [com.example.domain.AiParsing.parseActions].
     */
    suspend fun extractActions(lines: String, nowIso: String, timeZoneId: String, languageName: String): String = completeJson(
        "Current local date and time: $nowIso (time zone $timeZoneId).\n" +
            "From these chat messages (each line is \"<id> | <sender>: <text>\"), list concrete tasks, to-dos, appointments and events " +
            "that someone should be reminded of. Write each title in $languageName. For \"due\" give an ISO-8601 local date-time " +
            "(e.g. 2026-10-22T17:00) when a time or date is stated or clearly implied, else null.\n" +
            "Return JSON exactly like {\"items\":[{\"kind\":\"task\",\"title\":\"...\",\"due\":null,\"allDay\":false,\"refs\":[\"m2\"]}]} " +
            "and {\"items\":[]} if there is nothing.\n\n$lines",
        "You extract action items from chats conservatively: only items explicitly present in the messages, no guesses. " +
            "Each item must cite the message ids it comes from. Output only JSON.",
    )

    /** Transcript of a voice message (audio is sent only after the user tapped "Transcribe"). */
    suspend fun transcribe(audio: ByteArray, mimeType: String): String {
        if (audio.isEmpty()) throw AiException(AiError.EMPTY)
        if (audio.size > MAX_AUDIO_BYTES) throw AiException(AiError.TOO_LARGE)
        return withModel(
            "You transcribe voice messages verbatim in their original language. You never translate, summarize or add anything.",
            0.0f, json = false, timeoutMs = 90_000,
        ) { model ->
            val prompt = content {
                inlineData(audio, mimeType)
                text("Transcribe this voice message exactly as spoken, in its original language. Output only the transcript. If there is no speech, output exactly: [no speech]")
            }
            model.generateContent(prompt).text?.trim().orEmpty().ifEmpty { throw AiException(AiError.EMPTY) }
        }
    }

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
            "request payload size" in text || "too large" in text || "413" in text -> AiException(AiError.TOO_LARGE)
            e is IOException || e.cause is IOException || "unable to resolve host" in text -> AiException(AiError.NETWORK)
            else -> AiException(AiError.UNKNOWN, e.message)
        }
    }

    companion object {
        private const val TAG = "AiRepository"
        private const val MAX_PER_MINUTE = 10
        /** Inline request limit is 20 MB; voice notes are far smaller. */
        const val MAX_AUDIO_BYTES = 15 * 1024 * 1024
    }
}
