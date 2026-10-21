package com.example.domain

import java.util.UUID

/**
 * References to files kept in PulseChat's built-in media store: Firestore documents
 * `media/{mediaId}` with the file split into `media/{mediaId}/chunks/{index}` documents.
 *
 * The built-in store is used whenever Cloud Storage is not available for the project (no default
 * bucket, or the bucket is not usable on the current plan), so photos, voice notes, videos,
 * documents, statuses and profile/group photos keep working. A reference such as
 * `pulsemedia://md0123…` is stored exactly where a download URL would otherwise go (message and
 * status `media.url` / `media.path`, `users/{uid}/restricted/photo.url`, group `photoUrl`).
 *
 * Like Cloud Storage objects, chunks are readable by signed-in users who know the random media ID,
 * and the ID only appears in documents the reader is already allowed to see. `firestore.rules`
 * enforces the same limits as [CHUNK_BYTES] and [MAX_CHUNKS].
 */
object MediaRefs {
    const val SCHEME = "pulsemedia"
    private const val PREFIX = "$SCHEME://"

    /** Payload per chunk document (Firestore documents are limited to 1 MiB). */
    const val CHUNK_BYTES = 512 * 1024

    /** Largest chunk count per file; with [CHUNK_BYTES] this allows files of up to 32 MB. */
    const val MAX_CHUNKS = 64

    /** Largest file the built-in store accepts. */
    const val MAX_BYTES = CHUNK_BYTES.toLong() * MAX_CHUNKS

    private val idPattern = Regex("^[A-Za-z0-9_-]{8,64}$")

    fun isValidId(mediaId: String): Boolean = idPattern.matches(mediaId)

    /** True for references to the built-in media store. */
    fun isPulse(ref: String?): Boolean = ref != null && ref.startsWith(PREFIX) && isValidId(ref.removePrefix(PREFIX))

    fun ref(mediaId: String): String {
        require(isValidId(mediaId)) { "invalid media id" }
        return PREFIX + mediaId
    }

    fun idOf(ref: String?): String? = if (isPulse(ref)) ref!!.removePrefix(PREFIX) else null

    fun chunkCount(sizeBytes: Long): Int = if (sizeBytes <= 0L) 0 else ((sizeBytes + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()

    fun fits(sizeBytes: Long): Boolean = sizeBytes in 1..MAX_BYTES

    /** A fresh, unguessable media ID. */
    fun newMediaId(): String = "md" + UUID.randomUUID().toString().replace("-", "")

    /**
     * Media ID derived from a message/status ID (both random UUIDs), so a retried upload writes the
     * same documents again instead of leaving orphans behind.
     */
    fun mediaIdFor(key: String): String {
        val clean = key.filter { it.isLetterOrDigit() && it.code < 128 }.take(60)
        return if (clean.length >= 16) "md$clean" else newMediaId()
    }
}
