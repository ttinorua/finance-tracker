package com.financetracker.app

import com.financetracker.app.util.VoiceAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioTest {

    @Test
    fun `answers are split into sentence pieces, short ones joined`() {
        val parts = VoiceAudio.sentences("Hi. You spent 4,200 kr on food this month, which is a bit more than usual. Groceries were most of it! Okay?")
        assertEquals(2, parts.size)
        assertTrue(parts[0].startsWith("Hi. You spent"))
        assertTrue(parts[1].endsWith("Okay?"))
        assertEquals(emptyList<String>(), VoiceAudio.sentences("  "))
    }

    @Test
    fun `wav round trip keeps the samples`() {
        val pcm = ByteArray(100) { it.toByte() }
        val wav = VoiceAudio.wav(pcm, 16000)
        assertEquals(144, wav.size)
        assertArrayEquals(pcm, VoiceAudio.pcmOf(wav))
        assertArrayEquals(pcm, VoiceAudio.pcmOf(pcm))
    }

    @Test
    fun `recording stops after speech then silence, or when nobody speaks`() {
        val end = VoiceAudio.EndOfSpeech(threshold = 500.0, silenceMs = 1000, noSpeechMs = 3000)
        assertFalse(end.feed(2000.0, 100))
        repeat(9) { assertFalse(end.feed(10.0, 100)) }
        assertTrue(end.feed(10.0, 100))

        val nobody = VoiceAudio.EndOfSpeech(threshold = 500.0, noSpeechMs = 3000)
        repeat(29) { assertFalse(nobody.feed(10.0, 100)) }
        assertTrue(nobody.feed(10.0, 100))
        assertFalse(nobody.heardSpeech)
    }

    @Test
    fun `loudness of silence is zero`() {
        assertEquals(0.0, VoiceAudio.rms(ShortArray(10)), 0.0)
        assertEquals(1000.0, VoiceAudio.rms(ShortArray(10) { 1000 }), 0.001)
    }
}
