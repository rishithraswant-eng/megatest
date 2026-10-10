package org.seedhecode.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Standard RIFF/WAVE PCM16 mono file, finalized before it enters the library. */
class WavFile(private val destination: File, private val sampleRateHz: Int) : AutoCloseable {
    private val stream = RandomAccessFile(destination, "rw")
    var sampleCount: Long = 0
        private set

    init {
        require(sampleRateHz > 0)
        stream.setLength(0)
        stream.write(header(sampleRateHz, 0))
    }

    fun append(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) bytes.putShort(samples[index])
        stream.write(bytes.array())
        sampleCount += count
    }

    override fun close() {
        try {
            stream.seek(0)
            stream.write(header(sampleRateHz, sampleCount * 2))
        } finally {
            stream.close()
        }
    }

    companion object {
        fun header(sampleRateHz: Int, dataBytes: Long): ByteArray {
            require(sampleRateHz > 0)
            require(dataBytes in 0..(0xffffffffL - 36))
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36 + dataBytes).toInt())
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort()) // PCM
                putShort(1.toShort()) // Mono
                putInt(sampleRateHz)
                putInt(sampleRateHz * 2)
                putShort(2.toShort())
                putShort(16.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes.toInt())
            }.array()
        }
    }
}
