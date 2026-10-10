package org.seedhecode.core.inference

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class AudioEndpointTest {
    @Test fun `44100 Hz resampling has identical output across split frames`() {
        val samples = ShortArray(44100) { (sin(it * 0.05) * 12000).toInt().toShort() }
        val whole = PcmResampler(44100).accept(samples)
        val streaming = PcmResampler(44100)
        val split = samples.asList().chunked(882).flatMap { streaming.accept(it.toShortArray()).asList() }.toFloatArray()
        assertEquals(16000, whole.size)
        assertArrayEquals(whole, split, 0.00001f)
    }
    @Test fun `fixed window emits a segment but silence alone ends the turn`() {
        val endpoint = PauseEndpoint(maxSegmentSeconds = 1)
        val voice = FloatArray(16000) { 0.1f }
        val first = endpoint.accept(voice)
        assertTrue(first.speechStarted); assertNotNull(first.segment); assertFalse(first.turnEnded)
        val second = endpoint.accept(FloatArray(1600) { 0.1f })
        assertFalse(second.speechStarted); assertFalse(second.turnEnded)
        val final = endpoint.accept(FloatArray(12800))
        assertTrue(final.turnEnded); assertNotNull(final.segment)
    }
    @Test fun `silence and short clicks do not create conversational turns`() {
        val endpoint = PauseEndpoint()
        assertFalse(endpoint.accept(FloatArray(16000)).turnEnded)
        endpoint.accept(FloatArray(320) { 0.2f })
        assertFalse(endpoint.accept(FloatArray(16000)).turnEnded)
    }
}
