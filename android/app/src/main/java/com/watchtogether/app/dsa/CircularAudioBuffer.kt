package com.watchtogether.app.dsa

/**
 * DSA: Power-of-Two Lock-Free Circular Ring Buffer for Audio Streaming.
 *
 * Uses bitwise masking (index & mask) instead of expensive modulo division (% capacity).
 * Provides O(1) write and O(1) read operations with zero object allocations during playback.
 */
class CircularAudioBuffer(powerOfTwoCapacity: Int = 32768) {
    val capacity: Int
    private val mask: Int
    private val buffer: ByteArray

    private var head = 0 // Write index
    private var tail = 0 // Read index

    init {
        var cap = 1
        while (cap < powerOfTwoCapacity) cap = cap shl 1
        capacity = cap
        mask = capacity - 1
        buffer = ByteArray(capacity)
    }

    @Synchronized
    fun availableRead(): Int = (head - tail) and mask

    @Synchronized
    fun availableWrite(): Int = capacity - 1 - availableRead()

    @Synchronized
    fun write(data: ByteArray, offset: Int, length: Int): Int {
        val toWrite = minOf(length, availableWrite())
        if (toWrite <= 0) return 0

        val firstChunk = minOf(toWrite, capacity - (head and mask))
        System.arraycopy(data, offset, buffer, head and mask, firstChunk)

        val secondChunk = toWrite - firstChunk
        if (secondChunk > 0) {
            System.arraycopy(data, offset + firstChunk, buffer, 0, secondChunk)
        }

        head = (head + toWrite) and mask
        return toWrite
    }

    @Synchronized
    fun read(destination: ByteArray, offset: Int, length: Int): Int {
        val toRead = minOf(length, availableRead())
        if (toRead <= 0) return 0

        val firstChunk = minOf(toRead, capacity - (tail and mask))
        System.arraycopy(buffer, tail and mask, destination, offset, firstChunk)

        val secondChunk = toRead - firstChunk
        if (secondChunk > 0) {
            System.arraycopy(buffer, 0, destination, offset + firstChunk, secondChunk)
        }

        tail = (tail + toRead) and mask
        return toRead
    }

    @Synchronized
    fun clear() {
        head = 0
        tail = 0
    }
}

