package org.seedhecode.core.inference

import kotlin.math.sqrt

/** Streaming resampler retains fractional position across arbitrary frame boundaries. */
class PcmResampler(private val inputRate: Int, private val outputRate: Int = 16_000) {
    private var previous: Float? = null
    private var next = 0.0
    private var position = 0L
    init { require(inputRate > 0 && outputRate > 0) }
    fun accept(samples: ShortArray): FloatArray {
        val output = ArrayList<Float>()
        val step = inputRate.toDouble() / outputRate
        for (sample in samples) {
            val current = sample / 32768f
            val left = previous
            if (left == null) {
                if (next == 0.0) { output.add(current); next += step }
            } else {
                while (next <= position) {
                    val fraction = (next - (position - 1)).toFloat()
                    output.add(left + (current - left) * fraction)
                    next += step
                }
            }
            previous = current
            position++
        }
        return output.toFloatArray()
    }
}

data class EndpointEvent(val speechStarted: Boolean, val segment: FloatArray?, val turnEnded: Boolean)

/** Energy endpointing is a baseline; measure/calibrate it with the kit's noisy recordings.
 * Fixed windows flush segments to bound RAM; only silence ends the conversational turn.
 */
class PauseEndpoint(
    private val threshold: Double = 0.015,
    private val pauseMs: Int = 800,
    private val minSpeechMs: Int = 200,
    private val maxSegmentSeconds: Int = 20,
) {
    private val audio = ArrayList<Float>()
    private val preroll = ArrayDeque<Float>()
    private var speaking = false
    private var silence = 0
    private var voiced = 0
    fun accept(pcm: FloatArray): EndpointEvent {
        if (pcm.isEmpty()) return EndpointEvent(false, null, false)
        val rms = sqrt(pcm.fold(0.0) { sum, value -> sum + value * value } / pcm.size)
        val speech = rms >= threshold
        val started = speech && !speaking
        if (started) { speaking = true; audio.addAll(preroll); preroll.clear() }
        if (!speaking) {
            pcm.forEach { preroll.addLast(it) }
            while (preroll.size > 3200) preroll.removeFirst()
            return EndpointEvent(false, null, false)
        }
        pcm.forEach { audio.add(it) }
        if (speech) { voiced += pcm.size; silence = 0 } else silence += pcm.size
        if (silence >= pauseMs * 16) {
            val valid = voiced >= minSpeechMs * 16
            val chunk = if (valid && audio.isNotEmpty()) audio.toFloatArray() else null
            reset()
            return EndpointEvent(started, chunk, valid)
        }
        if (audio.size >= maxSegmentSeconds * 16_000) {
            val chunk = audio.toFloatArray()
            audio.clear()
            return EndpointEvent(started, chunk, false)
        }
        return EndpointEvent(started, null, false)
    }
    fun preview(): FloatArray? = if (speaking && audio.size >= 16_000) audio.toFloatArray() else null
    fun flush(): FloatArray? = if (voiced >= minSpeechMs * 16 && audio.isNotEmpty()) audio.toFloatArray().also { reset() } else null
    fun reset() { audio.clear(); preroll.clear(); speaking = false; silence = 0; voiced = 0 }
}
