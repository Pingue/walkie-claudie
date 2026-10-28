package com.bookmari.walkie

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicLevelTest {
    /** A sine at [amplitude] (of 32767), as little-endian 16-bit PCM. */
    private fun sine(amplitude: Double, samples: Int = 1_600): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amplitude * 32767 * sin(2 * PI * 440 * i / 16_000)).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun silenceIsZero() = assertEquals(0f, MicLevel.level(ByteArray(3_200), 3_200), 0f)

    @Test fun emptyOrOneByteIsZero() {
        assertEquals(0f, MicLevel.level(ByteArray(0), 0), 0f)
        assertEquals(0f, MicLevel.level(ByteArray(1), 1), 0f)
    }

    @Test fun quietNoiseStaysBelowTheSoundThreshold() {
        val b = sine(0.002) // about -60 dBFS
        assertTrue(MicLevel.level(b, b.size) < MicLevel.SOUND)
    }

    @Test fun normalSpeechLevelCountsAsSound() {
        val b = sine(0.1) // peak -20 dBFS, rms about -23
        assertTrue(MicLevel.level(b, b.size) >= MicLevel.SOUND)
    }

    @Test fun fullScaleIsClampedToOne() {
        val b = sine(1.0)
        assertEquals(1f, MicLevel.level(b, b.size), 0f)
    }

    @Test fun louderIsHigher() {
        val quiet = sine(0.02)
        val loud = sine(0.2)
        assertTrue(MicLevel.level(loud, loud.size) > MicLevel.level(quiet, quiet.size))
    }

    @Test fun onlyTheFirstNBytesCount() {
        val b = sine(0.2) + ByteArray(3_200) // loud, then silence
        assertTrue(MicLevel.level(b, 3_200) > 0.5f)
        assertEquals(0f, MicLevel.level(b.copyOfRange(3_200, b.size), 3_200), 0f)
    }

    @Test fun negativeSamplesAreSignedNotBytes() {
        // 0x8000 = -32768 for every sample: full scale
        val b = ByteArray(200) { if (it % 2 == 1) 0x80.toByte() else 0 }
        assertEquals(1f, MicLevel.level(b, b.size), 0f)
    }

    @Test fun cellsRoundAndClamp() {
        assertEquals(0, MicLevel.cells(0f))
        assertEquals(5, MicLevel.cells(0.5f))
        assertEquals(10, MicLevel.cells(1f))
        assertEquals(10, MicLevel.cells(3f))
        assertEquals(0, MicLevel.cells(-1f))
    }
}
