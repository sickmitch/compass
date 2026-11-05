package org.compass.cng.voice

import kotlin.math.*

data class VoiceTiming(val initialSilenceMs: Int = 8000, val trailingSilenceMs: Int = 1200,
    val minimumSpeechMs: Int = 224, val maximumSpeechMs: Int = 20000, val preRollMs: Int = 500) {
    init {
        require(initialSilenceMs in 1000..15000 && trailingSilenceMs in 600..3000)
        require(minimumSpeechMs in 96..1000 && maximumSpeechMs in 3000..25000 && preRollMs in 200..1000)
    }
    val maximumSamples get() = (initialSilenceMs + maximumSpeechMs + 2000) * 16
}

/** Probability comes from Silero; timeouts are measured in captured audio, not inference latency. */
class SpeechEndpoint(private val timing: VoiceTiming) {
    var samples = 0; private set
    var speechStart = -1; private set
    private var runStart = -1
    private var lastSpeech = 0
    var complete = false; private set
    fun accept(probability: Float, count: Int = 512) {
        samples += count
        if (probability >= .5f) {
            if (runStart < 0) runStart = samples - count
            lastSpeech = samples
            if (speechStart < 0 && samples - runStart >= timing.minimumSpeechMs * 16) speechStart = runStart
        } else runStart = -1
        if (speechStart < 0 && samples >= (timing.initialSilenceMs + timing.minimumSpeechMs) * 16)
            throw VoiceFailure("no_speech", "Non ho sentito una richiesta. Riprova.")
        if (speechStart >= 0) {
            if (samples - lastSpeech >= timing.trailingSilenceMs * 16) complete = true
            if (!complete && samples - speechStart >= timing.maximumSpeechMs * 16)
                throw VoiceFailure("too_long", "Richiesta troppo lunga. Ripeti con una frase più breve.")
        }
    }
    val firstSample get() = (speechStart - timing.preRollMs * 16).coerceAtLeast(0)
}

/** Streaming windowed-sinc low-pass resampler. Preserves phase across AudioRecord reads. */
class PcmResampler(private val inputRate: Int) {
    private val outputRate = 16000
    private val radius = 24
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    private val divisor = gcd(inputRate, outputRate)
    private val weights = Array(outputRate / divisor) { phase ->
        val fraction = phase.toDouble() * divisor / outputRate
        val cutoff = .45 * min(1.0, outputRate.toDouble() / inputRate)
        val values = DoubleArray(2 * radius + 1) { index ->
            val x = index - radius - fraction
            val sinc = if (abs(x) < 1e-9) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            sinc * (0.5 + .5 * cos(PI * x / (radius + 1)))
        }
        val sum = values.sum()
        FloatArray(values.size) { (values[it] / sum).toFloat() }
    }
    private val buffer = ArrayDeque<Float>().apply { repeat(radius) { add(0f) } }
    private var start = -radius.toLong()
    private var next = 0L
    init { require(inputRate in setOf(16000, 44100, 48000)) }
    fun accept(pcm: ShortArray, count: Int): FloatArray {
        require(count in 0..pcm.size)
        if (inputRate == outputRate) return FloatArray(count) { pcm[it] / 32768f }
        repeat(count) { buffer.addLast(pcm[it] / 32768f) }
        val output = ArrayList<Float>(count * outputRate / inputRate + 1)
        while (next / outputRate + radius < start + buffer.size) {
            val center = next / outputRate
            val coefficients = weights[((next % outputRate) / divisor).toInt()]
            var value = 0f
            for (i in coefficients.indices) value += buffer[(center - radius - start).toInt() + i] * coefficients[i]
            output.add(value)
            next += inputRate
            while (start < next / outputRate - radius) { buffer.removeFirst(); start++ }
        }
        return output.toFloatArray()
    }
}
