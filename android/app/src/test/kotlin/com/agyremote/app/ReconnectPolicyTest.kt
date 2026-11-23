package com.agyremote.app

import com.agyremote.app.network.ReconnectPolicy
import com.agyremote.app.network.SessionSequenceTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {

    @Test
    fun `computes exponential backoff with cap and resets on success`() {
        val policy = ReconnectPolicy(
            baseDelayMs = 1_000L,
            maxDelayMs = 8_000L,
            multiplier = 2.0,
            jitterRatio = 0.0,
        )

        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(4_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
        assertEquals(5, policy.attempts)

        policy.reset()
        assertEquals(0, policy.attempts)
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `jitter stays within safe bounds`() {
        val policy = ReconnectPolicy(
            baseDelayMs = 1_000L,
            maxDelayMs = 30_000L,
            jitterRatio = 0.2,
            random = Random(42),
        )
        repeat(10) {
            val d = policy.nextDelayMs()
            assertTrue("Delay $d must be >= 250ms", d >= 250L)
            assertTrue("Delay $d must be <= 30000ms", d <= 30_000L)
        }
    }

    @Test
    fun `tracks highest sequence number per session monotonically`() {
        val tracker = SessionSequenceTracker()
        assertNull(tracker.lastSeq("s1"))

        tracker.record("s1", 5L)
        tracker.record("s1", 3L) // out-of-order lower seq must not regress
        assertEquals(5L, tracker.lastSeq("s1"))

        tracker.record("s1", 12L)
        tracker.record("s2", 2L)
        assertEquals(12L, tracker.lastSeq("s1"))
        assertEquals(2L, tracker.lastSeq("s2"))

        tracker.clear("s1")
        assertNull(tracker.lastSeq("s1"))
        assertEquals(2L, tracker.lastSeq("s2"))
    }
}
