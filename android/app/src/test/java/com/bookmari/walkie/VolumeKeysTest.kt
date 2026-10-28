package com.bookmari.walkie

import com.bookmari.walkie.VolumeKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeysTest {
    @Test fun screenOffVolumeDownTogglesTalkOnAFreshPress() = assertEquals(Action.TOGGLE_TALK, VolumeKeys.action(-1, screenOn = false, freshPress = true))

    @Test fun screenOffVolumeDownRepeatsOfAHoldAreIgnored() = assertEquals(Action.IGNORE, VolumeKeys.action(-1, screenOn = false, freshPress = false))

    @Test fun volumeUpAlwaysRaisesTheVolume() {
        for (screen in listOf(true, false)) for (fresh in listOf(true, false))
            assertEquals(Action.STEP_UP, VolumeKeys.action(1, screen, fresh))
    }

    @Test fun screenOnVolumeDownStaysAVolumeStep() {
        assertEquals(Action.STEP_DOWN, VolumeKeys.action(-1, screenOn = true, freshPress = true))
        assertEquals(Action.STEP_DOWN, VolumeKeys.action(-1, screenOn = true, freshPress = false))
    }

    @Test fun zeroDirectionDoesNothing() = assertEquals(Action.IGNORE, VolumeKeys.action(0, screenOn = false, freshPress = true))

    @Test fun repeatsOfAHoldAreNotFreshButANewPressIs() {
        assertFalse(VolumeKeys.isFreshPress(nowMs = 1_500, lastEventMs = 1_000)) // first key repeat after 500 ms
        assertFalse(VolumeKeys.isFreshPress(nowMs = 1_550, lastEventMs = 1_500)) // then every ~50 ms
        assertTrue(VolumeKeys.isFreshPress(nowMs = 3_000, lastEventMs = 1_550)) // let go, pressed again later
        assertTrue(VolumeKeys.isFreshPress(nowMs = 1_000, lastEventMs = Long.MIN_VALUE / 2)) // the very first press
    }
}
