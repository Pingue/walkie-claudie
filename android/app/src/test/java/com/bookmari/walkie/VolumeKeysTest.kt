package com.bookmari.walkie

import com.bookmari.walkie.VolumeKeys.Act
import com.bookmari.walkie.VolumeKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeysTest {
    private val down = -1
    private val up = 1

    @Test fun parseKnownIdsAndFallBackToDefault() {
        for (a in Act.values()) assertEquals(a, VolumeKeys.parse(a.id))
        assertEquals(Act.DEFAULT, VolumeKeys.parse(null))
        assertEquals(Act.DEFAULT, VolumeKeys.parse("bogus"))
    }

    @Test fun defaultKeepsTheShippedBehaviour() {
        assertEquals(Act.TALK_HOLD, VolumeKeys.resolve(Act.DEFAULT, up = false, screenOn = true))
        assertEquals(Act.MONOLOGUE, VolumeKeys.resolve(Act.DEFAULT, up = true, screenOn = true))
        assertEquals(Act.TALK_TOGGLE, VolumeKeys.resolve(Act.DEFAULT, up = false, screenOn = false))
        assertEquals(Act.VOLUME, VolumeKeys.resolve(Act.DEFAULT, up = true, screenOn = false))
    }

    @Test fun anExplicitChoiceAppliesWhateverTheScreen() {
        for (screen in listOf(true, false)) for (u in listOf(true, false))
            assertEquals(Act.NEXT_CHANNEL, VolumeKeys.resolve(Act.NEXT_CHANNEL, u, screen))
    }

    @Test fun everyActHasALabel() {
        for (a in Act.values()) for (u in listOf(true, false)) assertTrue(VolumeKeys.label(a, u) != 0)
    }

    @Test fun screenOffTalkToggleOnAFreshPress() {
        assertEquals(Action.TOGGLE_TALK, VolumeKeys.action(Act.TALK_TOGGLE, down, screenOn = false, freshPress = true))
        assertEquals(Action.TOGGLE_TALK, VolumeKeys.action(Act.TALK_TOGGLE, up, screenOn = false, freshPress = true))
    }

    @Test fun screenOffNextChannelOnAFreshPress() =
        assertEquals(Action.NEXT_CHANNEL, VolumeKeys.action(Act.NEXT_CHANNEL, up, screenOn = false, freshPress = true))

    @Test fun screenOffHoldAndMonologueAreAnErrorOncePerPress() {
        for (a in listOf(Act.TALK_HOLD, Act.MONOLOGUE)) {
            assertEquals(Action.ERROR, VolumeKeys.action(a, down, screenOn = false, freshPress = true))
            assertEquals(Action.IGNORE, VolumeKeys.action(a, down, screenOn = false, freshPress = false)) // the key repeating while held
        }
    }

    @Test fun repeatsOfAHeldKeyNeverRetriggerAnAction() {
        for (a in listOf(Act.TALK_TOGGLE, Act.NEXT_CHANNEL))
            assertEquals(Action.IGNORE, VolumeKeys.action(a, down, screenOn = false, freshPress = false))
    }

    @Test fun volumeOnlyStepsEvenWhenHeld() {
        assertEquals(Action.STEP_UP, VolumeKeys.action(Act.VOLUME, up, screenOn = false, freshPress = false))
        assertEquals(Action.STEP_DOWN, VolumeKeys.action(Act.VOLUME, down, screenOn = false, freshPress = true))
    }

    @Test fun defaultScreenOffMatchesTheEarlierExperiment() {
        assertEquals(Action.TOGGLE_TALK, VolumeKeys.action(Act.DEFAULT, down, screenOn = false, freshPress = true))
        assertEquals(Action.IGNORE, VolumeKeys.action(Act.DEFAULT, down, screenOn = false, freshPress = false))
        assertEquals(Action.STEP_UP, VolumeKeys.action(Act.DEFAULT, up, screenOn = false, freshPress = true))
    }

    @Test fun screenOnProviderEventsAreJustVolumeSteps() {
        for (a in Act.values()) {
            assertEquals(Action.STEP_UP, VolumeKeys.action(a, up, screenOn = true, freshPress = true))
            assertEquals(Action.STEP_DOWN, VolumeKeys.action(a, down, screenOn = true, freshPress = true))
        }
    }

    @Test fun zeroDirectionDoesNothing() = assertEquals(Action.IGNORE, VolumeKeys.action(Act.TALK_TOGGLE, 0, screenOn = false, freshPress = true))

    @Test fun repeatsOfAHoldAreNotFreshButANewPressIs() {
        assertFalse(VolumeKeys.isFreshPress(nowMs = 1_500, lastEventMs = 1_000))
        assertFalse(VolumeKeys.isFreshPress(nowMs = 1_550, lastEventMs = 1_500))
        assertTrue(VolumeKeys.isFreshPress(nowMs = 3_000, lastEventMs = 1_550))
        assertTrue(VolumeKeys.isFreshPress(nowMs = 1_000, lastEventMs = Long.MIN_VALUE / 2))
    }
}
