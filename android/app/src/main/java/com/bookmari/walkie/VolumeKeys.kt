package com.bookmari.walkie

/**
 * Experimental: volume keys while the screen is off. Android only hands them to the active media session then, as relative
 * volume steps with no key-up and no hold time, so hold-to-talk stays a screen-on feature (KeyAccessibilityService).
 * With the screen off, a press of volume down starts talking and the next press sends; volume up still raises the volume.
 */
object VolumeKeys {
    enum class Action { TOGGLE_TALK, STEP_UP, STEP_DOWN, IGNORE }

    /** Held keys repeat every ~50 ms after the first 500 ms; events closer together than this are one hold, not new presses. */
    const val REPEAT_GAP_MS = 700L

    fun isFreshPress(nowMs: Long, lastEventMs: Long) = nowMs - lastEventMs >= REPEAT_GAP_MS

    /** [direction] as in AudioManager: positive raises, negative lowers, 0 is a no-op. */
    fun action(direction: Int, screenOn: Boolean, freshPress: Boolean) = when {
        direction > 0 -> Action.STEP_UP
        direction < 0 -> if (screenOn) Action.STEP_DOWN else if (freshPress) Action.TOGGLE_TALK else Action.IGNORE
        else -> Action.IGNORE
    }
}
