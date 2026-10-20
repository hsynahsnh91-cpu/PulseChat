package com.example.data.api

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiApiService(
    private var customApiKey: String? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Primary model as recommended by Google AI Studio (gemini-3.5-flash is stable and supported), followed by fallbacks
    private val modelsToTry = listOf(
        "gemini-3.5-flash",
        "gemini-flash-latest"
    )

    fun setApiKey(key: String) {
        customApiKey = key
    }

    private fun getActiveApiKey(): String {
        val key = customApiKey?.trim()
        if (!key.isNullOrEmpty()) return key
        return try {
            val k1 = BuildConfig.GEMINI_API_KEY.trim()
            if (k1.isNotEmpty()) k1 else BuildConfig.AI_API_KEY.trim()
        } catch (e: Exception) {
            "AQ.Ab8RN6Il81d1f3EPouPc4psuApuIdN8p61CITFuau1g_F0BedQ"
        }
    }

    suspend fun generateContent(
        prompt: String,
        systemInstruction: String? = null,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("يرجى إدخال نص السؤال أو الطلب"))
        }

        val apiKey = getActiveApiKey()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("مفتاح API غير متوفر"))
        }

        var lastError: Exception? = null

        for (model in modelsToTry) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                val contentsArray = JSONArray()

                for ((role, text) in conversationHistory) {
                    val turnObj = JSONObject()
                    turnObj.put("role", if (role == "model" || role == "assistant") "model" else "user")
                    val parts = JSONArray()
                    val part = JSONObject()
                    part.put("text", text)
                    parts.put(part)
                    turnObj.put("parts", parts)
                    contentsArray.put(turnObj)
                }

                val currentTurn = JSONObject()
                currentTurn.put("role", "user")
                val currentParts = JSONArray()
                val part = JSONObject()
                part.put("text", prompt)
                currentParts.put(part)
                currentTurn.put("parts", currentParts)
                contentsArray.put(currentTurn)

                val root = JSONObject()
                root.put("contents", contentsArray)

                if (!systemInstruction.isNullOrBlank()) {
                    val sysInstObj = JSONObject()
                    val sysParts = JSONArray()
                    val sysPart = JSONObject()
                    sysPart.put("text", systemInstruction)
                    sysParts.put(sysPart)
                    sysInstObj.put("parts", sysParts)
                    root.put("systemInstruction", sysInstObj)
                }

                val genConfig = JSONObject()
                genConfig.put("temperature", 0.7)
                root.put("generationConfig", genConfig)

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = root.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsedMessage = try {
                            val errObj = JSONObject(responseBody).optJSONObject("error")
                            errObj?.optString("message") ?: "Error ${response.code}"
                        } catch (e: Exception) {
                            "Error ${response.code}: $responseBody"
                        }
                        Log.w("GeminiApiService", "Model $model error ${response.code}: $parsedMessage")
                        lastError = Exception(parsedMessage)
                        // Try fallback model if any error occurs
                        return@use
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val contentObj = firstCandidate.optJSONObject("content")
                        val parts = contentObj?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val replyText = parts.getJSONObject(0).optString("text")
                            return@withContext Result.success(replyText)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GeminiApiService", "Failed model $model call", e)
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("تعذر الاتصال بخدمة الذكاء الاصطناعي"))
    }

    suspend fun generateSmartReplies(lastMessages: List<String>): List<String> = withContext(Dispatchers.IO) {
        val prompt = "اقترح 3 ردود سريعة وقصيرة ومناسبة جداً باللغة العربية للرسالة الأخيرة في هذه المحادثة. أعد فقط الردود الثلاثة مفصولة بفاصلة (|) دون أي نصوص إضافية أو ترقيم:\n${lastMessages.joinToString("\n")}"
        val result = generateContent(
            prompt = prompt,
            systemInstruction = "أنت مساعد محادثات ذكي وموجز. تقترح 3 ردود قصيرة ودية وطبيعية مفصولة فقط بفاصلة |"
        )
        result.fold(
            onSuccess = { text ->
                val replies = text.split("|").map { it.trim().removePrefix("-").removePrefix("1.").removePrefix("2.").removePrefix("3.").trim() }
                    .filter { it.isNotEmpty() }
                if (replies.isNotEmpty()) replies.take(3)
                else listOf("تمام، فهمتك!", "شكراً جزيلاً 👍", "سأتواصل معك لاحقاً")
            },
            onFailure = {
                listOf("تمام 👍", "شكراً لك!", "أهلاً وسهلاً")
            }
        )
    }

    suspend fun summarizeConversation(transcript: String): String = withContext(Dispatchers.IO) {
        val prompt = "يرجى تلخيص هذه المحادثة في 3 إلى 5 نقاط رئيسية واضحة ومفيدة باللغة العربية:\n\n$transcript"
        val result = generateContent(
            prompt = prompt,
            systemInstruction = "أنت مساعد تلخيص محادثات احترافي باللغة العربية."
        )
        result.getOrElse { "تعذر تلخيص المحادثة: ${it.localizedMessage}" }
    }

    suspend fun polishMessage(draft: String): String = withContext(Dispatchers.IO) {
        val prompt = "أعد صياغة هذه الرسالة لتكون واضحة، أنيقة، وخالية من الأخطاء الإملائية مع الحفاظ على المعنى الأصلي:\n\"$draft\""
        val result = generateContent(
            prompt = prompt,
            systemInstruction = "أعد صياغة النص المطلوب مباشرة باللغة العربية دون مقدمات."
        )
        result.getOrElse { draft }
    }
}
