package com.agyremote.app.network

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff policy for automatic WebSocket reconnection.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val multiplier: Double = 2.0,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attemptCount = 0

    val attempts: Int get() = attemptCount

    fun nextDelayMs(): Long {
        val exp = baseDelayMs * multiplier.pow(attemptCount.toDouble())
        val capped = min(exp.toLong(), maxDelayMs)
        attemptCount += 1
        if (jitterRatio <= 0.0) return capped
        val jitterRange = (capped * jitterRatio).toLong().coerceAtLeast(1L)
        val delta = random.nextLong(-jitterRange, jitterRange + 1)
        return (capped + delta).coerceIn(250L, maxDelayMs)
    }

    fun reset() {
        attemptCount = 0
    }
}

/**
 * Tracks the highest seen output sequence number per PTY session
 * so reconnects request only missed scrollback deltas (sinceSeq).
 */
class SessionSequenceTracker {
    private val seqBySession = mutableMapOf<String, Long>()

    @Synchronized
    fun record(sessionId: String, seq: Long) {
        val current = seqBySession[sessionId] ?: 0L
        if (seq > current) {
            seqBySession[sessionId] = seq
        }
    }

    @Synchronized
    fun lastSeq(sessionId: String): Long? = seqBySession[sessionId]

    @Synchronized
    fun clear(sessionId: String) {
        seqBySession.remove(sessionId)
    }

    @Synchronized
    fun clearAll() {
        seqBySession.clear()
    }
}
