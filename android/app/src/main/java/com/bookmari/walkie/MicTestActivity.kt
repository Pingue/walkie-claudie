package com.bookmari.walkie

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mic test: records with the mic chosen in Settings (the same route and recognizer pipe as monologue mode) and shows a level
 * meter, the input device Android actually routed to, and what the speech recognizer heard. Nothing is sent to the PC.
 */
class MicTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var input: TextView
    private lateinit var status: TextView
    private lateinit var heardText: TextView
    private lateinit var meter: Cells
    private lateinit var startKey: TextView

    private var test: Monologue? = null
    private var heard = ""
    private var peak = 0f
    private var shown = 0f
    private var routed = ""
    private var destroyed = false
    private val timeout = Runnable { stop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val body = Body(this) {}

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(lcdText(Ui.S).apply { setText(R.string.mic_test); alpha = 0.7f })
        info.addView(lcdText(Ui.M).apply { text = getString(R.string.mic_selected, getString(Mic.label(Mic.pref(context)))) })
        input = lcdText(Ui.S).apply { alpha = 0.7f; wrapByWord() }
        info.addView(input)
        body.lcd(info)

        val result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        meter = Cells(this)
        result.addView(meter, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(10) })
        status = lcdText(Ui.M).apply { wrapByWord() }
        result.addView(status)
        heardText = lcdText(Ui.M).apply { wrapByWord() }
        result.addView(heardText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })
        body.lcd(result, weight = 1f)

        body.key(key(R.string.back) { finish() })
        startKey = key(R.string.mic_test_start) { if (test == null) start() else stop() }
        body.key(startKey)
        idle()
    }

    private fun idle() {
        meter.lit = 0
        input.text = getString(R.string.mic_test_idle_input)
        status.setText(if (android.os.Build.VERSION.SDK_INT >= 33) R.string.mic_test_hint else R.string.mic_test_needs_13)
    }

    private fun start() {
        when {
            android.os.Build.VERSION.SDK_INT < 33 -> return idle()
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                return requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 0)
            Talk.listening || inCall() -> return status.setText(R.string.mic_test_busy)
        }
        heard = ""; peak = 0f; shown = 0f; routed = ""
        heardText.text = ""
        status.setText(R.string.mic_test_speak)
        input.text = getString(R.string.mic_test_idle_input)
        startKey.text = getString(R.string.mic_test_stop)
        Mic.open(this)
        main.postDelayed(timeout, 30_000)
        test = Monologue(this, onPartial = { heard = it; heardText.text = it }, onLevel = ::onLevel, onRouted = ::onRouted) { text ->
            finished(text)
        }.also { it.start() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else status.setText(R.string.mic_denied)
    }

    private fun stop() {
        main.removeCallbacks(timeout)
        test?.stop() // the text arrives in finished()
    }

    private fun onRouted(device: AudioDeviceInfo?) {
        routed = Mic.describe(this, device)
        input.text = getString(R.string.mic_test_input, routed)
    }

    private fun onLevel(level: Float) {
        peak = maxOf(peak, level)
        shown = maxOf(level, shown * 0.8f) // falls back gently so quick words are visible
        meter.lit = MicLevel.cells(shown)
        if (test != null && heard.isEmpty() && level >= MicLevel.SOUND) status.setText(R.string.mic_test_sound)
    }

    private fun finished(text: String) {
        test = null
        Mic.close(this)
        if (destroyed) return
        main.removeCallbacks(timeout)
        startKey.text = getString(R.string.mic_test_start)
        meter.lit = 0
        heardText.text = if (text.isEmpty()) "" else getString(R.string.mic_test_heard, text)
        status.setText(when {
            text.isNotEmpty() -> R.string.mic_test_ok
            peak >= MicLevel.SOUND -> R.string.mic_test_sound_only
            else -> R.string.mic_test_silent
        })
    }

    override fun onPause() {
        super.onPause()
        if (test != null) stop() // never keep recording in the background
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(timeout)
        super.onDestroy()
    }
}
