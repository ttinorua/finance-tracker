package com.financetracker.app.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Small, pure pieces of the voice chat: splitting an answer into sentences (so the first one can
 * play while the next is being made), WAV in and out, and noticing when the person has stopped
 * talking.
 */
object VoiceAudio {

    /** [text] in sentence-sized pieces, short ones joined so each request says something. */
    fun sentences(text: String, minLength: Int = 40, maxLength: Int = 400): List<String> {
        val raw = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (s in raw) {
            if (current.isNotEmpty() && current.length + s.length + 1 > maxLength) {
                out += current.toString(); current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
            if (current.length >= minLength) { out += current.toString(); current.clear() }
        }
        if (current.isNotEmpty()) {
            if (out.isNotEmpty() && current.length < minLength / 2) out[out.lastIndex] = out.last() + " " + current
            else out += current.toString()
        }
        return out
    }

    /** 16-bit mono PCM wrapped as a WAV file (what Whisper accepts). */
    fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header); write(pcm) }.toByteArray()
    }

    /** The raw PCM inside a WAV file, or [bytes] unchanged when it isn't one (already raw PCM). */
    fun pcmOf(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return bytes
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return bytes.copyOfRange(pos + 8, minOf(bytes.size, pos + 8 + size.coerceAtLeast(0)))
            pos += 8 + size + (size and 1)
        }
        return bytes.copyOfRange(minOf(44, bytes.size), bytes.size)
    }

    /** Loudness (RMS) of a block of 16-bit samples, 0..32768. */
    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / count)
    }

    /**
     * Decides when to stop recording: after the person has spoken and then been quiet for
     * [silenceMs], when nobody speaks within [noSpeechMs], or at [maxMs]. Feed it each block's
     * loudness and length.
     */
    class EndOfSpeech(
        private val threshold: Double = 900.0,
        private val silenceMs: Long = 1_300,
        private val noSpeechMs: Long = 8_000,
        private val maxMs: Long = 45_000
    ) {
        var heardSpeech = false
            private set
        private var elapsed = 0L
        private var quiet = 0L

        /** True when recording should stop. */
        fun feed(rms: Double, blockMs: Long): Boolean {
            elapsed += blockMs
            if (rms >= threshold) { heardSpeech = true; quiet = 0 } else quiet += blockMs
            return elapsed >= maxMs || (heardSpeech && quiet >= silenceMs) || (!heardSpeech && elapsed >= noSpeechMs)
        }
    }
}
