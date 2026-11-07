package com.watchtogether.app.dsa

import java.util.Arrays

/**
 * DSA: Circular Ring Buffer with Moving Median Filter.
 *
 * Used for NTP clock drift compensation in real-time video sync.
 * Instantaneous drift samples delta = (position_remote - position_local) are subject to
 * network packet jitter and TCP burstiness. A naive average is heavily biased by outliers.
 *
 * This filter maintains a fixed-size window of recent samples in a circular array (O(1) insertion)
 * and computes the exact median to reject transient network spikes.
 */
class DriftJitterFilter(val capacity: Int = 7) {
    init {
        require(capacity > 0 && capacity % 2 == 1) { "Capacity must be a positive odd number for unique median" }
    }

    private val ringBuffer = LongArray(capacity)
    private var writeIndex = 0
    private var count = 0

    @Synchronized
    fun addSample(sample: Long): Long {
        ringBuffer[writeIndex] = sample
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
        return getMedian()
    }

    @Synchronized
    fun getMedian(): Long {
        if (count == 0) return 0L
        if (count == 1) return ringBuffer[0]

        val copy = LongArray(count)
        System.arraycopy(ringBuffer, 0, copy, 0, count)
        Arrays.sort(copy)
        return copy[count / 2]
    }

    @Synchronized
    fun reset() {
        writeIndex = 0
        count = 0
        Arrays.fill(ringBuffer, 0L)
    }
}

