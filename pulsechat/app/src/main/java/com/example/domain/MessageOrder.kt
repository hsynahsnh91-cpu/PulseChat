package com.example.domain

/** Where a message created on this phone is listed until the server's time stamp arrives. */
object MessageOrder {
    /**
     * Time stamp for a new outgoing message: [now] (the best estimate of server time), moved to just
     * after [newest] - the newest message already in the chat - when that one is not older. A message
     * therefore always appears below everything already shown, even when the phone's clock is behind
     * the server or another message carries the same millisecond.
     */
    fun nextCreatedAt(now: Long, newest: Long?): Long = if (newest != null && newest >= now) newest + 1 else now
}
