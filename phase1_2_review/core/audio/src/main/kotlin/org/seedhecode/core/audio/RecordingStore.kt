package org.seedhecode.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VoiceRecording(
    val file: File,
    val sampleRateHz: Int,
    val durationMs: Long,
    val createdAtMs: Long,
)

/** Files are restricted to the application's private recordings directory. */
class RecordingStore(val directory: File) {
    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording storage." } }

    fun list(): List<VoiceRecording> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "wav" }
        .mapNotNull(::read)
        .sortedByDescending { it.createdAtMs }

    fun read(file: File): VoiceRecording? = runCatching {
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        require(file.length() > 44)
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(header, 8, 8, Charsets.US_ASCII) == "WAVEfmt ")
        require(String(header, 36, 4, Charsets.US_ASCII) == "data")
        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.getInt(16) == 16)
        require(bytes.getShort(20).toInt() == 1 && bytes.getShort(22).toInt() == 1)
        require(bytes.getShort(34).toInt() == 16)
        val rate = bytes.getInt(24)
        val size = bytes.getInt(40).toLong() and 0xffffffffL
        require(rate > 0 && size > 0 && size % 2 == 0L && size == file.length() - 44)
        VoiceRecording(file, rate, size * 1000 / (rate * 2L), file.lastModified())
    }.getOrNull()

    fun delete(recording: VoiceRecording): Boolean {
        require(recording.file.canonicalFile.parentFile == directory.canonicalFile)
        require(recording.file.extension == "wav")
        return recording.file.delete()
    }
}
