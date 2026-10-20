package com.example.domain

import java.util.UUID
import kotlin.random.Random

/** Stable identifiers. Message IDs are client-generated UUIDs and double as idempotency keys. */
object Ids {
    /** Local-only Gemini assistant participant (kept identical to the legacy constant). */
    const val AI_ASSISTANT_ID = "user_gemini_ai"
    const val AI_CONVERSATION_ID = "ai_assistant"
    private const val DIRECT_PREFIX = "dm_"

    /** Deterministic ID so two users can never end up with duplicate direct conversations. */
    fun directConversationId(a: String, b: String): String {
        require(a.isNotBlank() && b.isNotBlank() && a != b) { "invalid direct participants" }
        val (first, second) = if (a < b) a to b else b to a
        return "$DIRECT_PREFIX${first}_$second"
    }

    fun isDirectConversationId(id: String): Boolean = id.startsWith(DIRECT_PREFIX)

    fun newMessageId(): String = UUID.randomUUID().toString()
    fun newId(prefix: String): String = prefix + "_" + UUID.randomUUID().toString().replace("-", "")

    /** Public 10-digit numeric account ID (never starts with 0). */
    fun generate10DigitId(random: Random = Random.Default): String {
        val sb = StringBuilder().append(random.nextInt(1, 10))
        repeat(9) { sb.append(random.nextInt(0, 10)) }
        return sb.toString()
    }

    /**
     * Username/password accounts are backed by Firebase email/password auth. The address is a
     * synthetic, non-deliverable login identifier under the project's own firebaseapp.com domain;
     * it is never shown to other users and is not stored in Firestore.
     */
    fun authEmailForUsername(username: String, projectId: String): String =
        "${username.trim().lowercase()}@${projectId.trim().lowercase()}.firebaseapp.com"
}
