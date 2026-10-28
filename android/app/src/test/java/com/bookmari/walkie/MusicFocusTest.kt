package com.bookmari.walkie

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicFocusTest {
    private class Fake : MusicFocus.Backend {
        val log = ArrayList<String>()
        override fun request(gain: Int) { log.add("request $gain") }
        override fun abandon() { log.add("abandon") }
    }

    private val pause = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    private val duck = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

    @Test fun gainFollowsMode() {
        assertEquals(pause, MusicFocus.gainFor(MusicFocus.PAUSE))
        assertEquals(duck, MusicFocus.gainFor(MusicFocus.DUCK))
        assertNull(MusicFocus.gainFor(MusicFocus.OFF))
        assertNull(MusicFocus.gainFor("bogus"))
    }

    @Test fun requestsOnceAndAbandonsWithTheLastHolder() {
        val b = Fake()
        val f = MusicFocus(b) { MusicFocus.PAUSE }
        f.acquire("rec")
        f.acquire("tts") // a reply starts while still recording
        f.release("rec")
        assertEquals(listOf("request $pause"), b.log) // the reply still holds it
        f.release("tts")
        assertEquals(listOf("request $pause", "abandon"), b.log)
    }

    @Test fun duckAsksForDucking() {
        val b = Fake()
        MusicFocus(b) { MusicFocus.DUCK }.acquire("tts")
        assertEquals(listOf("request $duck"), b.log)
    }

    @Test fun offNeverTouchesFocus() {
        val b = Fake()
        val f = MusicFocus(b) { MusicFocus.OFF }
        f.acquire("rec")
        f.release("rec")
        f.releaseAll()
        assertEquals(emptyList<String>(), b.log)
    }

    @Test fun releasingWithoutAcquiringIsHarmless() {
        val b = Fake()
        val f = MusicFocus(b) { MusicFocus.PAUSE }
        f.release("tts")
        f.releaseAll()
        assertEquals(emptyList<String>(), b.log)
    }

    @Test fun acquiringTwiceBySameHolderNeedsOneRelease() {
        val b = Fake()
        val f = MusicFocus(b) { MusicFocus.PAUSE }
        f.acquire("tts")
        f.acquire("tts")
        f.release("tts")
        assertEquals(listOf("request $pause", "abandon"), b.log)
    }

    @Test fun releaseAllGivesFocusBackAndAllowsANewRequest() {
        val b = Fake()
        val f = MusicFocus(b) { MusicFocus.PAUSE }
        f.acquire("rec")
        f.acquire("tts")
        f.releaseAll()
        f.acquire("rec")
        assertEquals(listOf("request $pause", "abandon", "request $pause"), b.log)
    }

    @Test fun modeChangeAppliesToTheNextRequest() {
        val b = Fake()
        var mode = MusicFocus.OFF
        val f = MusicFocus(b) { mode }
        f.acquire("rec")
        f.release("rec")
        mode = MusicFocus.PAUSE
        f.acquire("rec")
        assertEquals(listOf("request $pause"), b.log)
    }

    @Test fun nextCyclesAllChoices() {
        assertEquals(MusicFocus.DUCK, MusicFocus.next(MusicFocus.PAUSE))
        assertEquals(MusicFocus.OFF, MusicFocus.next(MusicFocus.DUCK))
        assertEquals(MusicFocus.PAUSE, MusicFocus.next(MusicFocus.OFF))
        assertEquals(MusicFocus.PAUSE, MusicFocus.next("bogus"))
    }
}
