package org.seedhecode.core.inference

/** Owned mono PCM16 samples. A backend must not assume a fixed sample rate. */
data class AudioFrame(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val startSample: Long,
)

data class TranscriptUpdate(
    val sessionId: String,
    val segmentId: Long,
    val revision: Long,
    val text: String,
    val isFinal: Boolean,
)

/**
 * Backend boundary for a future local ASR + LNN + semantic-model pipeline.
 * Audio callbacks occur on the capture worker: implementations must enqueue
 * bounded work and return promptly, never block microphone reads on inference.
 */
interface VoiceEngine : AutoCloseable {
    fun beginSession(sessionId: String, sampleRateHz: Int)
    fun accept(frame: AudioFrame)
    fun endSession()
    override fun close()
}

/** Recording-only starter. No fabricated transcript or remote speech service. */
class CaptureOnlyVoiceEngine : VoiceEngine {
    override fun beginSession(sessionId: String, sampleRateHz: Int) = Unit
    override fun accept(frame: AudioFrame) = Unit
    override fun endSession() = Unit
    override fun close() = Unit
}
