package org.seedhecode.core.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WavFileTest {
    @Test fun `finalized audio round trips as little endian PCM with correct duration`() {
        val directory = Files.createTempDirectory("incident-audio-test").toFile()
        val clip = File(directory, "recording.wav")
        try {
            val samples = ShortArray(16_000) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
            WavFile(clip, 16_000).use { it.append(samples, samples.size) }
            val bytes = clip.readBytes()
            val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(32_044, bytes.size)
            assertEquals(32_036, view.getInt(4))
            assertEquals(32_000, view.getInt(40))
            assertEquals(Short.MIN_VALUE, view.getShort(44))
            assertEquals(Short.MAX_VALUE, view.getShort(46))
            val recording = RecordingStore(directory).list().single()
            assertEquals(1000L, recording.durationMs)
            assertEquals(16_000, recording.sampleRateHz)
        } finally { clip.delete(); directory.delete() }
    }

    @Test fun `unfinished and truncated recordings do not enter the library`() {
        val directory = Files.createTempDirectory("incident-audio-test").toFile()
        val partial = File(directory, "unfinished.wav.part")
        val truncated = File(directory, "truncated.wav")
        try {
            partial.writeBytes(WavFile.header(16_000, 0))
            truncated.writeBytes(WavFile.header(16_000, 100))
            assertTrue(RecordingStore(directory).list().isEmpty())
        } finally { partial.delete(); truncated.delete(); directory.delete() }
    }

    @Test fun `deletion cannot target a file outside app recording storage`() {
        val directory = Files.createTempDirectory("incident-audio-test").toFile()
        try {
            val outside = File(directory.parentFile, "outside.wav")
            val result = runCatching { RecordingStore(directory).delete(VoiceRecording(outside, 16_000, 0, 0)) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        } finally { directory.delete() }
    }
}
