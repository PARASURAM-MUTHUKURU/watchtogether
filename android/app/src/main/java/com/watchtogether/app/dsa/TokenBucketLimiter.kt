package com.watchtogether.app.dsa

/**
 * DSA: Token Bucket Rate Limiter Algorithm.
 *
 * Allows controlled bursts up to [capacity] tokens while enforcing a steady refill rate
 * of [refillRatePerSecond] tokens per second.
 *
 * Time Complexity: O(1) per acquire attempt.
 * Space Complexity: O(1).
 */
class TokenBucketLimiter(
    val capacity: Double,
    val refillRatePerSecond: Double
) {
    private var availableTokens: Double = capacity
    private var lastRefillTimestampNanos: Long = System.nanoTime()

    @Synchronized
    fun tryAcquire(tokens: Double = 1.0): Boolean {
        refill()
        return if (availableTokens >= tokens) {
            availableTokens -= tokens
            true
        } else {
            false
        }
    }

    @Synchronized
    private fun refill() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillTimestampNanos) / 1_000_000_000.0
        if (elapsedSeconds > 0) {
            availableTokens = minOf(capacity, availableTokens + elapsedSeconds * refillRatePerSecond)
            lastRefillTimestampNanos = now
        }
    }

    @Synchronized
    fun reset() {
        availableTokens = capacity
        lastRefillTimestampNanos = System.nanoTime()
    }
}

