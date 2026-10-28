package com.bookmari.walkie

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Makes other apps' music pause (or duck) while the walkie is speaking or listening, by holding transient audio focus.
 * Recording and a read-out reply can overlap, so focus is kept while anyone still holds it and given back with the last one.
 * Main thread only.
 */
class MusicFocus(private val backend: Backend, private val mode: () -> String) {
    interface Backend {
        fun request(gain: Int)
        fun abandon()
    }

    private val holders = HashSet<String>()
    private var held = false

    fun acquire(who: String) {
        holders.add(who)
        if (held) return
        val gain = gainFor(mode()) ?: return
        held = true
        backend.request(gain)
    }

    fun release(who: String) {
        holders.remove(who)
        if (holders.isEmpty()) abandon()
    }

    fun releaseAll() {
        holders.clear()
        abandon()
    }

    private fun abandon() {
        if (!held) return
        held = false
        backend.abandon()
    }

    class Android(private val ctx: Context) : Backend {
        private var request: AudioFocusRequest? = null

        override fun request(gain: Int) {
            val r = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { } // a call or another app taking focus: nothing for us to do
                .build()
            request = r
            ctx.getSystemService(AudioManager::class.java).requestAudioFocus(r)
        }

        override fun abandon() {
            request?.let { ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
            request = null
        }
    }

    companion object {
        const val PREF = "music_focus"
        const val OFF = "off"
        const val PAUSE = "pause"
        const val DUCK = "duck"
        val CHOICES = listOf(PAUSE, DUCK, OFF)

        /** The audio focus kind to ask for, or null when the music should be left alone. */
        fun gainFor(mode: String): Int? = when (mode) {
            PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> null
        }

        fun next(mode: String) = CHOICES[(CHOICES.indexOf(mode) + 1) % CHOICES.size]

        fun label(mode: String) = when (mode) {
            DUCK -> R.string.music_duck
            OFF -> R.string.music_off
            else -> R.string.music_pause
        }
    }
}
