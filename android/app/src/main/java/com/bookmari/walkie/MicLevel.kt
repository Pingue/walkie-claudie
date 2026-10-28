package com.bookmari.walkie

import kotlin.math.log10
import kotlin.math.sqrt

/** Loudness of 16-bit little-endian mono PCM, for the mic test's meter. */
object MicLevel {
    /** From here up, something is being picked up (speech at arm's length is around 0.5–0.8; a quiet room stays near 0). */
    const val SOUND = 0.4f

    /** 0 (silence) to 1 (very loud): RMS of the first [n] bytes in dBFS, with -60 dB as 0 and -10 dB as 1. */
    fun level(buf: ByteArray, n: Int): Float {
        val samples = n / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
        }
        val rms = sqrt(sum / samples)
        if (rms < 1.0) return 0f
        val db = 20 * log10(rms / 32768.0)
        return ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
    }

    /** Lit cells of the 10-cell meter. */
    fun cells(level: Float) = Math.round(level.coerceIn(0f, 1f) * 10)
}
