package com.bookmari.walkie

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bookmari.walkie.VolumeKeys.Act

/**
 * Volume keys while the walkie is on and the screen is on (Android gives accessibility no keys with the screen off; the experimental
 * screen-off path is the media session's volume provider in WalkieService). Each key runs its configured [Act] (Settings; default:
 * volume down = talk while held, volume up = monologue on/off) when long-pressed. Volume keys are held back until we know:
 * released within [LONG_MS] → we apply the volume step ourselves; held longer → the action. A key set to "volume only" is left to the system.
 * Also feeds the button-test log.
 */
class KeyAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var holding = false
    private var action = Act.VOLUME
    private var downKey = 0
    private val startHold = Runnable {
        holding = true
        val walkie = WalkieService.instance ?: return@Runnable
        when (action) {
            Act.TALK_HOLD -> walkie.holdStart()
            Act.TALK_TOGGLE -> walkie.talkToggle()
            Act.NEXT_CHANNEL -> walkie.nextChannel()
            Act.MONOLOGUE -> walkie.monologue() // the beep says "let go"
            else -> {}
        }
    }

    override fun onServiceConnected() = KeyLog.add("a11y", "service connected")

    override fun onKeyEvent(event: KeyEvent): Boolean {
        KeyLog.key("a11y", event)
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!up && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val prefs = getSharedPreferences(WalkieService.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(WalkieService.VOLUME, true) || WalkieService.instance == null || inCall()) return false // in a call: normal call volume
        val act = VolumeKeys.resolve(VolumeKeys.parse(prefs.getString(if (up) VolumeKeys.PREF_UP else VolumeKeys.PREF_DOWN, null)), up, screenOn = true)
        if (act == Act.VOLUME) return false
        // ponytail: one volume key at a time; pressing both at once is treated as two separate presses.
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) { holding = false; downKey = event.keyCode; action = act; main.postDelayed(startHold, LONG_MS) }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(startHold)
                // Releasing the key that started a talk-hold recording always sends, even if a stray second DOWN reset [holding].
                if (action == Act.TALK_HOLD && event.keyCode == downKey && Talk.hold && Talk.listening) WalkieService.instance?.holdEnd()
                else if (!holding) getSystemService(AudioManager::class.java).adjustSuggestedStreamVolume(
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
                )
                holding = false
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    companion object {
        /** Shorter presses stay volume steps. Measured taps were 100–170 ms, holds 700 ms+. */
        const val LONG_MS = 400L
    }
}
