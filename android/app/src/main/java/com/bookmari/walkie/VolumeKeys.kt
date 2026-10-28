package com.bookmari.walkie

/**
 * What the volume keys do. Each key has a configurable [Act]:
 * - screen on (KeyAccessibilityService): a long press runs it, a short press is a normal volume step;
 * - screen off (experimental, the media session's volume provider): a press runs it. Android then gives us relative volume
 *   steps with no key-up and no hold time, so the actions that need a held key (talk hold, monologue) play an error tone.
 */
object VolumeKeys {
    enum class Act(val id: String) {
        DEFAULT("default"), TALK_TOGGLE("toggle"), TALK_HOLD("hold"), NEXT_CHANNEL("next"), MONOLOGUE("monologue"), VOLUME("volume")
    }

    enum class Action { TOGGLE_TALK, NEXT_CHANNEL, ERROR, STEP_UP, STEP_DOWN, IGNORE }

    const val PREF_DOWN = "vol_down_action"
    const val PREF_UP = "vol_up_action"

    /** Held keys repeat every ~50 ms after the first 500 ms; events closer together than this are one hold, not new presses. */
    const val REPEAT_GAP_MS = 700L

    fun parse(id: String?) = Act.values().firstOrNull { it.id == id } ?: Act.DEFAULT

    /** [Act.DEFAULT] is what shipped before this was configurable: hold down = talk, hold up = monologue; screen off: down = talk toggle, up = volume. */
    fun resolve(act: Act, up: Boolean, screenOn: Boolean) = when {
        act != Act.DEFAULT -> act
        screenOn -> if (up) Act.MONOLOGUE else Act.TALK_HOLD
        else -> if (up) Act.VOLUME else Act.TALK_TOGGLE
    }

    fun label(act: Act, up: Boolean) = when (act) {
        Act.DEFAULT -> if (up) R.string.act_default_up else R.string.act_default_down
        Act.TALK_TOGGLE -> R.string.act_talk_toggle
        Act.TALK_HOLD -> R.string.act_talk_hold
        Act.NEXT_CHANNEL -> R.string.act_next_channel
        Act.MONOLOGUE -> R.string.act_monologue
        Act.VOLUME -> R.string.act_volume
    }

    fun isFreshPress(nowMs: Long, lastEventMs: Long) = nowMs - lastEventMs >= REPEAT_GAP_MS

    /**
     * What a volume event from the media session's provider does. [direction] as in AudioManager: positive raises, negative lowers.
     * With the screen on the Accessibility service owns the long presses, so this is just a volume step.
     */
    fun action(configured: Act, direction: Int, screenOn: Boolean, freshPress: Boolean): Action {
        if (direction == 0) return Action.IGNORE
        val up = direction > 0
        val step = if (up) Action.STEP_UP else Action.STEP_DOWN
        if (screenOn) return step
        return when (resolve(configured, up, screenOn = false)) {
            Act.VOLUME, Act.DEFAULT -> step
            Act.TALK_TOGGLE -> if (freshPress) Action.TOGGLE_TALK else Action.IGNORE
            Act.NEXT_CHANNEL -> if (freshPress) Action.NEXT_CHANNEL else Action.IGNORE
            Act.TALK_HOLD, Act.MONOLOGUE -> if (freshPress) Action.ERROR else Action.IGNORE
        }
    }
}
